package cp.player.core.provider

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import cp.player.core.util.PlatformContext
import cp.player.core.util.createHttpClient
import cp.player.core.util.defaultSettingsStorage
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.util.toMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 基于 QuickJS 运行时的 CPPlayer Standard JS 音源实现。
 *
 * 核心特性：
 * 1. 跨平台统一：在 Desktop (JVM) 与 Android 端共用同一套 QuickJS 原生 C 引擎；
 * 2. 现代 JS 支持：支持 ES2023、异步 Promise / async / await 顶层求值；
 * 3. 宿主原生加速：
 *    - `fetch(url, options)`: 桥接 Ktor Client，自动处理 Header、Cookie 与异步网络；
 *    - `crypto`: 原生提供 MD5, SHA-1, SHA-256, Base64, AES-128/256 (CBC/ECB), RSA 公钥加密；
 *    - `storage`: 独立于每个音源 ID 的键值持久化；
 *    - `console`: 重定向至宿主统一日志；
 *    - `env.settings`: 自动注入用户在设置页配置的表单参数；
 * 4. 线程安全：每个音源插件拥有独立的 QuickJS 实例，并在专属单线程调度器上调度，完全隔离内存与全局状态。
 */
class JsBackendProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    private val scriptPath: String,
    private val moduleDir: String,
    override val apiMap: Map<String, String>? = null,
    override val updateUrl: String? = null,
    override val targetAppPackage: String? = null,
    override val loginMethods: List<String>? = null,
    override val capabilities: List<String>? = null,
    override val apiVersion: Int = 1,
    override val apiVersionTag: String? = null,
    override val contract: String? = null,
    override val settingsSchema: List<ProviderSettingItemSchema>? = null,
    private val httpClient: HttpClient = createHttpClient()
) : BackendProvider, AutoCloseable {

    override val type: ProviderType = ProviderType.SCRIPT

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val configStorage by lazy { defaultSettingsStorage("provider_config_$id") }
    private val jsStorage by lazy { defaultSettingsStorage("provider_js_$id") }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CPPlayer-JsProvider-$id").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var quickJs: QuickJs? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isClosed = false

    override fun startServer(context: PlatformContext, port: Int) {
        isReady()
    }

    override fun stopServer() {
        close()
    }

    override fun analyzeAudio(path: String): String = """{"code": 500, "msg": "Not supported"}"""

    @Synchronized
    override fun isReady(): Boolean {
        if (isClosed) return false
        if (isInitialized) return true
        return try {
            runBlocking(dispatcher) {
                doInit()
            }
            true
        } catch (e: Throwable) {
            println("[JsBackendProvider] [$id] 初始化失败: ${e.message}")
            false
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        runBlocking(dispatcher) {
            try {
                quickJs?.close()
            } catch (ignored: Throwable) {
            } finally {
                quickJs = null
            }
        }
        executor.shutdown()
    }

    /**
     * 同步 API 契约调用入口。
     */
    override fun callApi(method: String, params: Map<String, String>): String {
        if (isClosed) {
            return """{"code":500,"message":"Provider [$id] 已关闭"}"""
        }

        val mapped = apiMap?.get(method) ?: method
        if (mapped == "unsupported") {
            return """{"code":404,"message":"音源 [$id] 声明不支持方法: $method"}"""
        }

        return try {
            runBlocking(dispatcher) {
                if (!isInitialized) {
                    doInit()
                }
                val qjs = quickJs ?: error("QuickJS 引擎未能初始化")

                val paramsJson = buildJsonObject {
                    // 1. 注入用户设置项作为基础上下文
                    settingsSchema?.forEach { item ->
                        val v = configStorage.getString(item.key) ?: item.default
                        if (v != null) put(item.key, JsonPrimitive(v))
                    }
                    // 2. 覆盖实际传入的业务参数
                    params.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                }.toString()

                val callScript = "await __cp_dispatch(${encodeJsString(mapped)}, $paramsJson);"
                val result = qjs.evaluate<String>(callScript)
                result ?: """{"code":204,"message":"No Content"}"""
            }
        } catch (e: Throwable) {
            val err = e.message?.replace("\"", "\\\"") ?: "未知执行错误"
            println("[JsBackendProvider] [$id] 调用方法 [$method] 失败: $err")
            """{"code":500,"message":"JS 执行异常: $err"}"""
        }
    }

    private suspend fun doInit() {
        if (isInitialized) return
        check(!isClosed) { "Provider [$id] 已被关闭" }

        val qjs = QuickJs.create(dispatcher)
        quickJs = qjs

        // 1. 绑定宿主 HTTP fetch 异步桥接
        qjs.asyncFunction<String>("__host_fetch") { args ->
            val reqJson = args.getOrNull(0)?.toString() ?: "{}"
            handleHostFetch(reqJson)
        }

        // 2. 绑定宿主原生加解密工具
        qjs.function<String>("__host_crypto") { args ->
            val reqJson = args.getOrNull(0)?.toString() ?: "{}"
            handleHostCrypto(reqJson)
        }

        // 3. 绑定宿主本地持久化存储
        qjs.function<String>("__host_storage") { args ->
            val reqJson = args.getOrNull(0)?.toString() ?: "{}"
            handleHostStorage(reqJson)
        }

        // 4. 绑定宿主日志
        qjs.function<Unit>("__host_log") { args ->
            val level = args.getOrNull(0)?.toString() ?: "INFO"
            val message = args.getOrNull(1)?.toString() ?: ""
            println("[JS:$id][$level] $message")
        }

        // 5. 注入宿主 JS 引导垫片（Web API 与 Standard v1 分发器）
        val configSnapshot = buildJsonObject {
            settingsSchema?.forEach { item ->
                val v = configStorage.getString(item.key) ?: item.default
                if (v != null) put(item.key, JsonPrimitive(v))
            }
        }.toString()

        val bootstrapCode = buildBootstrapJs(configSnapshot)
        qjs.evaluate<Any?>(bootstrapCode)

        // 6. 加载并求值音源插件入口脚本
        val scriptFile = File(scriptPath)
        check(scriptFile.exists()) { "入口脚本不存在: $scriptPath" }
        val scriptSource = scriptFile.readText(Charsets.UTF_8)
        qjs.evaluate<Any?>(scriptSource)

        isInitialized = true
        println("[JsBackendProvider] [$id] 初始化成功 (script: ${scriptFile.name})")
    }

    private suspend fun handleHostFetch(reqJson: String): String {
        return try {
            val root = json.parseToJsonElement(reqJson).jsonObject
            val url = root["url"]?.jsonPrimitive?.contentOrNull ?: error("Missing url")
            val methodStr = root["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
            val headersObj = root["headers"]?.jsonObject
            val bodyStr = root["body"]?.jsonPrimitive?.contentOrNull

            val response = httpClient.request(url) {
                this.method = HttpMethod.parse(methodStr.uppercase())
                headersObj?.forEach { (k, v) ->
                    val value = v.jsonPrimitive.contentOrNull
                    if (value != null) headers.append(k, value)
                }
                if (bodyStr != null) {
                    setBody(bodyStr)
                }
            }

            val status = response.status.value
            val statusText = response.status.description
            val resBody = response.bodyAsText()
            val resHeaders = buildJsonObject {
                response.headers.toMap().forEach { (k, list) ->
                    put(k, list.joinToString(", "))
                }
            }

            buildJsonObject {
                put("status", status)
                put("statusText", statusText)
                put("body", resBody)
                put("headers", resHeaders)
            }.toString()
        } catch (e: Throwable) {
            buildJsonObject {
                put("status", 599)
                put("statusText", "Host Network Error: ${e.message}")
                put("body", "")
                put("headers", buildJsonObject {})
            }.toString()
        }
    }

    private fun handleHostCrypto(reqJson: String): String {
        return try {
            val root = json.parseToJsonElement(reqJson).jsonObject
            val action = root["action"]?.jsonPrimitive?.contentOrNull ?: ""
            val text = root["text"]?.jsonPrimitive?.contentOrNull ?: ""

            val out = when (action) {
                "md5" -> md5(text)
                "sha1" -> sha1(text)
                "sha256" -> sha256(text)
                "base64Encode" -> Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                "base64Decode" -> String(Base64.getDecoder().decode(text), Charsets.UTF_8)
                "aesEncrypt" -> {
                    val key = root["key"]?.jsonPrimitive?.contentOrNull ?: ""
                    val iv = root["iv"]?.jsonPrimitive?.contentOrNull
                    val mode = root["mode"]?.jsonPrimitive?.contentOrNull ?: "CBC"
                    val padding = root["padding"]?.jsonPrimitive?.contentOrNull ?: "PKCS7Padding"
                    aesEncrypt(text, key, iv, mode, padding)
                }
                "aesDecrypt" -> {
                    val key = root["key"]?.jsonPrimitive?.contentOrNull ?: ""
                    val iv = root["iv"]?.jsonPrimitive?.contentOrNull
                    val mode = root["mode"]?.jsonPrimitive?.contentOrNull ?: "CBC"
                    val padding = root["padding"]?.jsonPrimitive?.contentOrNull ?: "PKCS7Padding"
                    aesDecrypt(text, key, iv, mode, padding)
                }
                "rsaEncrypt" -> {
                    val publicKey = root["publicKey"]?.jsonPrimitive?.contentOrNull ?: ""
                    rsaEncrypt(text, publicKey)
                }
                else -> error("Unsupported crypto action: $action")
            }
            buildJsonObject {
                put("value", out)
            }.toString()
        } catch (e: Throwable) {
            buildJsonObject {
                put("error", e.message ?: "Crypto failed")
            }.toString()
        }
    }

    private fun handleHostStorage(reqJson: String): String {
        return try {
            val root = json.parseToJsonElement(reqJson).jsonObject
            val op = root["op"]?.jsonPrimitive?.contentOrNull ?: ""
            val key = root["key"]?.jsonPrimitive?.contentOrNull ?: ""

            when (op) {
                "get" -> {
                    val v = jsStorage.getString(key)
                    buildJsonObject { put("value", v) }.toString()
                }
                "set" -> {
                    val v = root["value"]?.jsonPrimitive?.contentOrNull ?: ""
                    jsStorage.putString(key, v)
                    buildJsonObject { put("success", true) }.toString()
                }
                "remove" -> {
                    jsStorage.remove(key)
                    buildJsonObject { put("success", true) }.toString()
                }
                "clear" -> {
                    jsStorage.clear()
                    buildJsonObject { put("success", true) }.toString()
                }
                else -> buildJsonObject { put("error", "Unknown op: $op") }.toString()
            }
        } catch (e: Throwable) {
            buildJsonObject { put("error", e.message ?: "Storage op failed") }.toString()
        }
    }

    private fun buildBootstrapJs(configJson: String): String {
        return """
            (function() {
              if (typeof globalThis.console === 'undefined') {
                globalThis.console = {
                  log: function() { __host_log("INFO", Array.prototype.slice.call(arguments).map(String).join(" ")); },
                  info: function() { __host_log("INFO", Array.prototype.slice.call(arguments).map(String).join(" ")); },
                  warn: function() { __host_log("WARN", Array.prototype.slice.call(arguments).map(String).join(" ")); },
                  error: function() { __host_log("ERROR", Array.prototype.slice.call(arguments).map(String).join(" ")); }
                };
              }

              globalThis.fetch = async function(url, options) {
                options = options || {};
                var method = (options.method || "GET").toUpperCase();
                var headers = options.headers || {};
                var body = options.body;
                if (typeof body === 'object' && body !== null) {
                  body = JSON.stringify(body);
                } else if (body !== undefined && body !== null) {
                  body = String(body);
                } else {
                  body = null;
                }

                var reqPayload = JSON.stringify({
                  url: String(url),
                  method: method,
                  headers: headers,
                  body: body
                });

                var rawRes = await __host_fetch(reqPayload);
                var resObj = JSON.parse(rawRes || "{}");
                return {
                  ok: resObj.status >= 200 && resObj.status < 300,
                  status: resObj.status,
                  statusText: resObj.statusText || "",
                  headers: resObj.headers || {},
                  async text() {
                    return resObj.body || "";
                  },
                  async json() {
                    return JSON.parse(resObj.body || "{}");
                  }
                };
              };

              function callCrypto(action, params) {
                params = params || {};
                params.action = action;
                var res = JSON.parse(__host_crypto(JSON.stringify(params)));
                if (res.error) throw new Error(res.error);
                return res.value;
              }

              globalThis.crypto = {
                md5: function(text) { return callCrypto("md5", { text: String(text || "") }); },
                sha1: function(text) { return callCrypto("sha1", { text: String(text || "") }); },
                sha256: function(text) { return callCrypto("sha256", { text: String(text || "") }); },
                base64Encode: function(text) { return callCrypto("base64Encode", { text: String(text || "") }); },
                base64Decode: function(base64) { return callCrypto("base64Decode", { text: String(base64 || "") }); },
                aesEncrypt: function(text, key, iv, options) {
                  options = options || {};
                  return callCrypto("aesEncrypt", {
                    text: String(text || ""),
                    key: String(key || ""),
                    iv: iv ? String(iv) : null,
                    mode: options.mode || "CBC",
                    padding: options.padding || "PKCS7Padding"
                  });
                },
                aesDecrypt: function(text, key, iv, options) {
                  options = options || {};
                  return callCrypto("aesDecrypt", {
                    text: String(text || ""),
                    key: String(key || ""),
                    iv: iv ? String(iv) : null,
                    mode: options.mode || "CBC",
                    padding: options.padding || "PKCS7Padding"
                  });
                },
                rsaEncrypt: function(text, publicKey) {
                  return callCrypto("rsaEncrypt", {
                    text: String(text || ""),
                    publicKey: String(publicKey || "")
                  });
                }
              };

              globalThis.storage = {
                getItem: function(key) {
                  var res = JSON.parse(__host_storage(JSON.stringify({ op: "get", key: String(key || "") })));
                  return res.value;
                },
                setItem: function(key, value) {
                  __host_storage(JSON.stringify({ op: "set", key: String(key || ""), value: String(value || "") }));
                },
                removeItem: function(key) {
                  __host_storage(JSON.stringify({ op: "remove", key: String(key || "") }));
                },
                clear: function() {
                  __host_storage(JSON.stringify({ op: "clear" }));
                }
              };

              globalThis.env = {
                id: ${encodeJsString(id)},
                name: ${encodeJsString(name)},
                version: ${encodeJsString(version)},
                settings: JSON.parse(${encodeJsString(configJson)})
              };

              globalThis.__cp_dispatch = async function(method, params) {
                var mod = globalThis.CPPlayer || globalThis.default || globalThis;

                // 1. 优先直接按方法名调用（支持直接定义 callApi）
                if (typeof mod.callApi === "function") {
                  var res = await mod.callApi(method, params);
                  return JSON.stringify(res === undefined ? null : res);
                }

                // 2. 映射 Standard v3 与常用别名
                var fn = mod[method];
                if (typeof fn !== "function") {
                  if (method === "cloudsearch" || method === "search") {
                    fn = mod.search || mod.searchMusic || mod.onSearch;
                  } else if (method === "song/url" || method === "song/url/v1" || method === "playback") {
                    fn = mod.getMediaSource || mod.songUrl || mod.getPlaybackUrl;
                  } else if (method === "song/detail" || method === "detail") {
                    fn = mod.getSongDetail || mod.songDetail;
                  } else if (method === "lyric" || method === "lyric/new") {
                    fn = mod.getLyric || mod.lyric;
                  } else if (method === "playlist/detail") {
                    fn = mod.getPlaylistDetail || mod.playlistDetail;
                  }
                }

                if (typeof fn !== "function") {
                  return JSON.stringify({
                    code: 404,
                    message: "Method [" + method + "] not implemented in JS provider"
                  });
                }

                var outcome = await fn(params);
                return JSON.stringify(outcome === undefined ? null : outcome);
              };
            })();
        """.trimIndent()
    }

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun normalizePadding(padding: String): String = when (padding.uppercase()) {
        "PKCS7", "PKCS7PADDING" -> "PKCS5Padding"
        "PKCS5", "PKCS5PADDING" -> "PKCS5Padding"
        "NOPADDING" -> "NoPadding"
        else -> padding
    }

    private fun aesEncrypt(text: String, keyStr: String, ivStr: String?, mode: String, padding: String): String {
        val normPadding = normalizePadding(padding)
        val cipher = Cipher.getInstance("AES/$mode/$normPadding")
        val key = SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
        if (ivStr != null && mode.equals("CBC", ignoreCase = true)) {
            val iv = IvParameterSpec(ivStr.toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        }
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    private fun aesDecrypt(cipherTextBase64: String, keyStr: String, ivStr: String?, mode: String, padding: String): String {
        val normPadding = normalizePadding(padding)
        val cipher = Cipher.getInstance("AES/$mode/$normPadding")
        val key = SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
        if (ivStr != null && mode.equals("CBC", ignoreCase = true)) {
            val iv = IvParameterSpec(ivStr.toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
        } else {
            cipher.init(Cipher.DECRYPT_MODE, key)
        }
        val decoded = Base64.getDecoder().decode(cipherTextBase64)
        val decrypted = cipher.doFinal(decoded)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun rsaEncrypt(text: String, publicKeyPem: String): String {
        val cleanKey = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(cleanKey)
        val spec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val key = keyFactory.generatePublic(spec)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    private fun encodeJsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
