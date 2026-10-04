package cp.player.app.shortcut

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

/**
 * 桌面端快捷键的**唯一声明**（动作清单 / 默认绑定 / 序列化格式）。
 *
 * ## 为什么要有这一层
 *
 * 重构前全部快捷键就是 `Main.kt` 里 `handleDesktopShortcut` 的一串硬编码 `when`：
 * 5 条规则、键位写死在表达式里，既看不见全貌、也没法给用户改。要加一条得同时想清楚
 * 「会不会和别的键撞、要不要先判 Ctrl」，而要挪个位置只能改代码。
 *
 * 现在分成三层，各自只回答一个问题：
 * 1. [ShortcutKey] —— **有哪些键可以被绑定**（带稳定 token，用于落盘）；
 * 2. [ShortcutBinding] —— 一个具体的「修饰键 + 主键」组合，负责解析 / 序列化 / 匹配 / 显示；
 * 3. [ShortcutAction] —— **有哪些动作**、默认绑什么、属于哪个分组（设置页按它渲染）。
 *
 * 用户改过的绑定只以 `action.id` 为键落在设置里（见 `AppModel`），没改过的走 [ShortcutAction.defaultBinding] ——
 * 于是将来调整默认键位时，**只有没自定义过的用户会跟着变**，动过手的用户不受影响。
 *
 * ## 只支持有名字的键
 *
 * 刻意不做「任意 keyCode 都存」：[ShortcutKey] 是一张白名单，token 是稳定字符串
 * （`"left"` / `"f5"`），落盘后换平台、换 Compose 版本都还能解析出来。
 * 存裸 keyCode 则会随平台映射变化而失效，而且设置页里根本显示不出「这是什么键」。
 */

// ============================================================ 可绑定按键

/**
 * 一个可以被绑定的物理按键。
 *
 * [token] 是落盘用的稳定标识（**不要**用 [label] 存盘：它是给人看的，改文案不该让绑定失效）。
 */
enum class ShortcutKey(val token: String, val label: String, val key: Key) {
    // —— 功能键 ——
    SPACE("space", "空格", Key.Spacebar),
    ENTER("enter", "回车", Key.Enter),
    TAB("tab", "Tab", Key.Tab),
    ESCAPE("escape", "Esc", Key.Escape),
    BACKSPACE("backspace", "退格", Key.Backspace),
    DELETE("delete", "Delete", Key.Delete),
    INSERT("insert", "Insert", Key.Insert),
    // ⚠️ 用 `MoveHome` 而不是 `Key.Home`：后者在 Compose 1.11 已标 `@Deprecated`。
    HOME("home", "Home", Key.MoveHome),
    END("end", "End", Key.MoveEnd),
    PAGE_UP("page_up", "PageUp", Key.PageUp),
    PAGE_DOWN("page_down", "PageDown", Key.PageDown),

    // —— 方向键 ——
    LEFT("left", "←", Key.DirectionLeft),
    RIGHT("right", "→", Key.DirectionRight),
    UP("up", "↑", Key.DirectionUp),
    DOWN("down", "↓", Key.DirectionDown),

    // —— 符号键 ——
    COMMA("comma", ",", Key.Comma),
    PERIOD("period", ".", Key.Period),
    MINUS("minus", "-", Key.Minus),
    EQUALS("equals", "=", Key.Equals),
    SLASH("slash", "/", Key.Slash),
    SEMICOLON("semicolon", ";", Key.Semicolon),
    APOSTROPHE("apostrophe", "'", Key.Apostrophe),
    GRAVE("grave", "`", Key.Grave),
    BRACKET_LEFT("bracket_left", "[", Key.LeftBracket),
    BRACKET_RIGHT("bracket_right", "]", Key.RightBracket),
    BACKSLASH("backslash", "\\", Key.Backslash),

    // —— 字母 ——
    A("a", "A", Key.A),
    B("b", "B", Key.B),
    C("c", "C", Key.C),
    D("d", "D", Key.D),
    E("e", "E", Key.E),
    F("f", "F", Key.F),
    G("g", "G", Key.G),
    H("h", "H", Key.H),
    I("i", "I", Key.I),
    J("j", "J", Key.J),
    K("k", "K", Key.K),
    L("l", "L", Key.L),
    M("m", "M", Key.M),
    N("n", "N", Key.N),
    O("o", "O", Key.O),
    P("p", "P", Key.P),
    Q("q", "Q", Key.Q),
    R("r", "R", Key.R),
    S("s", "S", Key.S),
    T("t", "T", Key.T),
    U("u", "U", Key.U),
    V("v", "V", Key.V),
    W("w", "W", Key.W),
    X("x", "X", Key.X),
    Y("y", "Y", Key.Y),
    Z("z", "Z", Key.Z),

