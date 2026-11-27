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

    /**
     * 引擎自己能做真交叉淡化（双解码源重叠）时，宿主的音量斜坡必须**完全让位**。
     *
     * 两套机制同时改音量会「淡出两次」，而且引擎那套还会自己起下一个源，
     * 宿主再补一次 fade-in 只会把它压掉。所以这里不是「优先级」而是**互斥**：
     * native 模式下 3. 的曲末淡出、切歌淡入全部 no-op，音量始终等于 [userVolume]。
     *
     * ⚠️ 判据是 [PlatformPlayer.nativeCrossfadeReady] 而不是静态能力位
     * [PlatformPlayer.supportsNativeCrossfade]：
     * 引擎的真交叉淡化要求「本曲剩余时长 ≤ 淡化时长」，而剩余时长来自引擎自报的总长。
     * 总长拿不到（`n_frames` 缺失的网络流）或下一首没排进引擎时，引擎那套**一次都不会触发**；
     * 此时若还让位，就等于「开关开着、两套机制都不跑」—— 用户看到的就是完全不生效。
     * 这种情况下退回宿主的单播放器斜坡，至少听感上是对的（见 [FadeConfig] 的 KDoc）。
     *
     * 用 getter 而不是构造时快照：引擎可能在设置页读能力位之后才完成 init/拿到总长。
     */
    private val useNativeCrossfade: Boolean
        get() = platform.supportsNativeCrossfade && platform.nativeCrossfadeReady

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
        // 配置**始终**下发给引擎（含关闭），即便此刻它不会生效。
        // 能否生效是逐曲变化的（见 useNativeCrossfade）：本曲拿不到总长时宿主在自己跑斜坡，
        // 下一首拿到了就该由引擎接手 —— 那时用户不会再动这个开关，补发就来不及了。
        if (platform.supportsNativeCrossfade) {
            platform.setNativeCrossfade(config.enabled, config.durationMsClamped.toLong())
        }
        if (!config.enabled) {
            fadeRunner.cancel()
            platform.setVolume(userVolume)
            return
        }
        if (useNativeCrossfade) {
            // 引擎接管：音量必须恒等于用户音量，宿主不再碰（可能刚从中断的斜坡里退出）。
            fadeRunner.cancel()
            platform.setVolume(userVolume)
        }
    }

    fun onTrackPreparing() {
        fadeRunner.cancel()
        fadeOutStartedFor = null
    }

    fun startFadeIn() {
        if (useNativeCrossfade) return
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
        if (useNativeCrossfade) return
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
