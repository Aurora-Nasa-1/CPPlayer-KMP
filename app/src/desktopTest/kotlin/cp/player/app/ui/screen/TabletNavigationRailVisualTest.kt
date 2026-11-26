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
import androidx.compose.ui.layout.onSizeChanged
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
        var railWidthPx = 0
        val scene = ImageComposeScene(width = 700, height = 900, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    Row(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                    ) {
                        Box(Modifier.onSizeChanged { railWidthPx = it.width }) {
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
                        }
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

        // 回归护栏：rail 内部一旦再用 fillMaxWidth，自定义 Layout 会把 minWidth 读成整窗宽，
        // 左侧导航栏直接撑满 700px，内容区被挤没。density=1 时 M3 rail item token = 80dp。
        assertTrue(
            railWidthPx in 72..96,
            "平板导航栏宽度应约 80dp，实际 ${railWidthPx}px（density=1）",
        )
        File("build/tablet-navigation-rail.png").apply {
            parentFile?.mkdirs()
            writeBytes(png)
        }
        assertTrue(png.size > 5_000, "平板导航栏预览不应为空，实际 ${png.size} bytes")
    }
}
