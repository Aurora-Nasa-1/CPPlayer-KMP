package cp.player.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Material 3 Expressive 静态回退色板。
// 使用蓝紫主色、青绿辅助色和珊瑚强调色，让内容层级不依赖单一色相。
//
// ⚠️ **角色集合必须与动态取色路径一致**。materialkolor / 平台 Monet 会给出全套 M3 角色
// （含 surfaceContainerLowest..Highest）；这里若少填，静态回退与动态取色就会"同一套 UI
// 两种观感"，而且缺失的容器色阶会退到 lightColorScheme() 的默认灰紫。
//
// 历史教训：本文件原先**一个 surfaceContainer 角色都没填**，导致浅色下所有容器色阶
// 对比不足 ⇒ 卡片边界只能靠阴影/描边补，这才是"阴影用得多"和"纯黑模式必须靠
// bentoOutline() 描边"的同一个根因。层级应当主要靠**色阶**，不是靠描边。

val PrimaryLight = Color(0xFF4F55A5)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFE1E0FF)
val OnPrimaryContainerLight = Color(0xFF15175C)

val SecondaryLight = Color(0xFF006A68)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFF9CF1EE)
val OnSecondaryContainerLight = Color(0xFF00201F)

val TertiaryLight = Color(0xFF9A4529)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFFFDBCF)
val OnTertiaryContainerLight = Color(0xFF3A0B00)

val ErrorLight = Color(0xFFB3261E)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFF9DEDC)
val OnErrorContainerLight = Color(0xFF410E0B)

val BackgroundLight = Color(0xFFFCF8FC)
val OnBackgroundLight = Color(0xFF1C1B20)
val SurfaceLight = Color(0xFFFCF8FC)
val OnSurfaceLight = Color(0xFF1C1B20)
val SurfaceVariantLight = Color(0xFFE5E1EC)
val OnSurfaceVariantLight = Color(0xFF47464F)
val OutlineLight = Color(0xFF787680)
val OutlineVariantLight = Color(0xFFC9C5D0)

// —— 容器色阶（浅色）——
// 与 SurfaceLight(#FCF8FC) / SurfaceVariantLight(#E5E1EC) 同一色相，逐级加深约 4–6 点亮度。
// 五级阶梯是 M3 层级体系的主力：页面用 surface、卡片用 Low、卡片内嵌用 container、
// 浮起物用 High、最高对比用 Highest。**不要用阴影代替它。**
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF7F2FA)
val SurfaceContainerLight = Color(0xFFF1ECF4)
val SurfaceContainerHighLight = Color(0xFFEBE6EF)
val SurfaceContainerHighestLight = Color(0xFFE5E1EC)

val InverseSurfaceLight = Color(0xFF313034)
val InverseOnSurfaceLight = Color(0xFFF4EFF4)
val InversePrimaryLight = Color(0xFFC1C1FF)
val SurfaceDimLight = Color(0xFFDCD8E2)
val SurfaceBrightLight = Color(0xFFFCF8FC)
val ScrimLight = Color(0xFF000000)

// —— Fixed 角色（不随明暗模式反转，给分类图标/固定强调位使用）——
val PrimaryFixed = Color(0xFFE1E0FF)
val OnPrimaryFixed = Color(0xFF15175C)
val PrimaryFixedDim = Color(0xFFC1C1FF)
val OnPrimaryFixedVariant = Color(0xFF383B8C)

val SecondaryFixed = Color(0xFF9CF1EE)
val OnSecondaryFixed = Color(0xFF00201F)
val SecondaryFixedDim = Color(0xFF80D5D2)
val OnSecondaryFixedVariant = Color(0xFF00504E)

val TertiaryFixed = Color(0xFFFFDBCF)
val OnTertiaryFixed = Color(0xFF3A0B00)
val TertiaryFixedDim = Color(0xFFFFB59D)
val OnTertiaryFixedVariant = Color(0xFF7B2E15)

val PrimaryDark = Color(0xFFC1C1FF)
val OnPrimaryDark = Color(0xFF20216F)
val PrimaryContainerDark = Color(0xFF383B8C)
val OnPrimaryContainerDark = Color(0xFFE1E0FF)

val SecondaryDark = Color(0xFF80D5D2)
val OnSecondaryDark = Color(0xFF003736)
val SecondaryContainerDark = Color(0xFF00504E)
val OnSecondaryContainerDark = Color(0xFF9CF1EE)

val TertiaryDark = Color(0xFFFFB59D)
val OnTertiaryDark = Color(0xFF5C1905)
val TertiaryContainerDark = Color(0xFF7B2E15)
val OnTertiaryContainerDark = Color(0xFFFFDBCF)

val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)
val ErrorContainerDark = Color(0xFF8C1D18)
val OnErrorContainerDark = Color(0xFFF9DEDC)

