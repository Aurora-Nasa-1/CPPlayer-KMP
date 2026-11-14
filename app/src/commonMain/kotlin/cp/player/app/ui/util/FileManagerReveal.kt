package cp.player.app.ui.util

import cp.player.app.i18n.DownloadStrings
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.openInFileManager

/**
 * 「在文件管理器中打开」的唯一调用入口。
 *
 * 各页面此前直接调 [openInFileManager] 并**丢掉返回值** —— Android 上那个 actual
 * 曾经恒返回 false，于是按钮点下去毫无反应、也没有任何解释。这里把「失败要让用户
 * 知道」收成一处：成功静默，失败弹一条带路径的提示（Android 的私有目录限制写进
 * 了文案里，用户才不会以为是自己点错了）。
 */
fun revealInFileManager(path: String, strings: DownloadStrings) {
    if (path.isNotBlank() && openInFileManager(path)) return
    val message = strings.openInFileManagerFailed(path)
    val hint = strings.openInFileManagerPrivateHint
    // 桌面端的失败基本是「路径已失效」，套上 Android 的私有目录说明只会误导。
    UiEvents.notify(if (isAndroidPlatform()) "$message\n$hint" else message)
}
