package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TabletNavigationRailVisualTest {
    @Test
    fun `medium width navigation rail keeps labels and content readable`() {
        val scene = ImageComposeScene(width = 700, height = 900, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    Row(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                    ) {
                        AppNavigationRail(
                            tabs = listOf(
                                TabItem("home", MainScreen(), { it.tabHome }, Icons.Filled.Home, Icons.Outlined.Home),
                                TabItem(
                                    "search",
                                    MainScreen(),
                                    { it.tabSearch },
                                    Icons.Filled.Search,
                                    Icons.Outlined.Search,
                                ),
                                TabItem(
                                    "library",
                                    MainScreen(),
                                    { it.tabLibrary },
                                    Icons.Filled.LibraryMusic,
                                    Icons.Outlined.LibraryMusic,
                                ),
                            ),
                            selectedTabKey = "library",
                            onSelect = {},
                        )
                        Box(
                            Modifier.fillMaxHeight()
                                .weight(1f)
                                .padding(20.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow),
                        )
                    }
                }
            }
        }
        val png = try {
            scene.render().encodeToData()?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }

        File("build/tablet-navigation-rail.png").apply {
            parentFile?.mkdirs()
            writeBytes(png)
        }
        assertTrue(png.size > 5_000, "平板导航栏预览不应为空，实际 ${png.size} bytes")
    }
}
