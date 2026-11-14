package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import cp.player.app.ui.component.LocalEmbeddedInPane
import cp.player.app.ui.component.LocalWindowChromeActive
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.lyrics.BuiltinLyricsSourceIds
import cp.player.core.lyrics.LyricsCapability
import cp.player.core.lyrics.LyricsSourceEntry
import cp.player.core.pluginrepo.InstalledRepositoryPlugin
import cp.player.core.pluginrepo.ManagedPluginInstall
import cp.player.core.pluginrepo.PluginArtifact
import cp.player.core.pluginrepo.PluginCatalogEntry
import cp.player.core.pluginrepo.PluginRepositorySource
import cp.player.core.pluginrepo.PluginRepositoryState
import cp.player.core.pluginrepo.RepositoryPlugin
import cp.player.core.pluginrepo.RepositoryPluginKind
import cp.player.core.provider.BackendProvider
import cp.player.core.provider.ProviderSettingItemSchema
import cp.player.core.provider.ProviderType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 合并后的插件中心离屏版式探针。
 *
 * 覆盖两件编译与纯测量看不到的事：
 * 1. 「已安装」首屏在桌面宽栏是否真的分成双列，手机是否仍是单列；
 * 2. M3 Expressive 切换器在窄屏是否把四项压成可读的图标 + 短标签。
 */
class PluginHubPreviewTest {

