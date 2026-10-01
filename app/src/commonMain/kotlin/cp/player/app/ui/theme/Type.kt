package cp.player.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Material 3 Expressive 字号阶梯。
//
// ⚠️ 这里的字号 / 行高 / 字距 / 字重全部是 material3 1.11.0-alpha07 的**官方令牌真值**，
// 取自 `androidx.compose.material3.tokens.TypeScaleTokens` 的 <clinit>（javap 读出）。
// **不要凭记忆或对照文档改** —— 文档会漂，`titleLarge` 就是典型例子（见下）。
//
// 历史教训：本文件曾把 `titleLarge` / `titleMedium` / `titleSmall` / `labelLarge` /
// `labelMedium` 全部写成 SemiBold(600)。规范里它们分别是 Regular(400) / Medium(500)，
// 整体重了一到两档 ⇒ 标题与正文的字重对比被拉平，"层级"反而看不清。
//
// ⚠️ 特别容易搞反的一条：**`titleLarge` 是 Regular(400)**，不是 Medium 更不是 SemiBold。
// 它在 Expressive 里承担「大标题」角色，强调交给 [CpText.titleLargeEmphasized]。

/**
 * 标准字阶（对应 `TypeScaleTokens` 的非 Emphasized 档）。
 *
 * | 样式 | 字号 | 行高 | 字距 | 字重 |
 * |------|------|------|------|------|
 * | displayLarge | 57 | 64 | -0.2 | Regular |
 * | displayMedium / Small | 45 / 36 | 52 / 44 | 0 | Regular |
 * | headlineLarge / Medium / Small | 32 / 28 / 24 | 40 / 36 / 32 | 0 | Regular |
 * | **titleLarge** | 22 | 28 | **0** | **Regular** |
 * | titleMedium | 16 | 24 | 0.2 | Medium |
 * | titleSmall | 14 | 20 | 0.1 | Medium |
 * | bodyLarge | 16 | 24 | 0.5 | Regular |
 * | bodyMedium | 14 | 20 | 0.2 | Regular |
 * | bodySmall | 12 | 16 | 0.4 | Regular |
 * | labelLarge | 14 | 20 | 0.1 | Medium |
 * | labelMedium | 12 | 16 | 0.5 | Medium |
 * | labelSmall | 11 | 16 | 0.5 | Medium |
 */
val AppTypography: Typography = Typography(
    displayLarge = TextStyle(
        fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.2).sp,
        fontWeight = FontWeight.Normal,
    ),
    displayMedium = TextStyle(
        fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
    ),
    displaySmall = TextStyle(
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
    ),
    headlineLarge = TextStyle(
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
    ),
    // ↓ 关键：Regular(400)，不是 SemiBold。想要更重的标题请显式取 CpText.titleLargeEmphasized。
    titleLarge = TextStyle(
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp,
        fontWeight = FontWeight.Medium,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
        fontWeight = FontWeight.Medium,
    ),
)

/**
 * 给整套字阶套上同一个字体家族。
 *
 * **为什么不直接在 [AppTypography] 里写死 `fontFamily`**：字体得从 Compose 资源里读，
 * 而 `Font(Res.font.*)` 是 `@Composable`（桌面端还要异步加载字节），
 * 顶层的 `val AppTypography` 拿不到它。所以字阶保持"与字体无关"，
 * 由 `CpTheme` 在拿到字体后统一套一层（见 `Font.kt`）。
 */
internal fun Typography.withFontFamily(fontFamily: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = fontFamily),
    displayMedium = displayMedium.copy(fontFamily = fontFamily),
    displaySmall = displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = titleLarge.copy(fontFamily = fontFamily),
    titleMedium = titleMedium.copy(fontFamily = fontFamily),
    titleSmall = titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = bodySmall.copy(fontFamily = fontFamily),
    labelLarge = labelLarge.copy(fontFamily = fontFamily),
    labelMedium = labelMedium.copy(fontFamily = fontFamily),
    labelSmall = labelSmall.copy(fontFamily = fontFamily),
)

/**
 * M3E 的「强调」字阶（`*Emphasized` 档）。
 *
 * **为什么单独放一层而不是改 [AppTypography]**：`Typography` 的槽位是全局共享的，
 * 直接把它加重会连带影响所有取该样式的组件（包括不该被强调的地方）。规范的做法是
 * 基础字阶保持 Regular/Medium，**需要强调的地方显式取这里**。
 *
 * 字重取自 `TypeScaleTokens` 的 `*EmphasizedWeight`（javap 确认）：
 * - `body*Emphasized` / `display*Emphasized` / `headline*Emphasized` / `titleLargeEmphasized` → **Medium(500)**
 * - `titleMediumEmphasized` / `titleSmallEmphasized` / `label*Emphasized` → **Bold(700)**
 *
 * ⚠️ Emphasized 档的字距与基础档**不同**（例如 bodyLarge 0.5 → bodyLargeEmphasized 0.15），
 * 不是单纯换字重。
 *
 * ⚠️ 这些成员是 `@Composable` 属性（要从 `MaterialTheme.typography` 派生，才能继承主题字体），
 * **只能在 composable 里取**。放进 `remember {}` / 点击回调 / 普通函数里会编译不过。
 */
object CpText {

    // ↓ 全部从 MaterialTheme.typography 派生，而不是把字号 / 行高再抄一遍。两个原因：
    //   ① 字阶真值只有一处（上面的 AppTypography），以后调字号不会漏改这里；
    //   ② 自动继承主题字体 —— 写死 TextStyle 的话这些强调样式会漏掉 fontFamily，
    //      表现是「同一个页面里两套字体」。
    //   代价是它们变成 @Composable（要读 MaterialTheme.typography），只能在 composable 里取。

    val titleLargeEmphasized: TextStyle
        @Composable
        get() = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium)

    val titleMediumEmphasized: TextStyle
        @Composable
        get() = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)

    val titleSmallEmphasized: TextStyle
        @Composable
        get() = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)

    val labelLargeEmphasized: TextStyle
        @Composable
        get() = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)

    // ↓ Emphasized 档的字距与基础档不同，不是单纯换字重 —— 必须显式覆盖。
    val bodyLargeEmphasized: TextStyle
        @Composable
        get() = MaterialTheme.typography.bodyLarge.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.15.sp,
        )

    val bodyMediumEmphasized: TextStyle
        @Composable
        get() = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.25.sp,
        )
}

/**
 * 等宽数字（tabular figures）样式。
 *
 * **播放器的刚需**：比例数字下 `0:09` → `0:10` 的宽度会变，进度时间行会肉眼可见地
 * "抽"一下。开启 `tnum` 后每个数字占同样宽度，跳动就消失了。
 *
 * 用法：`Text(formatTimeMs(ms), style = CpText.timecode(MaterialTheme.typography.labelMedium))`
 */
object CpTypography {
    /**
     * 给任意样式开启等宽数字。
     *
     * 用 `fontFeatureSettings` 而不是换字体：只要字体带 `tnum` 特性就生效，
     * 系统默认字体（含 CJK 回退）在 Android 与桌面上都支持。
     */
    fun timecode(base: TextStyle): TextStyle =
        base.copy(fontFeatureSettings = "tnum")
}
