package cp.player.core.playback

import cp.player.core.playback.drm.WebViewDrmBrowser

/**
 * 桌面端经 WebView EME 桥获得 Widevine：Edge / Chrome / WebView2 Runtime 内核
 * 均自带 Widevine CDM（解密在浏览器进程里完成，license 由宿主转 Provider 交换）。
 * 找不到任何可用内核时保持为空列表，音源回退明文 preview。
 */
actual fun supportedDrmKeySystems(): List<String> =
    if (WebViewDrmBrowser.isAvailable) listOf("widevine") else emptyList()
