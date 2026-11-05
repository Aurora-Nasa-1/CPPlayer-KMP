/*
 * Based on the Lyrico Plugin API host contract (https://github.com/Replica0110/Lyrico) — Apache-2.0.
 * Host contract reference: app/src/main/java/com/ella/music/plugin/runtime/PluginJsRuntime.kt
 * Changes: the JS engine uses QuickJS (via dokar3:quickjs-kt) across Android and Desktop JVM,
 *          providing native ES2023 performance, async/await support, and thread safety via
 *          isolated engine instances. See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricsplugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * 宿主 → JS 的桥。插件通过 `__lyricoHostCall(name, payloadJson)` 调用宿主的 `Platform.*`。
 * 与 Lyrico Plugin API 的契约一致。
 */
interface PluginHostApi {
    fun call(name: String, payloadJson: String): String

    /**
     * 每次进入插件调用前的钩子（用于按「一次调用」重置请求配额 / 取消标记）。
     *
     * 带默认实现：`core` 的接口新增成员必须给默认实现，否则所有实现类一起编译失败。
     */
    fun beginInvocation() {}
}

/**
 * 运行一个 Lyrico 格式插件的 JS 运行时（基于 QuickJS 原生引擎）。
 *
 * 契约（与上游逐字一致）：
 * - 宿主暴露全局函数 `__lyricoHostCall(name, payloadJson) -> String`
 * - 脚本加载前先注入 `Platform` / `app` / `runtime` 引导层
 * - 宿主通过全局 `__invoke(fnName, requestJson) -> String` 调插件入口
 *
 * 保留类名 [RhinoPluginRuntime] 以向前兼容既有调用方。
 */
typealias QuickJsPluginRuntime = RhinoPluginRuntime

