package cp.player.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun supportsDynamicColor(): Boolean = false

@Composable
actual fun dynamicColorScheme(dark: Boolean): ColorScheme? = null