    private fun render(
        width: Int,
        height: Int,
        themeMode: ThemeMode = ThemeMode.DARK,
        view: PluginHubView,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = themeMode) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    CompositionLocalProvider(
                        LocalEmbeddedInPane provides false,
                        LocalWindowChromeActive provides false,
                    ) {
                        PluginHubContent(
                            state = sampleState(),
                            modifier = Modifier.fillMaxSize(),
                            initialView = view,
                        )
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
    fun `installed first screen renders on phone and desktop`() {
        val phone = render(390, 844, view = PluginHubView.INSTALLED)
        val desktop = render(1100, 900, themeMode = ThemeMode.LIGHT, view = PluginHubView.INSTALLED)
        write("build/plugin-hub-installed-phone.png", phone)
        write("build/plugin-hub-installed-desktop.png", desktop)
        assertTrue(phone.size > 20_000, "手机端已安装预览不应为空，实际 ${phone.size} bytes")
        assertTrue(desktop.size > 20_000, "桌面端已安装预览不应为空，实际 ${desktop.size} bytes")
    }

    @Test
    fun `discover and repositories render with expressive switcher`() {
        val phone = render(390, 844, view = PluginHubView.DISCOVER)
        val desktop = render(1100, 900, themeMode = ThemeMode.LIGHT, view = PluginHubView.REPOSITORIES)
        write("build/plugin-hub-discover-phone.png", phone)
        write("build/plugin-hub-repositories-desktop.png", desktop)
        assertTrue(phone.size > 15_000, "手机端发现页预览不应为空，实际 ${phone.size} bytes")
        assertTrue(desktop.size > 15_000, "桌面端仓库页预览不应为空，实际 ${desktop.size} bytes")
    }

    private fun write(path: String, bytes: ByteArray) {
        File(path).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun sampleState(): PluginHubUiState {
        val source = PluginRepositorySource(
            url = "https://plugins.example.com/catalog.json",
            name = "Community catalog",
            enabled = true,
        )
        val providerPlugin = RepositoryPlugin(
            id = "demo-provider",
            kind = RepositoryPluginKind.PROVIDER,
            name = "Demo Provider",
            version = "2.1.0",
            description = "A demo provider used by the offscreen layout probe.",
            author = "Example Audio",
            changelog = "Adds lossless search and a new account switcher.",
            artifacts = listOf(
                PluginArtifact(
                    platform = "windows",
                    arch = "x64",
                    runtime = "js",
                    url = "https://plugins.example.com/demo-provider.zip",
                    sha256 = "0123456789abcdef",
                    bytes = 1_048_576,
                ),
            ),
        )
        val lyricsPlugin = RepositoryPlugin(
            id = "demo-lyrics",
            kind = RepositoryPluginKind.LYRICS,
            name = "Demo Lyrics",
            version = "0.3.1",
            versionCode = 31,
            description = "Word-by-word lyrics from a community source.",
            author = "Lyrics Lab",
            artifacts = listOf(
                PluginArtifact(
                    platform = "any",
                    arch = "any",
                    runtime = "rhino-js",
                    url = "https://plugins.example.com/demo-lyrics.zip",
                    sha256 = "fedcba9876543210",
                    bytes = 524_288,
                ),
            ),
        )
        val installedLyrics = InstalledRepositoryPlugin(
            key = "lyrics:demo-lyrics",
            version = "0.2.0",
            versionCode = 20,
            name = "Demo Lyrics",
        )
        return PluginHubUiState(
            repository = PluginRepositoryState(
                sources = listOf(source),
                entries = listOf(
                    PluginCatalogEntry(
                        source = source,
                        plugin = providerPlugin,
                        artifact = providerPlugin.artifacts.first(),
                        incompatibility = null,
                    ),
                    PluginCatalogEntry(
                        source = source,
                        plugin = lyricsPlugin,
                        artifact = lyricsPlugin.artifacts.first(),
                        incompatibility = null,
                        installed = installedLyrics,
                        managed = ManagedPluginInstall(
                            key = installedLyrics.key,
                            sourceUrl = source.url,
                            version = installedLyrics.version,
                            versionCode = installedLyrics.versionCode,
                            sha256 = "fedcba9876543210",
                        ),
                    ),
                ),
                networkEnabled = true,
                autoCheck = true,
                installedPlugins = listOf(
                    installedLyrics,
                    InstalledRepositoryPlugin(
                        key = "provider:legacy",
                        version = "0.9.0",
                        name = "Legacy Provider",
                    ),
                ),
                managedInstalls = listOf(
                    ManagedPluginInstall(
                        key = "lyrics:demo-lyrics",
                        sourceUrl = source.url,
                        version = installedLyrics.version,
                        versionCode = installedLyrics.versionCode,
                        sha256 = "fedcba9876543210",
                    ),
                    ManagedPluginInstall(
                        key = "provider:legacy",
                        sourceUrl = source.url,
                        version = "0.9.0",
                        sha256 = "1111111111111111",
                        pendingRestart = true,
                    ),
                ),
            ),
            providers = listOf(
                FakeProvider(
                    id = "netease",
                    name = "网易云音乐",
                    version = "1.4.2",
                    settingsSchema = listOf(
                        ProviderSettingItemSchema(
                            key = "api",
                            label = "API 地址",
                            type = "string",
                            default = "https://example.com",
                        ),
                    ),
                ),
                FakeProvider(
                    id = "local-demo",
                    name = "本地示例音源",
                    version = "0.8.0",
                ),
            ),
            activeProviderId = "netease",
            lyricsSources = listOf(
                LyricsSourceEntry(
                    id = BuiltinLyricsSourceIds.AMLL,
                    name = "amll",
                    localizeName = true,
                    bundled = true,
                    removable = false,
                    capabilities = setOf(LyricsCapability.GET_LYRICS, LyricsCapability.SEARCH_SONGS),
                    enabled = true,
                ),
                LyricsSourceEntry(
                    id = "demo-lyrics",
                    name = "Demo Lyrics",
                    localizeName = false,
                    bundled = false,
                    removable = true,
                    capabilities = setOf(
                        LyricsCapability.GET_LYRICS,
                        LyricsCapability.SEARCH_SONGS,
                        LyricsCapability.SEARCH_COVERS,
                    ),
                    enabled = true,
                    author = "Lyrics Lab",
                    version = "0.3.1",
                ),
            ),
            providerAutoCheck = true,
        )
    }

    private class FakeProvider(
        override val id: String,
        override val name: String,
        override val version: String,
        override val type: ProviderType = ProviderType.SCRIPT,
        override val updateUrl: String? = null,
        override val targetAppPackage: String? = null,
        override val capabilities: List<String>? = listOf("search", "play", "lyrics"),
        override val settingsSchema: List<ProviderSettingItemSchema>? = null,
    ) : BackendProvider
}
