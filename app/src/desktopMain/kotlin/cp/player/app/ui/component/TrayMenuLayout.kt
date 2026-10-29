package cp.player.app.ui.component

/**
 * 托盘菜单能触发的动作。
 *
 * 放在 ui 侧而不是 `platform`：布局（[TrayMenuLayout]）和视觉（TrayMenuContent）都要用它，
 * 而 platform 本来就要依赖 ui，反过来再依赖一次就成环了。
 */
internal enum class TrayMenuAction {
    SHOW_WINDOW,
    TOGGLE_MINI_MODE,
    PREVIOUS,
    PLAY_PAUSE,
    NEXT,
    EXIT,
}

/**
 * 托盘菜单的**唯一布局真相**。
 *
 * 菜单是「Compose 离屏渲染成图 → AWT 窗口显示图片」，所以同一份坐标要被两边同时使用：
 * 渲染端按它摆控件，AWT 端按它做命中测试（图片本身不会告诉你哪里能点）。
 *
 * **所以这里必须是纯数据，不能有 Compose 布局的隐式行为**（比如 `weight`、`Arrangement`
 * 这类会让实际位置偏离声明值的写法）。一旦两边各算各的，「点得到的地方」和「看起来能点
 * 的地方」就会漂移 —— 而这种漂移在出图上完全看不出来。
 *
 * 单位一律是 **dp**（= AWT 的用户空间像素，HiDPI 下两边按同一个缩放倍率换算）。
 */
internal object TrayMenuLayout {

    /** 菜单本体（不含阴影留白）。 */
    const val Width = 248
    const val Height = 232

    /** 四周留出来画投影的空白。窗口尺寸 = 本体 + 两侧各这么多。 */
    const val Shadow = 12

    const val CornerRadius = 14
    const val Padding = 6

    const val TitleLeft = 12
    const val TitleTop = 10
    const val TitleHeight = 20
    const val TitleWidth = Width - TitleLeft * 2

    // 曲名与歌手之间刻意留 2dp：两行贴死时（20sp 行高的余量很小）看起来像同一句被折断。
    const val SubtitleTop = TitleTop + TitleHeight + 2
    const val SubtitleHeight = 16

    const val ControlsTop = 54
    const val ControlStart = 12
    const val ControlSize = 32
    const val ControlGap = 6

    const val Divider1Y = 90
    const val Item1Top = 97
    const val ItemHeight = 38
    const val Item2Top = Item1Top + ItemHeight
    const val Divider2Y = 180
    const val Item3Top = 186

    /** 菜单项内的左右内边距与图标尺寸。 */
    const val ItemPadding = 10
    const val ItemIconSize = 17
    const val ItemIconGap = 10

    /** 三个圆形控制键，从左到右。绘制与命中共用这一份顺序。 */
    val controlActions = listOf(
        TrayMenuAction.PREVIOUS,
        TrayMenuAction.PLAY_PAUSE,
        TrayMenuAction.NEXT,
    )

    /** 菜单项，从上到下。 */
    val itemActions = listOf(
        TrayMenuAction.SHOW_WINDOW,
        TrayMenuAction.TOGGLE_MINI_MODE,
        TrayMenuAction.EXIT,
    )

    /**
     * 命中测试：点在菜单**本体坐标系**（已扣掉阴影留白）里的哪个动作上。
     *
     * @return null = 空白 / 分隔线 / 信息区
     */
    fun actionAt(x: Float, y: Float): TrayMenuAction? {
        if (y >= ControlsTop && y < ControlsTop + ControlSize) {
            controlActions.forEachIndexed { index, action ->
                val centerX = ControlStart + index * (ControlSize + ControlGap) + ControlSize / 2f
                if (kotlin.math.abs(x - centerX) <= ControlSize / 2f) return action
            }
        }
        return when {
            y >= Item1Top && y < Item1Top + ItemHeight -> TrayMenuAction.SHOW_WINDOW
            y >= Item2Top && y < Item2Top + ItemHeight -> TrayMenuAction.TOGGLE_MINI_MODE
            y >= Item3Top && y < Item3Top + ItemHeight -> TrayMenuAction.EXIT
            else -> null
        }
    }
}
