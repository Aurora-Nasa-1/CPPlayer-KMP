package cp.player.app.ui.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 触觉反馈语义 —— 全平台统一的「手势词汇表」。
 *
 * 刻意按**语义**而不是按平台常量命名：调用方只表达「这是一次确认」，
 * 由平台层决定 Android 上对应哪个 `HapticFeedbackConstants`。
 * 这样新增一种反馈时不必在几十个调用点判断平台。
 */
enum class CpHaptic {
    /** 轻点：开关态变化（tab 切换、chip 选中、seek 拖动跨秒）。 */
    Tick,

    /** 确认：主要动作（播放 / 暂停、切歌）。 */
    Confirm,

    /** 成功：收藏这类带结果的反馈，比 [Confirm] 更重。 */
    Success,

    /** 拒绝 / 到头：边界触底。 */
    Reject,
}

/**
 * 触觉执行器。桌面端是空实现 —— JVM 拿不到任何系统震动能力，
 * 硬造一个 `Toolkit.beep()` 只会变成噪音，所以统一在平台层决定。
 */
fun interface CpHaptics {
    fun perform(kind: CpHaptic)
}

/**
 * 全局触觉执行器。未提供时是空实现，因此**任何地方都可以直接调用**
 * 而不必先判空 —— 触觉属于「锦上添花」，缺失时必须静默降级，不能抛异常。
 */
val LocalCpHaptics = staticCompositionLocalOf<CpHaptics> { CpHaptics { } }

/**
 * 创建当前平台的触觉执行器。
 *
 * Android：走 [android.view.View.performHapticFeedback]（尊重系统「触摸反馈」开关，
 * 且与系统其他触觉强度一致）；桌面：空实现。
 */
@Composable
expect fun rememberPlatformHaptics(): CpHaptics