    // —— 数字 ——
    ZERO("0", "0", Key.Zero),
    ONE("1", "1", Key.One),
    TWO("2", "2", Key.Two),
    THREE("3", "3", Key.Three),
    FOUR("4", "4", Key.Four),
    FIVE("5", "5", Key.Five),
    SIX("6", "6", Key.Six),
    SEVEN("7", "7", Key.Seven),
    EIGHT("8", "8", Key.Eight),
    NINE("9", "9", Key.Nine),

    // —— 功能键 ——
    F1("f1", "F1", Key.F1),
    F2("f2", "F2", Key.F2),
    F3("f3", "F3", Key.F3),
    F4("f4", "F4", Key.F4),
    F5("f5", "F5", Key.F5),
    F6("f6", "F6", Key.F6),
    F7("f7", "F7", Key.F7),
    F8("f8", "F8", Key.F8),
    F9("f9", "F9", Key.F9),
    F10("f10", "F10", Key.F10),
    F11("f11", "F11", Key.F11),
    F12("f12", "F12", Key.F12),
    ;

    companion object {
        /** 白名单里是否有这个物理键（录制时用来判断「这个键能不能绑」）。 */
        fun ofKey(key: Key): ShortcutKey? = entries.firstOrNull { it.key == key }

        fun ofToken(token: String): ShortcutKey? = entries.firstOrNull { it.token == token }
    }
}

// ============================================================ 绑定

/**
 * 一个「修饰键 + 主键」的组合。
 *
 * 修饰键只有 Ctrl / Shift / Alt 三个：**Meta（Win 键）刻意不支持** ——
 * 它在 Windows 上基本被系统占用（Win+方向键 = 贴边、Win+D = 显示桌面），
 * 绑了也抢不到事件，放进设置页只会让用户白折腾。
 *
 * [matches] 是**全等**匹配：绑 `Ctrl+←` 就不会被 `Ctrl+Shift+←` 触发。
 * 这是刻意的 —— 两个动作绑成「一个是另一个的前缀」时，靠全等才能让两个都可达
 * （历史上的 `when` 顺序写法必须把「切歌」排在「快进」前面，正是这个原因）。
 */
data class ShortcutBinding(
    val key: ShortcutKey,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    /** 纯值匹配（单元测试直接调它，不需要伪造平台按键事件）。 */
    fun matches(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean): Boolean =
        this.key.key == key && this.ctrl == ctrl && this.shift == shift && this.alt == alt

    fun matches(event: KeyEvent): Boolean =
        matches(event.key, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed)

    /** 给用户看的名字，如 `Ctrl+Shift+←`。 */
    val displayName: String
        get() = buildString {
            if (ctrl) append("Ctrl + ")
            if (shift) append("Shift + ")
            if (alt) append("Alt + ")
            append(key.label)
        }

    /** 落盘格式：`ctrl+shift+left`（修饰键固定顺序，不与 UI 文案耦合）。 */
    fun serialize(): String = buildString {
        if (ctrl) append("ctrl+")
        if (shift) append("shift+")
        if (alt) append("alt+")
        append(key.token)
    }

    companion object {
        /** 解析 [serialize] 的输出；无法识别时返回 null（调用方回退默认绑定）。 */
        fun parse(raw: String?): ShortcutBinding? {
            if (raw.isNullOrBlank()) return null
            var ctrl = false
            var shift = false
            var alt = false
            var keyToken: String? = null
            raw.split('+').forEach { part ->
                when (part) {
                    "ctrl" -> ctrl = true
                    "shift" -> shift = true
                    "alt" -> alt = true
                    // 主键的 token 本身不含 `+`（`backslash` 的 label 才是 `\`），
                    // 所以最后一个非修饰段就是主键。
                    else -> if (part.isNotBlank()) keyToken = part
                }
            }
            val key = keyToken?.let { ShortcutKey.ofToken(it) } ?: return null
            return ShortcutBinding(key, ctrl, shift, alt)
        }
    }
}

// ============================================================ 动作

/** 设置页里的分组标题。按「用户想干什么」划分，不按代码模块划分。 */
enum class ShortcutCategory(val title: String) {
    PLAYBACK("播放控制"),
    MODE("播放模式与收藏"),
    NAVIGATION("导航与窗口"),
}

