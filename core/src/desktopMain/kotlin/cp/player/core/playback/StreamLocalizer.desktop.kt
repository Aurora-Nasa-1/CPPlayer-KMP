package cp.player.core.playback

/**
 * 桌面端提供真实实现：桌面引擎（rodio）无法定位 FLAC over HTTP，无损流必须先落盘。
 * 见 [StreamLocalizer] 与 [DesktopStreamLocalizer]。
 */
actual fun createStreamLocalizer(): StreamLocalizer = DesktopStreamLocalizer()
