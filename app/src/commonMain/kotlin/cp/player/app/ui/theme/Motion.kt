package cp.player.app.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

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

    /**
     * 播放页展开 / 收起的**基准节拍**（毫秒）。
     *
     * 沉浸态是一组**必须同拍**的动画，不是一个独立动画：
     *
     * - `MainScreen`：播放页覆盖层的淡入 / 淡出（AnimatedContent）；
     * - `App`：窗口顶栏占位高度的涨落（背景因此铺到 / 退出窗口顶边）；
     * - `DesktopTitleBar`：标题栏除窗口钮外全部内容的淡出 / 淡入。
     *
     * 三处钉在同一个数上，读起来才是「一次交接」：顶栏让位的同时页面长出来。
     * 各自用各自习惯的时长（第一版：顶栏 200ms、背景 300ms、页面 400ms）就会出现
     * 「中间有一段顶栏是空的，页面才姗姗来迟」的时序错位 —— 这正是用户点名的那处。
     * ⚠️ 改这里之前先读上面三个调用点的注释。
     */
    const val PlayerExpandMs = 400

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

    /**
     * M3 Expressive Shared Axis (X 轴横向位移) 页面切换转场动效。
     *
     * 遵循 Material Design 3 / M3 Expressive 标准规范：
     * - 空间轴 (Spatial): 页面滑动约 25% 距离，采用带弹性且阻尼自然的 Expressive spring (defaultSpatialSpec)
     * - 效果轴 (Effects): 同步配合透明度进出淡化，采用严禁回弹的 effectsSpec (defaultEffectsSpec / fastEffectsSpec)
     *
     * @param isPop 是否为出栈 (Pop) 返回操作；false 为进栈 (Push) 前进操作
     */
    @Composable
    fun sharedAxisX(isPop: Boolean): ContentTransform {
        val spatialSpec: FiniteAnimationSpec<IntOffset> = spatial()
        val enterEffectsSpec: FiniteAnimationSpec<Float> = effects()
        val exitEffectsSpec: FiniteAnimationSpec<Float> = effectsFast()
        return if (isPop) {
            (slideInHorizontally(spatialSpec) { -(it * 0.25f).roundToInt() } + fadeIn(enterEffectsSpec)) togetherWith
                (slideOutHorizontally(spatialSpec) { (it * 0.25f).roundToInt() } + fadeOut(exitEffectsSpec))
        } else {
            (slideInHorizontally(spatialSpec) { (it * 0.25f).roundToInt() } + fadeIn(enterEffectsSpec)) togetherWith
                (slideOutHorizontally(spatialSpec) { -(it * 0.25f).roundToInt() } + fadeOut(exitEffectsSpec))
        }
    }
}
