package cp.player.app.ui.feedback

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Android 触觉执行器。
 *
 * 用 [View.performHapticFeedback] 而不是直接拿 `Vibrator`：
 * 1. 它尊重系统的「触摸反馈 / 触感振动」开关，用户关掉后这里也自动静音；
 * 2. 强度由系统统一调度，不会比同设备上的其他应用更震；
 * 3. 不需要 VIBRATE 权限。
 *
 * [LocalView] 在 `setContent` 的组合里拿到的就是承载界面的 ComposeView，
 * 也就是当前 Activity 的视图 —— 正是 performHapticFeedback 需要的那个 View。
 */
@Composable
actual fun rememberPlatformHaptics(): CpHaptics {
    val view = LocalView.current
    return remember(view) { CpHaptics { kind -> view.performHapticFeedback(constantsFor(kind)) } }
}

/**
 * 语义 → 平台常量。
 *
 * `CONFIRM` / `REJECT` / `CLOCK_TICK` 都是 API 30 才加入的常量，而本工程 minSdk = 29，
 * 所以 Android 10 上必须回退到老常量。这里的常量是 `static final int`，
 * javac 会把字面量内联进字节码，因此即使分支没被守卫到也不会抛 NoSuchFieldError ——
 * 守卫只是为了在老系统上拿到**正确**的观感，而不是为了防崩溃。
 */
private fun constantsFor(kind: CpHaptic): Int {
    val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    return when (kind) {
        CpHaptic.Tick ->
            if (modern) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.VIRTUAL_KEY
        CpHaptic.Confirm ->
            if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        CpHaptic.Success ->
            if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        CpHaptic.Reject ->
            if (modern) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }
}
