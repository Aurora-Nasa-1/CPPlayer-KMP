package cp.player.app.ui.a2ui

/**
 * A2UI 入口的总开关。
 *
 * 私人 DJ 台与「AI 与 A2UI」设置页暂时下线：入口、弹层宿主与设置项**同时**由这个常量
 * 控制，避免出现「菜单里点得到、设置里又改不了」的半截状态。
 *
 * 恢复时只需把它改回 `true`；以下三处会自动跟着生效：
 * - `PlayerMoreBottomSheet` 的私人 DJ 台卡片；
 * - `DesktopPlayerScreen` 顶栏的直达按钮；
 * - `SettingsRegistry` 的 `ai_settings` 入口。
 *
 * 不要在各调用点分别写死 `if (false)` —— 那样恢复时会漏掉一处。
 */
internal const val A2UI_ENTRY_ENABLED: Boolean = false
