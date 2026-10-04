package cp.player.app.platform

/**
 * 「返回」的**页面级处理器**：桌面端由 `DesktopBackDispatcher` 承担（`BackHandler` 的桌面实现
 * 就是往那里注册），Android 端恒为空（系统返回键由 Voyager / `androidx.activity` 接管）。
 *
 * ## 为什么需要这层包装
 *
 * 桌面的返回语义有**四条**来源，页面自己只看得见其中一条：
 * 1. 根 Navigator 能出栈；
 * 2. 主壳层的内容区**内嵌** Navigator 能出栈；
 * 3. 主壳层的内嵌面板开着（可收起）；
 * 4. **页面自己注册的处理器**（播放页展开态、歌单多选 —— 它们既没 push 路由也没开面板）。
 *
 * 窗口标题栏的返回键（`desktopMain/Main.kt` 的 `onBack`）把四条串成一条链。
 * 「空白处右键 → 返回上一级」必须复用**同一条链**，否则又会出现
 * 「Esc 能退、右键菜单里没有这一项」这类漂移（本仓库踩过两次）。
 * 第 4 条偏偏落在 `desktopMain`（`DesktopBackDispatcher` 是桌面专属对象），
 * 所以要用它就只能经 expect / actual 把它暴露给 `commonMain`。
 */
expect fun hasPageBackHandler(): Boolean

/**
 * 把一次返回派发给**页面自己**注册的处理器（后注册者优先，见 `DesktopBackDispatcher`）。
 *
 * @return 是否有人消费。`false` 表示调用方应当继续走「出栈 / 收面板」的兜底。
 */
expect fun dispatchPageBack(): Boolean
