package cp.player.core.scrobble

import cp.player.core.util.createHttpClient
import cp.player.core.util.md5Hex
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Last.fm 官方 Audioscrobbler API 2.0 客户端。
 *
 * 遵循官方签名规范与认证协议。
 */
class LastFmApi(
    private val client: HttpClient = createHttpClient(),
) {
    companion object {
        const val API_ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
        const val AUTH_URL_BASE = "https://www.last.fm/api/auth/"

        /**
         * CPPlayer 官方专属 Last.fm API Key 与 Shared Secret。
         */
        const val DEFAULT_API_KEY = "b96571e6e5e23593652bd4158ed0863a"
        const val DEFAULT_API_SECRET = "7a9c54679191cc998f5b474856830429"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    /**
     * 计算 Last.fm API 签名（`api_sig`）。
     *
     * 规则：
     * 1. 过滤掉 `format` 与 `callback` 参数；
     * 2. 所有参数按键名 ASCII 升序排列；
     * 3. 顺序拼接 key + value，并在末尾追加 secret；
     * 4. 计算该 UTF-8 字符串的 32 位小写 MD5。
     */
    fun generateSignature(params: Map<String, String>, secret: String): String {
        val sorted = params.entries
            .filter { it.key != "format" && it.key != "callback" }
            .sortedBy { it.key }
            .joinToString("") { "${it.key}${it.value}" } + secret
        return md5Hex(sorted)
    }

    /**
     * 第一步：获取临时授权 Request Token（读接口无需签名）。
     */
    suspend fun fetchRequestToken(apiKey: String, secret: String? = null): Result<String> = runCatching {
        val url = "$API_ENDPOINT?method=auth.getToken&api_key=$apiKey&format=json"
        val response = client.get(url).bodyAsText()
        val root = json.parseToJsonElement(response).jsonObject
        if (root.containsKey("error")) {
            val errCode = root["error"]?.jsonPrimitive?.intOrNull ?: -1
            val errMsg = root["message"]?.jsonPrimitive?.content ?: "Unknown error"
            throw IllegalStateException("Last.fm error ($errCode): $errMsg")
        }
        root["token"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("No token in Last.fm response: $response")
    }

    /**
     * 第二步：构造引导用户前往浏览器授权的 URL。
     */
    fun buildAuthWebUrl(apiKey: String, token: String): String {
        return "$AUTH_URL_BASE?api_key=$apiKey&token=$token"
    }

    /**
     * 第三步：用户在浏览器中授权完成后，用 token 换取持久的 Session Key 与用户名。
     */
    suspend fun fetchSession(apiKey: String, secret: String, token: String): Result<Pair<String, String>> = runCatching {
        val params = mutableMapOf(
            "method" to "auth.getSession",
            "api_key" to apiKey,
            "token" to token,
        )
        params["api_sig"] = generateSignature(params, secret)
        params["format"] = "json"

        val response = client.submitForm(
            url = API_ENDPOINT,
            formParameters = parameters {
                params.forEach { (k, v) -> append(k, v) }
            },
        ).bodyAsText()

        val root = json.parseToJsonElement(response).jsonObject
        if (root.containsKey("error")) {
            val errCode = root["error"]?.jsonPrimitive?.intOrNull ?: -1
            val errMsg = root["message"]?.jsonPrimitive?.content ?: "Unknown error"
            throw IllegalStateException("Last.fm error ($errCode): $errMsg")
        }
        val session = root["session"]?.jsonObject
            ?: throw IllegalStateException("No session object in response: $response")
        val sessionKey = session["key"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("No session key in response")
        val username = session["name"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("No username in response")

        sessionKey to username
    }

    /**
     * 正在播放（Now Playing）广播。
     */
    suspend fun updateNowPlaying(
        apiKey: String,
        secret: String,
        sessionKey: String,
        track: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): ScrobbleResult = runCatching {
        val params = mutableMapOf(
            "method" to "track.updateNowPlaying",
            "api_key" to apiKey,
            "sk" to sessionKey,
            "track" to track,
            "artist" to artist,
        )
        if (!album.isNullOrBlank()) params["album"] = album
        if (durationSec > 0) params["duration"] = durationSec.toString()
        params["api_sig"] = generateSignature(params, secret)
        params["format"] = "json"

        val response = client.submitForm(
            url = API_ENDPOINT,
            formParameters = parameters {
                params.forEach { (k, v) -> append(k, v) }
            },
        ).bodyAsText()

        parseResult(response)
    }.getOrElse {
        ScrobbleResult.failure(it.message)
    }

    /**
     * 提交已听打卡（Scrobble）。
     */
    suspend fun scrobble(
        apiKey: String,
        secret: String,
        sessionKey: String,
        track: String,
        artist: String,
        album: String?,
        timestampSec: Long,
        durationSec: Int,
    ): ScrobbleResult = runCatching {
        val params = mutableMapOf(
            "method" to "track.scrobble",
            "api_key" to apiKey,
            "sk" to sessionKey,
            "track" to track,
            "artist" to artist,
            "timestamp" to timestampSec.toString(),
        )
        if (!album.isNullOrBlank()) params["album"] = album
        if (durationSec > 0) params["duration"] = durationSec.toString()
        params["api_sig"] = generateSignature(params, secret)
        params["format"] = "json"

        val response = client.submitForm(
            url = API_ENDPOINT,
            formParameters = parameters {
                params.forEach { (k, v) -> append(k, v) }
            },
        ).bodyAsText()

        parseResult(response)
    }.getOrElse {
        ScrobbleResult.failure(it.message)
    }

    /**
     * 标记红心喜爱（Love）。
     */
    suspend fun love(
        apiKey: String,
        secret: String,
        sessionKey: String,
        track: String,
        artist: String,
    ): ScrobbleResult = runCatching {
        val params = mutableMapOf(
            "method" to "track.love",
            "api_key" to apiKey,
            "sk" to sessionKey,
            "track" to track,
            "artist" to artist,
        )
        params["api_sig"] = generateSignature(params, secret)
        params["format"] = "json"

        val response = client.submitForm(
            url = API_ENDPOINT,
            formParameters = parameters {
                params.forEach { (k, v) -> append(k, v) }
            },
        ).bodyAsText()

        parseResult(response)
    }.getOrElse {
        ScrobbleResult.failure(it.message)
    }

    /**
     * 取消标记红心（Unlove）。
     */
    suspend fun unlove(
        apiKey: String,
        secret: String,
        sessionKey: String,
        track: String,
        artist: String,
    ): ScrobbleResult = runCatching {
        val params = mutableMapOf(
            "method" to "track.unlove",
            "api_key" to apiKey,
            "sk" to sessionKey,
            "track" to track,
            "artist" to artist,
        )
        params["api_sig"] = generateSignature(params, secret)
        params["format"] = "json"

        val response = client.submitForm(
            url = API_ENDPOINT,
            formParameters = parameters {
                params.forEach { (k, v) -> append(k, v) }
            },
        ).bodyAsText()

        parseResult(response)
    }.getOrElse {
        ScrobbleResult.failure(it.message)
    }

    private fun parseResult(response: String): ScrobbleResult {
        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
            ?: return ScrobbleResult.failure("Invalid response format: $response")

        if (root.containsKey("error")) {
            val errCode = root["error"]?.jsonPrimitive?.intOrNull ?: -1
            val errMsg = root["message"]?.jsonPrimitive?.content ?: "Unknown error"
            // Last.fm code 14: Timestamp too old (Last.fm ignores scrobbles older than 14 days)
            val ignored = (errCode == 14)
            return ScrobbleResult(
                success = false,
                ignored = ignored,
                message = errMsg,
                errorCode = errCode,
            )
        }

        // 检查 scrobbles 对象里的 ignoredMessage
        val scrobbles = root["scrobbles"]?.jsonObject
        val scrobble = scrobbles?.get("scrobble")?.jsonObject
        val ignoredMessage = scrobble?.get("ignoredMessage")?.jsonObject?.get("content")?.jsonPrimitive?.content
        if (!ignoredMessage.isNullOrBlank() && ignoredMessage != "0") {
            return ScrobbleResult(success = true, ignored = true, message = ignoredMessage)
        }

        return ScrobbleResult.Success
    }
}