val BackgroundDark = Color(0xFF131318)
val OnBackgroundDark = Color(0xFFE5E1E9)
val SurfaceDark = Color(0xFF131318)
val OnSurfaceDark = Color(0xFFE5E1E9)
val SurfaceVariantDark = Color(0xFF47464F)
val OnSurfaceVariantDark = Color(0xFFC9C5D0)
val OutlineDark = Color(0xFF928F99)
val OutlineVariantDark = Color(0xFF47464F)

// —— 容器色阶（深色）——
// 与 SurfaceDark(#131318) 同色相，逐级提亮约 4–5 点。
// ⚠️ 纯黑模式（见 `Theme.withPureBlackSurfaces`）**只压最低的两级**，
// 保留 Low..Highest 这几档极暗灰 —— 否则卡片与背景同色、层级只剩描边一种手段。
val SurfaceContainerLowestDark = Color(0xFF0E0E13)
val SurfaceContainerLowDark = Color(0xFF1B1B21)
val SurfaceContainerDark = Color(0xFF1F1F25)
val SurfaceContainerHighDark = Color(0xFF2A2A30)
val SurfaceContainerHighestDark = Color(0xFF35343B)

val InverseSurfaceDark = Color(0xFFE5E1E9)
val InverseOnSurfaceDark = Color(0xFF313034)
val InversePrimaryDark = Color(0xFF4F55A5)
val SurfaceDimDark = Color(0xFF131318)
val SurfaceBrightDark = Color(0xFF393840)
val ScrimDark = Color(0xFF000000)

val LightColors: ColorScheme = lightColorScheme(
    primary = PrimaryLight, onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight, onPrimaryContainer = OnPrimaryContainerLight,
    inversePrimary = InversePrimaryLight,
    secondary = SecondaryLight, onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight, onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight, onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight, onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight, onError = OnErrorLight,
    errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
    background = BackgroundLight, onBackground = OnBackgroundLight,
    surface = SurfaceLight, onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight, onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight, outlineVariant = OutlineVariantLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    inverseSurface = InverseSurfaceLight, inverseOnSurface = InverseOnSurfaceLight,
    scrim = ScrimLight,
    primaryFixed = PrimaryFixed, onPrimaryFixed = OnPrimaryFixed,
    primaryFixedDim = PrimaryFixedDim, onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed, onSecondaryFixed = OnSecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim, onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed, onTertiaryFixed = OnTertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim, onTertiaryFixedVariant = OnTertiaryFixedVariant,
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = PrimaryDark, onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark, onPrimaryContainer = OnPrimaryContainerDark,
    inversePrimary = InversePrimaryDark,
    secondary = SecondaryDark, onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark, onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark, onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark, onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark, onError = OnErrorDark,
    errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark, onBackground = OnBackgroundDark,
    surface = SurfaceDark, onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark, onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark, outlineVariant = OutlineVariantDark,
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    inverseSurface = InverseSurfaceDark, inverseOnSurface = InverseOnSurfaceDark,
    scrim = ScrimDark,
    primaryFixed = PrimaryFixed, onPrimaryFixed = OnPrimaryFixed,
    primaryFixedDim = PrimaryFixedDim, onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed, onSecondaryFixed = OnSecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim, onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed, onTertiaryFixed = OnTertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim, onTertiaryFixedVariant = OnTertiaryFixedVariant,
)

/**
 * M3 扩展语义状态色板（Success / Warning）。
 *
 * 浅/深色分别按 Tonal 算法与 WCAG AA（≥ 4.5:1）校准，避免页面直接硬编码
 * `Color(0xFF4CAF50)` / `Color(0xFFFF9800)` 导致浅色模式下对比度跌破 3:1。
 */
@Immutable
data class CpSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightSemanticColors = CpSemanticColors(
    success = Color(0xFF1B6E2D),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFD2F5D7),
    onSuccessContainer = Color(0xFF002107),
    warning = Color(0xFF8A5000),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDFB8),
    onWarningContainer = Color(0xFF2C1600),
)

private val DarkSemanticColors = CpSemanticColors(
    success = Color(0xFF81D88B),
    onSuccess = Color(0xFF00390F),
    successContainer = Color(0xFF00531A),
    onSuccessContainer = Color(0xFFB8F5BE),
    warning = Color(0xFFFFB95D),
    onWarning = Color(0xFF492900),
    warningContainer = Color(0xFF693C00),
    onWarningContainer = Color(0xFFFFDFB8),
)

/** 根据当前已解析的明暗主题返回合规的语义状态色板。 */
@Composable
fun cpSemanticColors(): CpSemanticColors =
    if (LocalIsDarkTheme.current) DarkSemanticColors else LightSemanticColors
