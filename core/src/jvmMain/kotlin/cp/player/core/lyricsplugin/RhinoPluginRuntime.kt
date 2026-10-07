/*
 * Based on the Lyrico Plugin API host contract (https://github.com/Replica0110/Lyrico) — Apache-2.0.
 * Host contract reference: app/src/main/java/com/ella/music/plugin/runtime/PluginJsRuntime.kt
 * Changes: the JS engine was swapped from Android-only quickjs-wrapper to Mozilla Rhino so the
 *          same host contract runs on both Android and Desktop JVM. The Lyrico host bootstrap and
 *          __invoke dispatcher are reproduced verbatim from upstream so existing plugins run
 *          unchanged. See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricsplugin

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

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
 * 用 Rhino 运行一个 Lyrico 格式插件的 JS 运行时。
 *
 * 契约（与上游逐字一致）：
 * - 宿主暴露全局函数 `__lyricoHostCall(name, payloadJson) -> String`
 * - 脚本加载前先注入 `Platform` / `app` / `runtime` 引导层
 * - 宿主通过全局 `__invoke(fnName, requestJson) -> String` 调插件入口
 *
 * ⚠️ **非线程安全**：一个实例必须在同一线程创建与使用（调用方在专属单线程调度器上跑）。
 */
class RhinoPluginRuntime(private val hostApi: PluginHostApi) : AutoCloseable {

    private var scope: Scriptable? = null
    private var closed = false

    /** 求值插件脚本（含 include 合并后的完整源码）。 */
    fun eval(script: String, filename: String) = withContext { cx, scope ->
        hostApi.beginInvocation()
        cx.evaluateString(scope, script, filename, 1, null)
        Unit
    }

    /** 调用插件全局入口函数，返回其 JSON 字符串化结果。 */
    fun call(functionName: String, requestJson: String): String = withContext { cx, scope ->
        hostApi.beginInvocation()
        val fn = ScriptableObject.getProperty(scope, "__invoke")
        if (fn !is Function) return@withContext "null"
        val result = fn.call(cx, scope, scope, arrayOf<Any>(functionName, requestJson))
        when (result) {
            null, Context.getUndefinedValue() -> "null"
            else -> Context.toString(result)
        }
    }

    override fun close() {
        closed = true
        scope = null
    }

    private fun <T> withContext(block: (Context, Scriptable) -> T): T {
        check(!closed) { "RhinoPluginRuntime already closed" }
        val cx = Context.enter()
        try {
            // -1 = 解释执行，不生成字节码：在 Android 上避免动态类加载，桌面端也够快。
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val activeScope = scope ?: createScope(cx).also { scope = it }
            return block(cx, activeScope)
        } finally {
            Context.exit()
        }
    }

    private fun createScope(cx: Context): Scriptable {
        val scope = cx.initStandardObjects()
        ScriptableObject.putProperty(scope, "__lyricoHostCall", HostCallFunction(hostApi))
        cx.evaluateString(scope, GLOBAL_THIS_SHIM, "<host-shim>", 1, null)
        cx.evaluateString(scope, HOST_API_BOOTSTRAP, "<host-bootstrap>", 1, null)
        cx.evaluateString(scope, INVOKE_DISPATCHER, "<host-dispatcher>", 1, null)
        return scope
    }

    /** Rhino 在个别配置下不定义 `globalThis`；补一个指向全局对象的别名。 */
    private class HostCallFunction(private val hostApi: PluginHostApi) : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any {
            val name = args.getOrNull(0)?.let { Context.toString(it) } ?: ""
            val payload = args.getOrNull(1)?.let { Context.toString(it) } ?: "{}"
            return hostApi.call(name, payload)
        }

        override fun getFunctionName(): String = "__lyricoHostCall"
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
              globalThis.__invoke = function(name, requestJson) {
                var fn = globalThis[name];
                if (typeof fn !== "function") return "null";
                var result = fn(JSON.parse(requestJson || "{}"));
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
    }
}