class RhinoPluginRuntime(private val hostApi: PluginHostApi) : AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CPPlayer-LyricsPluginRuntime").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var quickJs: QuickJs? = null

    @Volatile
    private var closed = false

    init {
        runBlocking(dispatcher) {
            val qjs = QuickJs.create(dispatcher)
            quickJs = qjs
            qjs.function<String>("__lyricoHostCall") { args ->
                val name = args.getOrNull(0)?.toString() ?: ""
                val payload = args.getOrNull(1)?.toString() ?: "{}"
                hostApi.call(name, payload)
            }
            qjs.evaluate<Any?>(GLOBAL_THIS_SHIM)
            qjs.evaluate<Any?>(HOST_API_BOOTSTRAP)
            qjs.evaluate<Any?>(INVOKE_DISPATCHER)
        }
    }

    /** 求值插件脚本（含 include 合并后的完整源码）。 */
    fun eval(script: String, filename: String) {
        check(!closed) { "RhinoPluginRuntime already closed" }
        runBlocking(dispatcher) {
            hostApi.beginInvocation()
            quickJs?.evaluate<Any?>(script, filename = filename)
        }
    }

    /** 调用插件全局入口函数，返回其 JSON 字符串化结果。 */
    fun call(functionName: String, requestJson: String): String {
        check(!closed) { "RhinoPluginRuntime already closed" }
        return runBlocking(dispatcher) {
            hostApi.beginInvocation()
            val callScript = "await globalThis.__invoke(${encodeJsString(functionName)}, ${encodeJsString(requestJson)});"
            val result = quickJs?.evaluate<String>(callScript)
            result ?: "null"
        }
    }

    override fun close() {
        if (closed) return
        closed = true
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

    private companion object {
        const val GLOBAL_THIS_SHIM = """
            (function() {
              if (typeof globalThis === 'undefined') {
                var g = (function(){ return this; })();
                if (g && typeof g.globalThis === 'undefined') { g.globalThis = g; }
              }
            })();
        """

        private const val INVOKE_DISPATCHER = """
            (function() {
              globalThis.__invoke = async function(name, requestJson) {
                var fn = globalThis[name];
                if (typeof fn !== "function") return "null";
                var result = await fn(JSON.parse(requestJson || "{}"));
                return JSON.stringify(result === undefined ? null : result);
              };
            })();
        """

        // 逐字来自 Lyrico 插件宿主，保证插件看到的 Platform 面完全一致。
        private val HOST_API_BOOTSTRAP = """
            (function() {
              function hostCall(name, payload) {
                return JSON.parse(__lyricoHostCall(name, JSON.stringify(payload || {}))).value;
              }

              function normalizeOptions(options) {
                options = options || {};
                return {
                  headers: options.headers || {},
                  contentType: options.contentType,
                  connectTimeoutMs: options.connectTimeoutMs,
                  readTimeoutMs: options.readTimeoutMs,
                  followRedirects: options.followRedirects
                };
              }

              globalThis.app = {
                getInfo: function() { return hostCall("app.info", {}); },
                getUserAgent: function() { return hostCall("app.userAgent", {}); }
              };

              globalThis.runtime = {
                getInfo: function() { return hostCall("runtime.info", {}); }
              };

              globalThis.Platform = {
                i18n: {
                  getLocale: function() { return hostCall("i18n.getLocale", {}); },
                  t: function(key) {
                    return hostCall("i18n.t", { key: String(key), args: Array.prototype.slice.call(arguments, 1) });
                  }
                },
                app: globalThis.app,
                runtime: globalThis.runtime,

                cache: {
                  get: function(key) { return hostCall("cache.get", { key: String(key || "") }); },
                  set: function(key, value, ttlMs) {
                    return hostCall("cache.set", {
                      key: String(key || ""),
                      value: value == null ? "" : String(value),
                      ttlMs: Number(ttlMs || 0)
                    });
                  },
                  remove: function(key) { return hostCall("cache.remove", { key: String(key || "") }); },
                  clear: function() { return hostCall("cache.clear", {}); }
                },

                crypto: {
                  md5: function(text) { return hostCall("crypto.md5", { text: String(text || "") }); },
                  sha256: function(text) { return hostCall("crypto.sha256", { text: String(text || "") }); }
                },

                base64: {
                  encodeText: function(text) { return hostCall("base64.encodeText", { text: String(text || "") }); },
                  decodeText: function(base64) { return hostCall("base64.decodeText", { base64: String(base64 || "") }); },
                  decodeBytes: function(base64) { return hostCall("base64.decodeBytes", { base64: String(base64 || "") }); },
                  encodeBytes: function(bytes) { return hostCall("base64.encodeBytes", { bytes: Array.from(bytes || []) }); },
                  encodeUrlText: function(text) { return hostCall("base64.encodeUrlText", { text: String(text || "") }); },
                  decodeUrlText: function(base64Url) { return hostCall("base64.decodeUrlText", { base64Url: String(base64Url || "") }); },
                  encodeUrlBytes: function(bytes) { return hostCall("base64.encodeUrlBytes", { bytes: Array.from(bytes || []) }); },
                  decodeUrlBytes: function(base64Url) { return hostCall("base64.decodeUrlBytes", { base64Url: String(base64Url || "") }); }
                },

                bytes: {
                  xor: function(bytes, key) {
                    return hostCall("bytes.xor", { bytes: Array.from(bytes || []), key: Array.from(key || []) });
                  },
                  xorBase64: function(base64, key) {
                    return hostCall("bytes.xorBase64", { base64: String(base64 || ""), key: Array.from(key || []) });
                  }
                },

                compression: {
                  inflateBytesToText: function(bytes) {
                    return hostCall("compression.inflateBytesToText", { bytes: Array.from(bytes || []) });
                  },
                  inflateBase64ToText: function(base64) {
                    return hostCall("compression.inflateBase64ToText", { base64: String(base64 || "") });
                  }
                },

                http: {
                  getText: function(url, options) {
                    options = normalizeOptions(options);
                    return hostCall("http.getText", {
                      url: String(url || ""),
                      headers: options.headers || {},
                      connectTimeoutMs: options.connectTimeoutMs,
                      readTimeoutMs: options.readTimeoutMs,
                      followRedirects: options.followRedirects
                    });
                  },
                  postText: function(url, body, options) {
                    options = normalizeOptions(options);
                    return hostCall("http.postText", {
                      url: String(url || ""),
                      body: body == null ? "" : String(body),
                      contentType: options.contentType || "application/json; charset=utf-8",
                      headers: options.headers || {},
                      connectTimeoutMs: options.connectTimeoutMs,
                      readTimeoutMs: options.readTimeoutMs,
                      followRedirects: options.followRedirects
                    });
                  },
                  get: function(url, options) {
                    options = normalizeOptions(options);
                    return hostCall("http.get", {
                      url: String(url || ""),
                      headers: options.headers || {},
                      connectTimeoutMs: options.connectTimeoutMs,
                      readTimeoutMs: options.readTimeoutMs,
                      followRedirects: options.followRedirects
                    });
                  },
                  post: function(url, body, options) {
                    options = normalizeOptions(options);
                    return hostCall("http.post", {
                      url: String(url || ""),
                      body: body == null ? "" : String(body),
                      contentType: options.contentType || "application/json; charset=utf-8",
                      headers: options.headers || {},
                      connectTimeoutMs: options.connectTimeoutMs,
                      readTimeoutMs: options.readTimeoutMs,
                      followRedirects: options.followRedirects
                    });
                  },
                  getBytes: function(url, options) {
                    options = normalizeOptions(options);
                    return hostCall("http.getBytes", {
                      url: String(url || ""),
                      headers: options.headers || {},
                      connectTimeoutMs: options.connectTimeoutMs,
                      readTimeoutMs: options.readTimeoutMs,
                      followRedirects: options.followRedirects
                    });
                  }
                },

                log: {
                  debug: function(tag, message) {
                    if (message === undefined) { message = tag; tag = "PlatformPlugin"; }
                    return hostCall("log.debug", { tag: String(tag || "PlatformPlugin"), message: String(message || "") });
                  },
                  warn: function(tag, message) {
                    if (message === undefined) { message = tag; tag = "PlatformPlugin"; }
                    return hostCall("log.warn", { tag: String(tag || "PlatformPlugin"), message: String(message || "") });
                  },
                  error: function(tag, message) {
                    if (message === undefined) { message = tag; tag = "PlatformPlugin"; }
                    return hostCall("log.error", { tag: String(tag || "PlatformPlugin"), message: String(message || "") });
                  }
                }
              };
            })();
        """

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
}
