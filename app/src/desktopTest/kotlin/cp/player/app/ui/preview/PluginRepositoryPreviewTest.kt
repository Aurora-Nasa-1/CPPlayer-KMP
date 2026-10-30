package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import cp.player.app.i18n.CpStringsEn
import cp.player.app.i18n.CpStringsZh
import cp.player.app.i18n.LocalCpStrings
import cp.player.app.ui.screen.PluginRepositoryContent
import cp.player.app.ui.screen.PluginRepositoryTab
import cp.player.app.ui.screen.filterRepositoryEntries
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.pluginrepo.InstalledRepositoryPlugin
import cp.player.core.pluginrepo.ManagedPluginInstall
import cp.player.core.pluginrepo.PluginArtifact
import cp.player.core.pluginrepo.PluginCatalogEntry
import cp.player.core.pluginrepo.PluginRepositorySource
import cp.player.core.pluginrepo.PluginRepositoryState
import cp.player.core.pluginrepo.RepositoryPlugin
import cp.player.core.pluginrepo.RepositoryPluginKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginRepositoryPreviewTest {
    private val source = PluginRepositorySource("https://example.org/catalog/index.json", "CPPlayer Plugins")
    private val artifact = PluginArtifact("windows", "x64", "jni", "https://example.org/package.zip", "a".repeat(64), 123456)
    private val plugin = RepositoryPlugin("test-music", RepositoryPluginKind.PROVIDER,
        "Community music provider / 社区音源", "1.2.0", "Browse music and playlists / 音乐与歌单", "Community",
        changelog = "Fix search and login.", artifacts = listOf(artifact))
    private val installed = InstalledRepositoryPlugin(plugin.key, "1.0.0", name = plugin.name)
    private val managed = ManagedPluginInstall(plugin.key, source.url, "1.0.0", sha256 = artifact.sha256)
    private val state = PluginRepositoryState(
        sources = listOf(source),
        entries = listOf(
            PluginCatalogEntry(source, plugin, artifact, null, installed, managed),
            PluginCatalogEntry(source, plugin.copy(id = "other", name = "歌词扩展 / Lyrics extension",
                kind = RepositoryPluginKind.LYRICS), artifact, "No package for this platform"),
        ),
        installedPlugins = listOf(installed),
        managedInstalls = listOf(managed),
        networkEnabled = true,
    )

    @Test
    fun `search type and update filters are local and deterministic`() {
        assertEquals(2, filterRepositoryEntries(state, PluginRepositoryTab.BROWSE, "COMMUNITY", 0).size)
        assertEquals(1, filterRepositoryEntries(state, PluginRepositoryTab.BROWSE, "test-music", 0).size)
        assertEquals(1, filterRepositoryEntries(state, PluginRepositoryTab.UPDATES, "", 0).size)
        assertEquals(1, filterRepositoryEntries(state, PluginRepositoryTab.BROWSE, "", 2).size)
        assertTrue(filterRepositoryEntries(state, PluginRepositoryTab.BROWSE, "absent", 0).isEmpty())
    }

    @Test
    fun `render every tab in Chinese English light dark and narrow layouts`() {
        val directory = File("../artifacts/plugin-repository-preview").apply { mkdirs() }
        for (width in listOf(412, 1100)) for (english in listOf(false, true)) {
            for (tab in PluginRepositoryTab.entries) {
                val scene = ImageComposeScene(width, 1000, density = Density(1f)) {
                    CpTheme(themeMode = if (english) ThemeMode.DARK else ThemeMode.LIGHT) {
                        CompositionLocalProvider(LocalCpStrings provides if (english) CpStringsEn else CpStringsZh) {
                            PluginRepositoryContent(
                                state, initialTab = tab,
                                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                            )
                        }
                    }
                }
                try {
                    val data = scene.render().encodeToData() ?: error("Cannot encode preview")
                    val file = File(directory, "${tab.name.lowercase()}-$width-${if (english) "en-dark" else "zh-light"}.png")
                    file.writeBytes(data.bytes)
                    assertTrue(file.length() > 5000, "Preview should not be blank: ${file.name}")
                } finally { scene.close() }
            }
        }
    }
}
