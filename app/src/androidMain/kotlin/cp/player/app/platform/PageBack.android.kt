package cp.player.app.platform

/**
 * Android 实现：此处恒为空。
 *
 * Android 的返回由 `androidx.activity.compose.BackHandler`（`BackHandler` 的安卓实现）
 * 与 Voyager 的 `Navigator` 接管，页面不向这套分发器注册；而桌面专属的
 * 「空白处右键菜单」在安卓上也不存在（见 `rememberBackContextMenuItem` 的说明）。
 */
actual fun hasPageBackHandler(): Boolean = false

actual fun dispatchPageBack(): Boolean = false
