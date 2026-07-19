package cp.player.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Material 3 Expressive 静态回退色板。
// 使用蓝紫主色、青绿辅助色和珊瑚强调色，让内容层级不依赖单一色相。

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

val LightColors: ColorScheme = lightColorScheme(
    primary = PrimaryLight, onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight, onPrimaryContainer = OnPrimaryContainerLight,
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
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = PrimaryDark, onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark, onPrimaryContainer = OnPrimaryContainerDark,
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
)
