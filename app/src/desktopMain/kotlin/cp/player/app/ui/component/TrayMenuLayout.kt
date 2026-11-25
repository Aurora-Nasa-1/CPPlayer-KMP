package cp.player.app.ui.component

/**
 * 托盘菜单布局尺寸与常量（单位均为 dp）。
 */
internal object TrayMenuLayout {
    /** 菜单卡片本体宽。 */
    const val Width = 248

    /** 菜单卡片本体高。 */
    const val Height = 232

    /** 窗口四周留给阴影的边距。窗口物理尺寸 = 本体 + 两侧各这么多。 */
    const val ShadowPadding = 8

    /** 窗口总宽度。 */
    const val WindowWidth = Width + ShadowPadding * 2

    /** 窗口总高度。 */
    const val WindowHeight = Height + ShadowPadding * 2

    /** 菜单卡片圆角半径。 */
    const val CornerRadius = 14
}
