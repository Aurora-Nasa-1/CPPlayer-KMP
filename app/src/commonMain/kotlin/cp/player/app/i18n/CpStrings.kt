package cp.player.app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale

/**
 * 应用文案的**唯一取值入口**。
 *
 * ### 为什么是自己这套，而不是 `compose-resources` 的 `Res.string`
 *
 * 选过 compose-resources，**做不到**（方案被否定的依据见 `docs/dev/I18N.md` §1）：
 * 换语言走的 `LocalComposeEnvironment` 在 1.12.1 里是
 * `internal val`（源码：`ResourceEnvironment.kt`），连 `ResourceEnvironment` 的构造函数
 * 都是 `internal constructor` —— 应用侧**没有任何**合规路径改变资源使用的语言，
 * 唯一的 public API 是只能取系统值的 `getSystemResourceEnvironment()`。
 * （`javap` 会把它们显示成 public class —— 那是 JVM 字节码层面，Kotlin 元数据里仍是
 * internal，两者别混为一谈。）
 *
 * 与其为了「官方」而牺牲「应用内切换语言」，这里自己管一份。
 * 代价与我们放弃的东西相比很小，而且刚好补上了 compose-resources 对本仓库的第二个
 * 短板：**本仓库约一半文案不在组合上下文里**
 * （`UiEvents.notify(...)`、`ScreenModel`、塞进返回栈的 `StartupScreen("…")`），
 * `stringResource()` 在那里根本调不了，而这里的 [CpStrings] 是个普通对象，哪儿都能读。
 *
 * ### 两份实现的完整性由编译器保证
 *
 * [CpStringsZh] / [CpStringsEn] 都实现同一个接口，**漏哪一条 Kotlin 编译不过** ——
 * 这比 XML 里缺 key 静默回落到另一种语言（要靠脚本查）可靠得多。
 *
 * ### 加文案的步骤
 *
 * 1. 在下面对应的分组接口里加成员（找不到合适的分组就新建一个，参考 §area 划分）；
 * 2. `CpStringsZh` / `CpStringsEn` 各写一份；
 * 3. 调用点读 [cpStrings]。
 */
interface CpStrings {
    val language: LanguageStrings
    val settings: SettingsStrings

    companion object {
        /** 简体中文实例。 */
        val zh: CpStrings get() = CpStringsZh

        /** 英文实例。 */
        val en: CpStrings get() = CpStringsEn

        /**
         * 按 [AppLanguage] 解析出实例。
         *
         * [AppLanguage.SYSTEM] 时读 Compose 的 `Locale.current`（Android 跟 Configuration、
         * 桌面跟 JVM 默认 locale），**命中英文才用英文，其余一律中文** ——
         * 中文是兜底语言（理由见 [AppLanguage] 的 KDoc）。
         */
        fun of(language: AppLanguage): CpStrings = when (language) {
            AppLanguage.ZH_HANS -> zh
            AppLanguage.ENGLISH -> en
            AppLanguage.SYSTEM -> if (Locale.current.language.equals("en", ignoreCase = true)) en else zh
        }
    }
}

/** 「标题 + 副标题」成对出现的文案（设置入口几乎都是这个形状）。 */
data class CpTextPair(
    val title: String,
    val subtitle: String,
)

// ---------------------------------------------------------------------------
// 各区域分组。按「谁在显示」划分，不按「数据从哪来」划分 ——
// 目的是让迁移时能一个屏幕一个屏幕地做，而不是一次改遍全局。
// ---------------------------------------------------------------------------

interface LanguageStrings {
    val screenTitle: String
    val optionSystem: String
    val optionSystemNote: String
    val optionZhHans: String
    val optionEnglish: String
    val current: String
    val applyNote: String
}

interface SettingsStrings {
    val screenTitle: String
    val groupGeneral: String
    val groupAccountProvider: String
    val groupConnectivity: String
    val groupOther: String
    val itemLanguage: CpTextPair
    val itemAppearance: CpTextPair
    val itemPlayback: CpTextPair
    val itemStorage: CpTextPair
    val itemShortcuts: CpTextPair
    val itemAccount: CpTextPair
    val itemProviders: CpTextPair
    val itemStreamOutput: CpTextPair
    val itemIntegration: CpTextPair
    val itemStandby: CpTextPair
    val itemAbout: CpTextPair
    val itemDiagnostics: CpTextPair
    val itemRenderTuning: CpTextPair
    val itemOnboarding: CpTextPair
}

// ---------------------------------------------------------------------------
// 组合侧接线
// ---------------------------------------------------------------------------

/**
 * 当前文案。
 *
 * 默认值给中文是为了让**未接Provide的场景**（预览、单测直接调某个 composable）
 * 也能渲染，而不是抛「composition local 未赋值」—— 那种报错离真正的原因太远。
 */
val LocalCpStrings = staticCompositionLocalOf { CpStrings.zh }

/** 在组合里读文案（重组驱动：语言一变，读取点自动重组合）。 */
@Composable
@ReadOnlyComposable
fun cpStrings(): CpStrings = LocalCpStrings.current

/**
 * 把语言注入整棵树。**全树只调用一次**（在 `AppTheme` 里）。
 *
 * 语言变化 => `strings` 变 => CompositionLocal 读取点失效 => 所有读过的地方重组合，
 * **不需要重启**。做不到即时更新的只有那些在组合外就把文案固化进状态的调用点，
 * 那种地方要存 lambda / key，别存现成的字符串。
 */
@Composable
fun ProvideCpStrings(
    language: AppLanguage,
    content: @Composable () -> Unit,
) {
    val strings = remember(language) { CpStrings.of(language) }
    CompositionLocalProvider(
        LocalCpStrings provides strings,
        content = content,
    )
}
