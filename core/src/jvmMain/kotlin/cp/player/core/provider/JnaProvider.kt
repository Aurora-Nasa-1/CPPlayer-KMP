package cp.player.core.provider

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import cp.player.core.util.PlatformContext
import cp.player.core.util.createHttpClient
import cp.player.core.util.defaultSettingsStorage
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * JNA 调用的 C ABI 接口。
 * 音源动态库（.so / .dll / .dylib）按标准 C 调用约定（extern "C"）导出以下函数：
 * - [call_api] (核心)：处理 API 请求，输入方法名与 JSON 参数，返回指向以 null 结尾的 UTF-8 字符串的指针。
 * - [free_string] (推荐)：释放 [call_api] 分配的字符串内存，避免内存泄漏。
 * - [start_server] (可选)：若 Native 内部自起 HTTP 服务，通过此函数启动本地服务。
 */
interface ProviderNativeCInterface : Library {
    fun start_server(host: String, port: Int): Int
    fun call_api(method: String, paramsJson: String): Pointer?
    fun free_string(ptr: Pointer)
}

open class JnaProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    protected val soPath: String,
    override val apiMap: Map<String, String>? = null,
    override val updateUrl: String? = null,
    override val targetAppPackage: String? = null,
    override val loginMethods: List<String>? = null,
    override val capabilities: List<String>? = null,
    override val apiVersion: Int = 1,
    override val apiVersionTag: String? = null,
    override val contract: String? = null,
    override val settingsSchema: List<ProviderSettingItemSchema>? = null,
) : BackendProvider {

    override val type: ProviderType = ProviderType.JNA
    private var nativeLib: ProviderNativeCInterface? = null
    private var isLoaded = false
    override var loadError: String? = null
        protected set
    private var serverStarted = false
    private var serverPort = 3000
    private var httpClient: HttpClient? = null
    private val configStorage by lazy { defaultSettingsStorage("provider_config_$id") }

    init {
        val soFile = File(soPath)
        if (!soFile.exists()) {
            loadError = "动态库文件不存在: $soPath"
            log(loadError!!)
        }
    }

    override fun isReady(): Boolean = loadError == null

    open override fun startServer(context: PlatformContext, port: Int) {
        log("startServer 开始: soPath=$soPath, port=$port")
        loadNativeLibrary()
        if (!isLoaded || nativeLib == null) {
            log("startServer 失败: JNA 库加载失败: $loadError")
            throw IllegalStateException("JNA 库加载失败: $loadError")
        }
        this.serverPort = port
        try {
            val code = nativeLib?.start_server("127.0.0.1", port)
            serverStarted = true
            log("JNA 服务启动成功: $soPath, port=$port, code=$code")
        } catch (e: UnsatisfiedLinkError) {
            log("start_server 未导出（可选）: ${e.message}，保持直接 JNA 内存调用")
        } catch (e: NoSuchMethodError) {
            log("start_server 未定义（可选）: ${e.message}，保持直接 JNA 内存调用")
        } catch (e: Throwable) {
            isLoaded = false
            loadError = "JNA 服务启动崩溃: ${e.message}"
            log("start_server 崩溃: ${e.message}")
            throw e
        }
    }

    open override fun stopServer() {
        isLoaded = false
        serverStarted = false
        nativeLib = null
    }

    private fun getOrCreateHttpClient(): HttpClient {
        return httpClient ?: createHttpClient().also { httpClient = it }
    }

    private fun callApiViaHttp(method: String, body: String, configJsonStr: String?): String {
        return try {
            runBlocking {
                val client = getOrCreateHttpClient()
                val resp = client.post("http://127.0.0.1:$serverPort/api/$method") {
                    contentType(ContentType.Application.Json)
                    if (!configJsonStr.isNullOrEmpty()) {
                        header("X-Provider-Config", configJsonStr)
                    }
                    setBody(body)
                }
                resp.bodyAsText()
            }
        } catch (e: Exception) {
            errorJson("JNA HTTP fallback failed: ${e.message}")
        }
    }

    open override fun callApi(method: String, params: Map<String, String>): String {
        if (!isLoaded) {
            log("callApi 被调用但 JNA 未加载: method=$method, loadError=$loadError")
            return errorJson("JNA not loaded: ${loadError ?: "unknown"}")
        }
        val configJson = settingsSchema?.let { schema ->
            buildJsonObject {
                schema.forEach { item ->
                    val value = configStorage.getString(item.key) ?: item.default
                    if (value != null) {
                        put(item.key, JsonPrimitive(value))
                    }
                }
            }
        }
        val json = buildJsonObject {
            settingsSchema?.forEach { item ->
                val value = configStorage.getString(item.key) ?: item.default
                if (value != null) put(item.key, JsonPrimitive(value))
            }
            params.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            if (configJson != null && configJson.isNotEmpty()) {
                put("config", configJson)
            }
        }.toString()
        log("callApi -> JNA call_api: method=$method, params={${maskParamsForLog(params)}}")
        return callApiJson(method, json)
    }

    internal fun callApiJson(method: String, paramsJson: String): String {
        if (!isLoaded) return errorJson("JNA not loaded: ${loadError ?: "unknown"}")
        val lib = nativeLib ?: return errorJson("JNA lib is null: ${loadError ?: "unknown"}")
        val body = runCatching { Json.parseToJsonElement(paramsJson) }.getOrNull() as? JsonObject
            ?: return errorJson("JNA request must be a JSON object")

        return try {
            val ptr = lib.call_api(method, paramsJson)
            if (ptr != null) {
                val result = ptr.getString(0, "UTF-8")
                try {
                    lib.free_string(ptr)
                } catch (_: Throwable) {
                    // 若未导出 free_string 则容错
                }
                log("callApi <- JNA call_api: method=$method, resultLen=${result.length}")
                result
            } else {
                errorJson("JNA call_api returned null")
            }
        } catch (e: UnsatisfiedLinkError) {
            if (serverStarted) {
                log("call_api 未导出但本地 HTTP 服务已就绪，降级走 HTTP: $method")
                callApiViaHttp(method, paramsJson, (body["config"] as? JsonObject)?.toString())
            } else {
                isLoaded = false
                loadError = "JNA 调用未导出: ${e.message}"
                log(loadError!!)
                errorJson(loadError!!)
            }
        } catch (e: NoSuchMethodError) {
            if (serverStarted) {
                log("call_api 未定义但本地 HTTP 服务已就绪，降级走 HTTP: $method")
                callApiViaHttp(method, paramsJson, (body["config"] as? JsonObject)?.toString())
            } else {
                isLoaded = false
                loadError = "JNA 方法未找到: ${e.message}"
                log(loadError!!)
                errorJson(loadError!!)
            }
        } catch (e: Throwable) {
            errorJson("JNA 调用异常: ${e.message}")
        }
    }

    private fun errorJson(msg: String): String {
        val escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"code":500,"msg":"$escaped"}"""
    }

    private fun loadNativeLibrary() {
        if (isLoaded && nativeLib != null) return
        val soFile = File(soPath)
        if (!soFile.exists()) {
            loadError = "动态库文件不存在: $soPath"
            log(loadError!!)
            return
        }
        try {
            log("加载 JNA 动态库: $soPath")
            nativeLib = Native.load(soFile.absolutePath, ProviderNativeCInterface::class.java)
            isLoaded = true
            loadError = null
            log("JNA 动态库加载成功: $soPath")
        } catch (e: UnsatisfiedLinkError) {
            loadError = "JNA 链接失败（缺依赖库或架构不匹配）: ${e.message}"
            log("加载 SO 失败: $soPath - ${e.message}")
        } catch (e: Throwable) {
            loadError = "JNA 加载异常: ${e.message}"
            log("加载 SO 异常: $soPath - ${e.message}")
        }
    }

    private fun log(msg: String) {
        println("[JnaProvider] $msg")
    }

    private fun maskParamsForLog(params: Map<String, String>): String =
        params.entries.joinToString(", ") { (k, v) ->
            if (k.lowercase() in SENSITIVE_PARAM_KEYS) "$k=***(${v.length})" else "$k=$v"
        }

    open override fun getDiagnostics(): Map<String, String> = buildMap {
        put("id", id)
        put("type", "JNA")
        put("soPath", soPath)
        put("isLoaded", isLoaded.toString())
        put("serverStarted", serverStarted.toString())
        put("serverPort", serverPort.toString())
        loadError?.let { put("loadError", it) }
    }

    open override fun close() {
        stopServer()
        httpClient?.close()
        httpClient = null
    }

    override fun analyzeAudio(path: String): String = """{"code": 500, "msg": "Not supported"}"""

    private companion object {
        val SENSITIVE_PARAM_KEYS = setOf(
            "cookie", "token", "password", "md5_password", "access_token", "refresh_token",
        )
    }
}
