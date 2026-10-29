package cp.player.core.scrobble

import cp.player.core.util.SettingsStorage
import cp.player.core.util.defaultSettingsStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Last.fm 官方打卡服务实现。
 */
class LastFmScrobbler(
    private val api: LastFmApi = LastFmApi(),
    private val storage: SettingsStorage = defaultSettingsStorage("scrobble_lastfm"),
) : ExternalScrobbler {

    override val id: String = ID
    override val displayName: String = "Last.fm"

    private val _statusFlow = MutableStateFlow(loadInitialStatus())
    override val statusFlow: StateFlow<ScrobblerStatus> = _statusFlow.asStateFlow()

    override val isEnabled: Boolean get() = _statusFlow.value.isEnabled
    override val isAuthorized: Boolean get() = _statusFlow.value.isAuthorized
    override val username: String? get() = _statusFlow.value.username

    var sessionKey: String? = storage.getString(KEY_SESSION_KEY)
        private set

    var customApiKey: String? = storage.getString(KEY_CUSTOM_API_KEY)
        private set

    var customSecret: String? = storage.getString(KEY_CUSTOM_SECRET)
        private set

    var nowPlayingEnabled: Boolean = storage.getString(KEY_NOW_PLAYING)?.toBooleanStrictOrNull() ?: true
        private set

    var loveSyncEnabled: Boolean = storage.getString(KEY_LOVE_SYNC)?.toBooleanStrictOrNull() ?: true
        private set

    var includeLocalTracks: Boolean = storage.getString(KEY_INCLUDE_LOCAL)?.toBooleanStrictOrNull() ?: true
        private set

    val effectiveApiKey: String
        get() = customApiKey?.takeIf { it.isNotBlank() } ?: LastFmApi.DEFAULT_API_KEY

    val effectiveSecret: String
        get() = customSecret?.takeIf { it.isNotBlank() } ?: LastFmApi.DEFAULT_API_SECRET

    private fun loadInitialStatus(): ScrobblerStatus {
        val enabled = storage.getString(KEY_ENABLED)?.toBooleanStrictOrNull() ?: false
        val user = storage.getString(KEY_USERNAME)
        val sk = storage.getString(KEY_SESSION_KEY)
        return ScrobblerStatus(
            isEnabled = enabled && !sk.isNullOrBlank(),
            isAuthorized = !sk.isNullOrBlank(),
            username = user,
        )
    }

    fun setEnabled(enabled: Boolean) {
        storage.putString(KEY_ENABLED, enabled.toString())
        _statusFlow.value = _statusFlow.value.copy(
            isEnabled = enabled && isAuthorized,
        )
    }

    fun setNowPlayingEnabled(enabled: Boolean) {
        nowPlayingEnabled = enabled
        storage.putString(KEY_NOW_PLAYING, enabled.toString())
    }

    fun setLoveSyncEnabled(enabled: Boolean) {
        loveSyncEnabled = enabled
        storage.putString(KEY_LOVE_SYNC, enabled.toString())
    }

    fun setIncludeLocalTracks(enabled: Boolean) {
        includeLocalTracks = enabled
        storage.putString(KEY_INCLUDE_LOCAL, enabled.toString())
    }

    fun setCustomCredentials(apiKey: String?, secret: String?) {
        customApiKey = apiKey?.takeIf { it.isNotBlank() }
        customSecret = secret?.takeIf { it.isNotBlank() }
        if (customApiKey != null) storage.putString(KEY_CUSTOM_API_KEY, customApiKey!!) else storage.remove(KEY_CUSTOM_API_KEY)
        if (customSecret != null) storage.putString(KEY_CUSTOM_SECRET, customSecret!!) else storage.remove(KEY_CUSTOM_SECRET)
    }

    /**
     * 发起网页授权：获取临时 token 并返回浏览器应打开的 URL。
     */
    suspend fun startAuthWebFlow(): Result<Pair<String, String>> = runCatching {
        val token = api.fetchRequestToken(effectiveApiKey, effectiveSecret).getOrThrow()
        val authUrl = api.buildAuthWebUrl(effectiveApiKey, token)
        token to authUrl
    }

    /**
     * 完成授权：用户在浏览器点击授权后，调用本方法换取 Session 并保存。
     */
    suspend fun finishAuth(token: String): Result<String> = runCatching {
        val (sk, user) = api.fetchSession(effectiveApiKey, effectiveSecret, token).getOrThrow()
        sessionKey = sk
        storage.putString(KEY_SESSION_KEY, sk)
        storage.putString(KEY_USERNAME, user)
        storage.putString(KEY_ENABLED, "true")

        _statusFlow.value = ScrobblerStatus(
            isEnabled = true,
            isAuthorized = true,
            username = user,
            lastError = null,
        )
        user
    }.onFailure { err ->
        _statusFlow.value = _statusFlow.value.copy(lastError = err.message)
    }

    /**
     * 断开连接/退出授权。
     */
    fun disconnect() {
        sessionKey = null
        storage.remove(KEY_SESSION_KEY)
        storage.remove(KEY_USERNAME)
        storage.putString(KEY_ENABLED, "false")
        _statusFlow.value = ScrobblerStatus(
            isEnabled = false,
            isAuthorized = false,
            username = null,
            lastError = null,
        )
    }

    override suspend fun updateNowPlaying(track: ScrobbleTrack): ScrobbleResult {
        if (!isEnabled || !isAuthorized || !nowPlayingEnabled) return ScrobbleResult.Ignored
        if (track.isLocal && !includeLocalTracks) return ScrobbleResult.Ignored
        val sk = sessionKey ?: return ScrobbleResult.failure("Not authorized")

        val res = api.updateNowPlaying(
            apiKey = effectiveApiKey,
            secret = effectiveSecret,
            sessionKey = sk,
            track = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = track.durationSec,
        )
        if (!res.success) {
            checkAuthError(res.errorCode)
        }
        return res
    }

    override suspend fun scrobble(track: ScrobbleTrack): ScrobbleResult {
        if (!isEnabled || !isAuthorized) return ScrobbleResult.Ignored
        if (track.isLocal && !includeLocalTracks) return ScrobbleResult.Ignored
        val sk = sessionKey ?: return ScrobbleResult.failure("Not authorized")

        val res = api.scrobble(
            apiKey = effectiveApiKey,
            secret = effectiveSecret,
            sessionKey = sk,
            track = track.title,
            artist = track.artist,
            album = track.album,
            timestampSec = track.timestampSec,
            durationSec = track.durationSec,
        )
        if (!res.success) {
            checkAuthError(res.errorCode)
        }
        return res
    }

    override suspend fun love(track: ScrobbleTrack): ScrobbleResult {
        if (!isEnabled || !isAuthorized || !loveSyncEnabled) return ScrobbleResult.Ignored
        val sk = sessionKey ?: return ScrobbleResult.failure("Not authorized")

        val res = api.love(
            apiKey = effectiveApiKey,
            secret = effectiveSecret,
            sessionKey = sk,
            track = track.title,
            artist = track.artist,
        )
        if (!res.success) {
            checkAuthError(res.errorCode)
        }
        return res
    }

    override suspend fun unlove(track: ScrobbleTrack): ScrobbleResult {
        if (!isEnabled || !isAuthorized || !loveSyncEnabled) return ScrobbleResult.Ignored
        val sk = sessionKey ?: return ScrobbleResult.failure("Not authorized")

        val res = api.unlove(
            apiKey = effectiveApiKey,
            secret = effectiveSecret,
            sessionKey = sk,
            track = track.title,
            artist = track.artist,
        )
        if (!res.success) {
            checkAuthError(res.errorCode)
        }
        return res
    }

    private fun checkAuthError(errorCode: Int?) {
        // Last.fm code 4: Authentication Failed, code 9: Invalid session key
        if (errorCode == 4 || errorCode == 9) {
            _statusFlow.value = _statusFlow.value.copy(
                isAuthorized = false,
                lastError = "Last.fm 凭据已失效，请重新授权",
            )
        }
    }

    companion object {
        const val ID = "lastfm"

        private const val KEY_ENABLED = "lastfm_enabled"
        private const val KEY_SESSION_KEY = "lastfm_sk"
        private const val KEY_USERNAME = "lastfm_user"
        private const val KEY_NOW_PLAYING = "lastfm_now_playing"
        private const val KEY_LOVE_SYNC = "lastfm_love_sync"
        private const val KEY_INCLUDE_LOCAL = "lastfm_include_local"
        private const val KEY_CUSTOM_API_KEY = "lastfm_custom_api_key"
        private const val KEY_CUSTOM_SECRET = "lastfm_custom_secret"
    }
}
