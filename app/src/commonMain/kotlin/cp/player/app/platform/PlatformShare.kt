package cp.player.app.platform

/**
 * 分享文本（如歌单链接）。
 * Android 端唤起系统分享面板；Desktop 端复制到剪贴板并提示。
 *
 * @param text 要分享的文本内容
 */
expect fun shareText(text: String)
