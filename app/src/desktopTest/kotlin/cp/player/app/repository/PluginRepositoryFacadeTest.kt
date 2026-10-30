package cp.player.app.repository

import cp.player.core.pluginrepo.InstalledRepositoryPlugin
import cp.player.core.pluginrepo.PluginArtifact
import cp.player.core.pluginrepo.PluginCatalog
import cp.player.core.pluginrepo.PluginInstallOutcome
import cp.player.core.pluginrepo.PluginInstallPhase
import cp.player.core.pluginrepo.PluginRepositoryIO
import cp.player.core.pluginrepo.PluginRepositoryService
import cp.player.core.pluginrepo.PluginTarget
import cp.player.core.pluginrepo.RepositoryPlugin
import cp.player.core.pluginrepo.RepositoryPluginKind
import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginRepositoryFacadeTest {
    private class MemorySettings : SettingsStorage {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?) = values[key] ?: default
        override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun contains(key: String) = key in values
        override fun clear() = values.clear()
    }

    @Test
    fun `only a newly completed installation notifies UI caches not staged or rejected installs`() {
        runBlocking {
            for (restartRequired in listOf(false, true)) {
                val artifact = PluginArtifact("any", runtime = "http", url = "https://example.org/p.zip",
                    sha256 = "a".repeat(64), bytes = 1)
                val plugin = RepositoryPlugin("test", RepositoryPluginKind.PROVIDER, "Test", "1.0.0", artifacts = listOf(artifact))
                val text = Json.encodeToString(PluginCatalog(name = "Test", plugins = listOf(plugin)))
                val io = object : PluginRepositoryIO {
                    override suspend fun fetchCatalog(url: String) = text
                    override suspend fun cachedCatalog(url: String): String? = null
                    override suspend fun cacheCatalog(url: String, text: String) = Unit
                    override suspend fun preparePackage(plugin: RepositoryPlugin, artifact: PluginArtifact,
                        onProgress: (PluginInstallPhase, Long) -> Unit) = "test.zip"
                    override suspend fun discardPackage(path: String) = Unit
                }
                var inventory = emptyList<InstalledRepositoryPlugin>()
                val service = PluginRepositoryService(MemorySettings(), io, PluginTarget("windows", "x64"), { inventory }) { p, _ ->
                    if (!restartRequired) inventory = listOf(InstalledRepositoryPlugin(p.key, p.version))
                    PluginInstallOutcome(restartRequired)
                }
                service.addSource("https://example.org/index.json")
                service.setNetworkEnabled(true)
                service.refresh()
                val notifications = mutableListOf<String>()
                val facade = PluginRepository(service) { notifications += it }
                facade.install(service.state.value.entries.single(), false)
                assertEquals(if (restartRequired) emptyList() else listOf("test"), notifications)
                service.setNetworkEnabled(false)
                assertFailsWith<IllegalArgumentException> { facade.install(service.state.value.entries.single(), false) }
                assertEquals(if (restartRequired) emptyList() else listOf("test"), notifications)
            }
        }
    }
}
