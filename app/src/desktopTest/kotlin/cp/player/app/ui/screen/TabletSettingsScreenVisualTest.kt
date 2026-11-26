package cp.player.app.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import cp.player.app.ui.navigation.RouteDisplay
import cp.player.app.ui.navigation.rememberPageStack
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.presentation.navigation.AppNavigator
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.component.LocalWindowChromeActive
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TabletSettingsScreenVisualTest {

    private class MockNavigator : AppNavigator {
        var popCalled = false
        override val depth: Int = 2
        override val currentKey: String? = "Settings"
        override fun push(route: AppRoute) {}
        override fun pushRoot(route: AppRoute) {}
        override fun replace(route: AppRoute) {}
        override fun replaceAll(route: AppRoute) {}
        override fun pop(): Boolean {
            popCalled = true
            return true
        }
        override fun popToRoot() {}
    }

    private fun render(width: Int, height: Int, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    CompositionLocalProvider(
                        LocalWindowChromeActive provides false,
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            content()
                        }
                    }
                }
            }
        }
        return try {
            scene.render().encodeToData()?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `tablet settings screen renders top bar with back button in wide layout`() {
        val mockNavigator = MockNavigator()
        val png = render(width = 900, height = 700) {
            CompositionLocalProvider(
                LocalAppNavigator provides mockNavigator,
            ) {
                RouteDisplay(rememberPageStack(SettingsScreen(embedded = false), "test-settings"))
            }
        }

        File("app/build/tablet-settings-screen.png").apply {
            parentFile?.mkdirs()
            writeBytes(png)
        }
        assertTrue(png.size > 10_000, "平板设置界面预览不应为空，实际 ${png.size} bytes")
    }
}