/**
 * 一个可绑定的动作。
 *
 * [defaultBinding] 为 `null` 表示**默认就没绑**（用户自己去设置页绑一个）。
 *
 * ⚠️ [id] 是落盘键名的一部分，**改名等于把用户的绑定丢掉**；要改文案改 [label] / [hint]。
 */
enum class ShortcutAction(
    val id: String,
    val label: String,
    val category: ShortcutCategory,
    val defaultBinding: ShortcutBinding?,
    val hint: String,
) {
    PLAY_PAUSE(
        id = "play_pause",
        label = "播放 / 暂停",
        category = ShortcutCategory.PLAYBACK,
        defaultBinding = ShortcutBinding(ShortcutKey.SPACE),
        hint = "切换当前曲目的播放状态",
    ),
    PREV_TRACK(
        id = "prev_track",
        label = "上一首",
        category = ShortcutCategory.PLAYBACK,
        defaultBinding = ShortcutBinding(ShortcutKey.LEFT, ctrl = true, shift = true),
        hint = "跳到队列中的上一首",
    ),
    NEXT_TRACK(
        id = "next_track",
        label = "下一首",
        category = ShortcutCategory.PLAYBACK,
        defaultBinding = ShortcutBinding(ShortcutKey.RIGHT, ctrl = true, shift = true),
        hint = "跳到队列中的下一首",
    ),
    SEEK_BACKWARD(
        id = "seek_backward",
        label = "快退 5 秒",
        category = ShortcutCategory.PLAYBACK,
        defaultBinding = ShortcutBinding(ShortcutKey.LEFT, ctrl = true),
        hint = "只在可拖动的曲目上生效（时长未知时自动忽略）",
    ),
    SEEK_FORWARD(
        id = "seek_forward",
        label = "快进 5 秒",
        category = ShortcutCategory.PLAYBACK,
        defaultBinding = ShortcutBinding(ShortcutKey.RIGHT, ctrl = true),
        hint = "只在可拖动的曲目上生效（时长未知时自动忽略）",
    ),
    TOGGLE_FAVORITE(
        id = "toggle_favorite",
        label = "收藏 / 取消收藏",
        category = ShortcutCategory.MODE,
        defaultBinding = ShortcutBinding(ShortcutKey.L),
        hint = "收藏当前播放的曲目；未登录时不生效",
    ),
    TOGGLE_SHUFFLE(
        id = "toggle_shuffle",
        label = "随机播放",
        category = ShortcutCategory.MODE,
        defaultBinding = ShortcutBinding(ShortcutKey.S),
        hint = "开 / 关当前队列的随机播放",
    ),
    CYCLE_REPEAT(
        id = "cycle_repeat",
        label = "切换循环模式",
        category = ShortcutCategory.MODE,
        defaultBinding = ShortcutBinding(ShortcutKey.R),
        hint = "关 → 列表循环 → 单曲循环 → 关",
    ),
    BACK(
        id = "back",
        label = "返回上一级",
        category = ShortcutCategory.NAVIGATION,
        defaultBinding = ShortcutBinding(ShortcutKey.ESCAPE),
        hint = "等价于标题栏上的返回键：先退当前页面 / 面板，退无可退时不响应",
    ),
    OPEN_SETTINGS(
        id = "open_settings",
        label = "打开设置",
        category = ShortcutCategory.NAVIGATION,
        defaultBinding = ShortcutBinding(ShortcutKey.COMMA, ctrl = true),
        hint = "从任意页面回到主界面并打开设置",
    ),
    ;

    companion object {
        fun of(id: String): ShortcutAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 落盘时用来表示「**已解绑**」的哨兵值。
 *
 * 必须与「没有落盘记录（= 用默认绑定）」区分开：没有它的话，「清除绑定」会把用户
 * 打回默认键位 —— 用户按下「清除」是想**关掉**这个快捷键，结果它又回来了，
 * 这在设置页里是纯粹的错误行为。
 */
const val UNBOUND_SHORTCUT_MARKER: String = "unbound"

/**
 * 找出**互相冲突**的动作 id（同一个「修饰键 + 主键」被两个以上动作占用）。
 *
 * 冲突不会让程序出错（派发取声明顺序靠前的那个），但用户会遇到「按下去反应的不是我想的那个」，
 * 所以设置页要把这些行标出来。纯函数：入参就是 `AppModel.shortcutBindingsFlow` 的值。
 */
fun findShortcutConflicts(bindings: Map<String, ShortcutBinding?>): Set<String> =
    bindings.entries
        .filter { it.value != null }
        .groupBy({ it.value!! }, { it.key })
        .values
        .filter { it.size > 1 }
        .flatten()
        .toSet()
