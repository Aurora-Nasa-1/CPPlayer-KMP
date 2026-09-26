package cp.player.core.playback

/**
 * 安卓是**有理由的**空实现，不是「以后再说」。
 *
 * Media3 / ExoPlayer 能正常定位 HTTP FLAC（引擎侧的局限只在桌面 rodio 上），
 * 所以安卓不需要「先落盘」这一步 —— 加进来只会平白让首播等一次整曲下载。
 * 见 [StreamLocalizer] 的实测矩阵。
 */
actual fun createStreamLocalizer(): StreamLocalizer = NoOpStreamLocalizer
