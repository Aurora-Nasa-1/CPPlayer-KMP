package cp.player.app.platform

/**
 * 桌面实现：直接转发给 `DesktopBackDispatcher`。
 *
 * ⚠️ `hasHandlers` 读的是**快照状态**（`handlerCount`）—— 在 composition 里读它会订阅，
 * 页面注册 / 注销处理器（展开播放页、进入歌单多选）会驱动重组，
 * 「返回上一级」这一项因此能跟着出现 / 消失。
 */
actual fun hasPageBackHandler(): Boolean = DesktopBackDispatcher.hasHandlers

actual fun dispatchPageBack(): Boolean = DesktopBackDispatcher.dispatch()
