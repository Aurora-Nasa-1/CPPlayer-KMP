/*
 * Based on the Lyrico Plugin API host contract (https://github.com/Replica0110/Lyrico) — Apache-2.0.
 * Host contract reference: app/src/main/java/com/ella/music/plugin/runtime/QuickJsHostApi.kt
 * Changes: package renamed; OkHttp replaced by java.net.HttpURLConnection (no extra dependency,
 *          works on both Android and Desktop JVM); Android Base64/Log replaced by java.util.Base64
 *          and a pluggable log sink; AES + XML host calls omitted (see HostApiRegistry).
 *          See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricsplugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Inflater

/**
 * `Platform.*` 宿主调用的纯 Kotlin 实现。
 *
 * 与 JS 的约定：JS 侧 `hostCall(name, payload)` 执行
 * `JSON.parse(__lyricoHostCall(name, JSON.stringify(payload))).value`，
 * 因此 [call] 必须返回一个形如 `{"value": <结果>}` 的 JSON 字符串。
 *
 * 引擎无关（Rhino / QuickJS 都能用）。移植自 Lyrico，插件无需改动。
 */
class JvmPluginHostApi(
    private val pluginId: String = "default",
    private val appName: String = "CPPlayer",
    private val appVersion: String = "1.0.0",
    private val cacheRootDir: File? = null,
    private val logSink: (level: String, tag: String, message: String) -> Unit = { _, _, _ -> },
) : PluginHostApi {

    private val json = Json { ignoreUnknownKeys = true }
    private val memoryCache = LinkedHashMap<String, CacheEntry>()

    /** 一次调用内的取消标记（预留：超时后拒绝继续发起 HTTP）。 */
    @Volatile
    private var invocationCancelled = false

    override fun beginInvocation() {
        invocationCancelled = false
    }

    override fun call(name: String, payloadJson: String): String {
        val payload = runCatching { json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull()
        val value: JsonElement = when (name) {
            "app.info" -> buildJsonObject {
                put("name", appName)
                put("version", appVersion)
            }
            "app.userAgent" -> JsonPrimitive("$appName/$appVersion")
            "runtime.info" -> buildJsonObject {
                put("host", "CPPlayer")
                put("hostApiVersion", HostApiRegistry.HOST_API_VERSION)
            }
            "i18n.getLocale" -> JsonPrimitive("zh-CN")
            "i18n.t" -> JsonPrimitive(payload?.get("key")?.jsonPrimitive?.contentOrNull ?: "")

            "cache.get" -> JsonPrimitive(cacheGet(payload?.str("key").orEmpty()))
            "cache.set" -> {
                cacheSet(payload?.str("key").orEmpty(), payload?.str("value").orEmpty())
                JsonNull
            }
            "cache.remove" -> {
                cacheRemove(payload?.str("key").orEmpty())
                JsonNull
            }
            "cache.clear" -> {
                cacheClear()
                JsonNull
            }

            "crypto.md5" -> JsonPrimitive(hashHex("MD5", payload?.str("text").orEmpty()))
            "crypto.sha256" -> JsonPrimitive(hashHex("SHA-256", payload?.str("text").orEmpty()))

            "base64.encodeText" -> JsonPrimitive(
                Base64.getEncoder().encodeToString(payload?.str("text").orEmpty().toByteArray(Charsets.UTF_8)),
            )
            "base64.decodeText" -> JsonPrimitive(
                runCatching { String(Base64.getDecoder().decode(payload?.str("base64").orEmpty()), Charsets.UTF_8) }
                    .getOrDefault(""),
            )
            "base64.decodeBytes" -> intArrayJson(
                runCatching { Base64.getDecoder().decode(payload?.str("base64").orEmpty()) }.getOrDefault(ByteArray(0)),
            )
            "base64.encodeBytes" -> JsonPrimitive(
                Base64.getEncoder().encodeToString(payload?.intArray("bytes") ?: ByteArray(0)),
            )
            "base64.encodeUrlText" -> JsonPrimitive(
                Base64.getUrlEncoder().encodeToString(payload?.str("text").orEmpty().toByteArray(Charsets.UTF_8)),
            )
            "base64.decodeUrlText" -> JsonPrimitive(
                runCatching { String(Base64.getUrlDecoder().decode(payload?.str("base64Url").orEmpty()), Charsets.UTF_8) }
                    .getOrDefault(""),
            )
            "base64.encodeUrlBytes" -> JsonPrimitive(
                Base64.getUrlEncoder().encodeToString(payload?.intArray("bytes") ?: ByteArray(0)),
            )
            "base64.decodeUrlBytes" -> intArrayJson(
                runCatching { Base64.getUrlDecoder().decode(payload?.str("base64Url").orEmpty()) }.getOrDefault(ByteArray(0)),
            )

            "bytes.xor" -> {
                val data = payload?.intArray("bytes") ?: ByteArray(0)
                val key = payload?.intArray("key") ?: ByteArray(0)
                intArrayJson(xor(data, key))
            }
            "bytes.xorBase64" -> {
                val data = runCatching { Base64.getDecoder().decode(payload?.str("base64").orEmpty()) }
                    .getOrDefault(ByteArray(0))
                val key = payload?.intArray("key") ?: ByteArray(0)
                intArrayJson(xor(data, key))
            }

            "compression.inflateBytesToText" -> JsonPrimitive(
                inflateText(payload?.intArray("bytes") ?: ByteArray(0)),
            )
            "compression.inflateBase64ToText" -> JsonPrimitive(
                inflateText(
                    runCatching { Base64.getDecoder().decode(payload?.str("base64").orEmpty()) }
                        .getOrDefault(ByteArray(0)),
                ),
            )

            "http.getText" -> JsonPrimitive(httpRequest(payload, "GET")?.text.orEmpty())
            "http.postText" -> JsonPrimitive(httpRequest(payload, "POST")?.text.orEmpty())
            "http.get" -> parseOrNull(httpRequest(payload, "GET")?.text)
            "http.post" -> parseOrNull(httpRequest(payload, "POST")?.text)
            "http.getBytes" -> intArrayJson(
                httpRequest(payload, "GET")?.bytes ?: ByteArray(0),
            )

            "log.debug" -> {
                logSink("debug", payload?.str("tag").orEmpty(), payload?.str("message").orEmpty())
                JsonNull
            }
            "log.warn" -> {
                logSink("warn", payload?.str("tag").orEmpty(), payload?.str("message").orEmpty())
                JsonNull
            }
            "log.error" -> {
                logSink("error", payload?.str("tag").orEmpty(), payload?.str("message").orEmpty())
                JsonNull
            }

            else -> {
                logSink("warn", "LyricoPlugin", "Unsupported host call: $name")
                JsonNull
            }
        }
        return buildJsonObject { put("value", value) }.toString()
    }

    // ============ HTTP ============

    private class HttpResult(val text: String, val bytes: ByteArray)

    private fun httpRequest(payload: JsonObject?, method: String, redirectCount: Int = 0): HttpResult? {
        val url = payload?.str("url").orEmpty()
        if (url.isBlank()) return null
        // ⚠️ 只允许 http/https，避免插件用 file:// 读取本地文件。
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        if (redirectCount > 3) return null
        return runCatching {
            val connection = (java.net.URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = payload?.int("connectTimeoutMs") ?: DEFAULT_CONNECT_TIMEOUT_MS
                readTimeout = payload?.int("readTimeoutMs") ?: DEFAULT_READ_TIMEOUT_MS
                instanceFollowRedirects = payload?.bool("followRedirects") ?: true
                
                // 默认请求头：避免被各大音乐服务反爬（默认 Java/xx 会直接 403）
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty("Accept-Encoding", "gzip, deflate")

                payload?.obj("headers")?.forEach { (key, value) ->
                    setRequestProperty(key, value.jsonPrimitive.contentOrNull.orEmpty())
                }
                if (method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", payload?.str("contentType") ?: "application/json; charset=utf-8")
                }
            }
            if (method == "POST") {
                val body = payload?.str("body").orEmpty().toByteArray(Charsets.UTF_8)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            // 处理跨协议重定向（HttpURLConnection 不会自动在 http 和 https 之间跳转）
            if (code in listOf(301, 302, 303, 307, 308) && (payload?.bool("followRedirects") ?: true)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (!location.isNullOrBlank()) {
                    val nextUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        java.net.URI(url).resolve(location).toString()
                    }
                    val nextPayload = buildJsonObject {
                        payload?.forEach { (k, v) -> put(k, v) }
                        put("url", nextUrl)
                    }
                    return httpRequest(nextPayload, if (code == 303) "GET" else method, redirectCount + 1)
                }
            }
            val encoding = connection.contentEncoding?.lowercase()
            val rawStream = if (code in 200..299) connection.inputStream else connection.errorStream
            val stream = when {
                rawStream == null -> null
                encoding == "gzip" -> java.util.zip.GZIPInputStream(rawStream)
                encoding == "deflate" -> java.util.zip.InflaterInputStream(rawStream)
                else -> rawStream
            }
            val bytes = stream?.use { input ->
                val buffer = ByteArrayOutputStream()
                input.copyTo(buffer)
                buffer.toByteArray()
            } ?: ByteArray(0)
            connection.disconnect()
            HttpResult(String(bytes, Charsets.UTF_8), bytes)
        }.getOrNull()
    }

    private fun parseOrNull(text: String?): JsonElement {
        if (text.isNullOrBlank()) return JsonNull
        return runCatching { json.parseToJsonElement(text) }.getOrDefault(JsonNull)
    }

    // ============ 缓存 ============

    private class CacheEntry(val value: String, val expiresAt: Long)

    private fun cacheGet(key: String): String {
        if (key.isBlank()) return ""
        synchronized(memoryCache) {
            memoryCache[key]?.let { entry ->
                if (entry.expiresAt == 0L || entry.expiresAt > System.currentTimeMillis()) return entry.value
                memoryCache.remove(key)
            }
        }
        val disk = cacheRootDir ?: return ""
        val file = File(disk, key.safeCacheName())
        return if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
    }

    private fun cacheSet(key: String, value: String) {
        if (key.isBlank()) return
        synchronized(memoryCache) { memoryCache[key] = CacheEntry(value, 0L) }
        cacheRootDir?.let { dir ->
            runCatching {
                dir.mkdirs()
                File(dir, key.safeCacheName()).writeText(value)
            }
        }
    }

    private fun cacheRemove(key: String) {
        synchronized(memoryCache) { memoryCache.remove(key) }
        cacheRootDir?.let { dir -> runCatching { File(dir, key.safeCacheName()).delete() } }
    }

    private fun cacheClear() {
        synchronized(memoryCache) { memoryCache.clear() }
        cacheRootDir?.let { dir -> runCatching { dir.listFiles()?.forEach(File::delete) } }
    }

    private fun String.safeCacheName(): String =
        "$pluginId-${hashHex("SHA-256", this).take(32)}"

    // ============ 工具 ============

    private fun hashHex(algorithm: String, text: String): String =
        runCatching {
            MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }.getOrDefault("")

    private fun xor(data: ByteArray, key: ByteArray): ByteArray {
        if (key.isEmpty()) return data
        return ByteArray(data.size) { index -> (data[index].toInt() xor key[index % key.size].toInt()).toByte() }
    }

    private fun inflateText(bytes: ByteArray): String =
        runCatching {
            val inflater = Inflater()
            inflater.setInput(bytes)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) break
                output.write(buffer, 0, count)
            }
            inflater.end()
            String(output.toByteArray(), Charsets.UTF_8)
        }.getOrDefault("")

    private fun intArrayJson(bytes: ByteArray): JsonElement =
        buildJsonArray { bytes.forEach { add(JsonPrimitive(it.toInt() and 0xFF)) } }
}

private const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000
private const val DEFAULT_READ_TIMEOUT_MS = 10_000
private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 CPPlayer/1.0"


// ============ JSON 读取助手 ============

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.intArray(key: String): ByteArray? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.toByte() }?.toByteArray()
