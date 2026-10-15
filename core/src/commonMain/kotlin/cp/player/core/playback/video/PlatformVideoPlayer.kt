package cp.player.core.playback.video

import cp.player.core.media.VideoResolution
import cp.player.core.playback.PlatformMediaPlayer
import cp.player.core.util.PlatformContext
import kotlinx.coroutines.flow.StateFlow

/**
 * 跨平台视频播放内核接口。
 *
 * 继承 [PlatformMediaPlayer]，在基础播放生命周期的基础上，
 * 增加了视频画幅尺寸感知、无缝切换清晰度与平台渲染载体（Surface/Window）绑定的能力。
 */
interface PlatformVideoPlayer : PlatformMediaPlayer {
    /** 视频宽高比与分辨率，未就绪或无法解析时为 null */
    val videoResolution: StateFlow<VideoResolution?>

    /**
     * 平滑切换清晰度（无缝续播）。
     *
     * 保持当前精确进度与播放状态，实现音画不回弹的平滑衔接。
     *
     * @param url 新清晰度流地址
     * @param headers 请求头（防盗链 Referer / User-Agent 等）
     */
    suspend fun switchQuality(
        url: String,
        headers: Map<String, String> = emptyMap(),
    )

    /**
     * 挂载平台特定的渲染承载体。
     *
     * - Android: 支持传入 `SurfaceView`、`TextureView` 或 `androidx.media3.ui.PlayerView`
     * - Desktop: 支持传入 AWT `Canvas` / `Component` / 原生窗口句柄 (HWND / Window ID)
     * - 传入 null 表示解绑（组件 dispose 或页面退出时调用）
     */
    fun attachSurface(surfaceTarget: Any?)
}

/**
 * 工厂函数：创建平台对应的视频播放内核实现。
 */
expect fun createPlatformVideoPlayer(context: PlatformContext): PlatformVideoPlayer
