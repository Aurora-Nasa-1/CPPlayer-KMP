package cp.player.core.playback

import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.CoroutineScope

/**
 * 音频平滑过渡与淡入淡出协同组件。
 *
 * 职责：
 * 1. 维护用户真实音量（[userVolume]）作为唯一事实源，防止淡出残值污染用户设置。
 * 2. 管理切歌淡入与曲末淡出斜坡（[FadeRunner]）。
 * 3. 监听播放进度并在曲末按剩余时长触发平滑淡出，且保证单曲内不重复触发。
 */
internal class PlaybackFadeController(
    private val platform: PlatformPlayer,
    private val scope: CoroutineScope,
    private val fadeSettings: SettingsStorage? = null,
) {
    @Volatile
    var userVolume: Float = 1f
        private set

    @Volatile
    var fadeConfig: FadeConfig = fadeSettings?.let { FadeSettings.read(it) } ?: FadeConfig.OFF
        private set

    @Volatile
    private var fadeOutStartedFor: String? = null

    private val fadeRunner: FadeRunner = FadeRunner(
        scope = scope,
        apply = { v -> platform.setVolume(v.coerceIn(0f, 1f)) },
    )

    fun setUserVolume(volume: Float): Float {
        val v = volume.coerceIn(0f, 1f)
        userVolume = v
        fadeRunner.cancel()
        platform.setVolume(v)
        return v
    }

    fun setFade(config: FadeConfig) {
        fadeConfig = config
        fadeSettings?.let { FadeSettings.write(it, config) }
        if (!config.enabled) {
            fadeRunner.cancel()
            platform.setVolume(userVolume)
        }
    }

    fun onTrackPreparing() {
        fadeRunner.cancel()
        fadeOutStartedFor = null
    }

    fun startFadeIn() {
        val cfg = fadeConfig
        if (!cfg.enabled || !cfg.fadeIn) {
            fadeRunner.cancel()
            platform.setVolume(userVolume)
            return
        }
        platform.setVolume(0f)
        fadeRunner.start(
            from = 0f,
            to = userVolume,
            durationMs = cfg.durationMsClamped,
        )
    }

    fun maybeStartFadeOut(pos: Long, durationMs: Long?, currentMediaId: String?, engineReady: Boolean) {
        val cfg = fadeConfig
        if (!cfg.enabled || !cfg.fadeOut) return
        if (!engineReady) return
        if (fadeOutStartedFor == currentMediaId) return
        val total = durationMs ?: return
        if (total <= 0L) return

        val remaining = total - pos
        if (remaining > cfg.durationMsClamped || remaining <= 0L) return

        fadeOutStartedFor = currentMediaId
        val effective = remaining.coerceAtMost(cfg.durationMsClamped.toLong()).toInt()
        fadeRunner.start(
            from = currentPlatformVolume(),
            to = 0f,
            durationMs = effective,
        )
    }

    private fun currentPlatformVolume(): Float =
        runCatching { platform.getVolume() }.getOrNull()?.coerceIn(0f, 1f) ?: userVolume

    fun cancel() {
        fadeRunner.cancel()
        fadeOutStartedFor = null
    }
}
