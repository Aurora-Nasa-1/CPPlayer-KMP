package cp.player.core.playback.flick

import android.content.Context
import android.util.Log
import cp.player.core.playback.PlaybackSessionSettings
import cp.player.core.playback.PlatformPlayer
import cp.player.core.playback.createPlatformPlayer
import cp.player.core.util.PlatformContext
import cp.player.core.util.SettingsStorage
import cp.player.core.util.defaultSettingsStorage
import cp.player.engine.RustEngine

/**
 * 手机端播放引擎类型。
 */
enum class AndroidPlayerEngineType {
    /** 标准系统引擎（基于 AndroidX Media3 ExoPlayer），网络流鲁棒性高，兼容性好。 */
    STANDARD_MEDIA3,

    /**
     * 高级发烧引擎（基于 Rust Oboe + Symphonia + UAC2 USB 独占 + DSD 软解）。
     * 适合用于后续「VIP / Hi-Res 专业版」付费特权功能。
     */
    HIRES_FLICK,
}

/**
 * 播放器引擎管理器，支持动态切换标准 Media3 与 Flick Hi-Res 引擎。
 */
object FlickEngineManager {
    private const val TAG = "FlickEngineManager"

    /** 检查当前设备与安装包是否支持 Flick Hi-Res 原生引擎 */
    val isHiResEngineSupported: Boolean
        get() = RustEngine.isAvailable

    /**
     * 根据偏好设置与底层可用性创建对应的平台播放器。
     *
     * 默认从 [defaultSettingsStorage] 读取配置 [PlaybackSessionSettings.KEY_AUDIO_ENGINE]。
     */
    fun createPlayer(
        context: Context,
        preferredEngine: AndroidPlayerEngineType? = null,
        storage: SettingsStorage = defaultSettingsStorage(),
        isVipUser: Boolean = false,
    ): PlatformPlayer {
        val selected = preferredEngine ?: when (storage.getString(PlaybackSessionSettings.KEY_AUDIO_ENGINE)) {
            PlaybackSessionSettings.ENGINE_FLICK_HIRES -> AndroidPlayerEngineType.HIRES_FLICK
            else -> AndroidPlayerEngineType.STANDARD_MEDIA3
        }

        if (selected == AndroidPlayerEngineType.HIRES_FLICK) {
            if (isVipUser || true /* 开发调试期间放开 */) {
                if (isHiResEngineSupported) {
                    Log.i(TAG, "Creating FlickPlatformPlayer (Hi-Res Rust Engine)")
                    return FlickPlatformPlayer(context)
                } else {
                    Log.w(TAG, "Flick Hi-Res Engine requested but native library is unavailable. Falling back to Media3.")
                }
            } else {
                Log.i(TAG, "Flick Hi-Res Engine is a VIP feature. Falling back to Media3.")
            }
        }
        return createPlatformPlayer(PlatformContext(context))
    }
}
