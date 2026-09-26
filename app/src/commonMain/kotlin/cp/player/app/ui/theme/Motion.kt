package cp.player.app.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/**
 * M3 Expressive 动效令牌。
 *
 * **为什么需要这一层**：`MotionScheme` 把动画分成两组，语义完全不同，混用就会「看着不对」——
 *
 * - **spatial**（位移 / 缩放 / 尺寸）：可以带**回弹**，这正是 Expressive 观感的主要来源。
 * - **effects**（颜色 / 透明度）：**绝不能回弹**，否则淡入淡出会看到颜色来回抖。
 *
 * 直接在各调用点手写 `spring(dampingRatio = …)` 时，很容易把回弹顺手用到淡出上；
 * 统一从这里取，就不会混。三档速度（fast / default / slow）分别对应
 * 「小控件即时反馈」/「常规状态切换」/「整页级转场」。
 *
 * ⚠️ 这些 spec 由 `MaterialExpressiveTheme` 注入（见 [CpTheme]）。若主题退化成普通
 * `MaterialTheme`，拿到的会是默认的 standard scheme，动效会变「钝」但不报错。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object CpMotion {

    /** 常规位移：大多数位置 / 尺寸变化的默认值。 */
    @Composable
    @ReadOnlyComposable
    fun <T> spatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

    /** 快位移：图标、指示器等小控件的即时反馈。 */
    @Composable
    @ReadOnlyComposable
    fun <T> spatialFast(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastSpatialSpec()

    /** 慢位移：整页级转场、大面积元素。 */
    @Composable
    @ReadOnlyComposable
    fun <T> spatialSlow(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowSpatialSpec()

    /** 常规效果：颜色 / 透明度。**不带回弹**。 */
    @Composable
    @ReadOnlyComposable
    fun <T> effects(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()

    /** 快效果：hover / press 之类的高频反馈。 */
    @Composable
    @ReadOnlyComposable
    fun <T> effectsFast(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastEffectsSpec()

    /** 慢效果：主题换色之类的大面积颜色过渡。 */
    @Composable
    @ReadOnlyComposable
    fun <T> effectsSlow(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowEffectsSpec()
}
