package cp.player.core.pluginrepo

import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginRepositoryTest {
    private class MemorySettings : SettingsStorage {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?) = values[key] ?: default
        override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun contains(key: String) = key in values
        override fun clear() = values.clear()
    }

    private class MemoryIO : PluginRepositoryIO {
        val responses = mutableMapOf<String, String>()
        val caches = mutableMapOf<String, String>()
        var fetches = 0
        var downloads = 0
        var discarded = 0
        var failure: Exception? = null
        var downloadGate: CompletableDeferred<Unit>? = null
        val downloadStarted = CompletableDeferred<Unit>()
        override suspend fun fetchCatalog(url: String): String {
            fetches++
            failure?.let { throw it }
            return responses.getValue(url)
        }
        override suspend fun cachedCatalog(url: String) = caches[url]
        override suspend fun cacheCatalog(url: String, text: String) { caches[url] = text }
        override suspend fun preparePackage(
            plugin: RepositoryPlugin,
            artifact: PluginArtifact,
            onProgress: (PluginInstallPhase, Long) -> Unit,
        ): String {
            downloads++
            downloadStarted.complete(Unit)
            downloadGate?.await()
            onProgress(PluginInstallPhase.VERIFYING, artifact.bytes)
            return "verified-package.zip"
        }
        override suspend fun discardPackage(path: String) { discarded++ }
    }

    private val source = "https://example.org/index.json"
    private val artifact = PluginArtifact("any", runtime = "http", url = "https://example.org/demo.zip", sha256 = "a".repeat(64), bytes = 100)
    private val plugin = RepositoryPlugin("demo", RepositoryPluginKind.PROVIDER, "Demo", "1.2.0", artifacts = listOf(artifact))
    private fun catalog(plugin: RepositoryPlugin = this.plugin) =
        Json.encodeToString(PluginCatalog(name = "Example", plugins = listOf(plugin)))

    @Test
    fun `semver handles numeric prereleases build metadata and very large components`() {
        assertTrue(PluginVersion.compare("1.10.0", "1.9.0")!! > 0)
        assertTrue(PluginVersion.compare("1.0.0-beta.10", "1.0.0-beta.2")!! > 0)
        assertTrue(PluginVersion.compare("1.0.0", "1.0.0-rc.1")!! > 0)
        assertEquals(0, PluginVersion.compare("1.0.0+abc", "1.0.0+def"))
        assertTrue(PluginVersion.compare("999999999999999999999.0.0", "12.0.0")!! > 0)
        listOf("latest", "1.0", "01.0.0", "1.0.0-beta.01").forEach { assertFalse(PluginVersion.isValid(it)) }
    }

    @Test
    fun `catalog rejects unsafe ids urls sizes versions and duplicate identities`() {
        assertNotNull(PluginRepositoryService.parseCatalog(catalog()))
        listOf("../demo", "con", "demo..evil", "demo/", "demo.").forEach {
            assertFailsWith<IllegalArgumentException> { PluginRepositoryService.parseCatalog(catalog(plugin.copy(id = it))) }
        }
        assertFailsWith<IllegalArgumentException> { PluginRepositoryService.parseCatalog(catalog(plugin.copy(version = "latest"))) }
        assertFailsWith<IllegalArgumentException> {
            PluginRepositoryService.parseCatalog(catalog(plugin.copy(artifacts = listOf(artifact.copy(url = "http://example.org/p.zip")))))
        }
        assertFailsWith<IllegalArgumentException> {
            PluginRepositoryService.parseCatalog(catalog(plugin.copy(artifacts = listOf(artifact.copy(sha256 = "bad")))))
        }
        assertFailsWith<IllegalArgumentException> {
            PluginRepositoryService.parseCatalog(Json.encodeToString(PluginCatalog(name = "Example", plugins = listOf(plugin, plugin))))
        }
    }

    @Test
    fun `compatibility keeps provider and lyrics API rules independent`() {
        val desktop = PluginTarget("windows", "x64")
        assertEquals(null, plugin.copy(apiVersion = 99, minHostApiVersion = 1).compatibility(desktop).second)
        assertNotNull(plugin.copy(minHostApiVersion = 99).compatibility(desktop).second)
        val lyrics = plugin.copy(kind = RepositoryPluginKind.LYRICS, apiVersion = 99, versionCode = 1,
            artifacts = listOf(artifact.copy(runtime = "rhino-js")))
        assertNotNull(lyrics.compatibility(desktop).second)
        val binary = plugin.copy(artifacts = listOf(artifact.copy(platform = "android", arch = "arm64", runtime = "binary")))
        assertNotNull(binary.compatibility(PluginTarget("android", "arm64")).second)
        assertNotNull(plugin.copy(artifacts = listOf(artifact, artifact)).compatibility(desktop).second)
    }

    @Test
    fun `network is opt in refresh never downloads and automatic checks are throttled`() {
        runBlocking {
            val io = MemoryIO().apply { responses[source] = catalog() }
            val service = PluginRepositoryService(MemorySettings(), io, PluginTarget("windows", "x64"), { emptyList() }) {
                    _, _ -> error("Refresh must never install")
            }
            service.addSource(source)
            service.loadCached()
            assertEquals(0, io.fetches)
            assertFailsWith<IllegalArgumentException> { service.refresh() }
            service.setNetworkEnabled(true)
            service.setAutoCheck(true)
            service.checkIfDue()
            service.checkIfDue()
            assertEquals(1, io.fetches)
            assertEquals(0, io.downloads)
            assertEquals(1, service.state.value.entries.size)
        }
    }

    @Test
    fun `failed refresh preserves cached entries and reports a source error`() {
        runBlocking {
            val io = MemoryIO().apply { caches[source] = catalog() }
            val service = PluginRepositoryService(MemorySettings(), io, PluginTarget("windows", "x64"), { emptyList() }) {
                    _, _ -> PluginInstallOutcome()
            }
            service.addSource(source)
            service.loadCached()
            service.setNetworkEnabled(true)
            io.failure = IllegalStateException("Offline")
            service.refresh()
            assertEquals(1, service.state.value.entries.size)
            assertEquals("Offline", service.state.value.sourceErrors[source])
        }
    }

    @Test
    fun `install records provenance and rejects another repository taking over`() {
        runBlocking {
            val settings = MemorySettings()
            val io = MemoryIO().apply { responses[source] = catalog() }
            var inventory = emptyList<InstalledRepositoryPlugin>()
            val service = PluginRepositoryService(settings, io, PluginTarget("windows", "x64"), { inventory }) { p, _ ->
                inventory = listOf(InstalledRepositoryPlugin(p.key, p.version))
                PluginInstallOutcome()
            }
            service.addSource(source)
            service.setNetworkEnabled(true)
            service.refresh()
            service.install(service.state.value.entries.single())
            assertEquals(PluginInstallPhase.COMPLETE, service.state.value.task?.phase)
            assertEquals(1, io.discarded)
            val other = "https://other.example.org/index.json"
            io.responses[other] = catalog(plugin.copy(version = "1.3.0"))
            service.addSource(other)
            service.refresh()
            assertFailsWith<IllegalArgumentException> {
                service.install(service.state.value.entries.single { it.source.url == other }, replaceUnmanaged = true)
            }
            assertEquals(1, io.downloads)
        }
    }

    @Test
    fun `unmanaged replacements need consent and pending native updates do not claim to be applied`() {
        runBlocking {
            val io = MemoryIO().apply { responses[source] = catalog() }
            val inventory = listOf(InstalledRepositoryPlugin(plugin.key, "1.0.0"))
            val service = PluginRepositoryService(MemorySettings(), io, PluginTarget("windows", "x64"), { inventory }) {
                    _, _ -> PluginInstallOutcome(restartRequired = true)
            }
            service.addSource(source)
            service.setNetworkEnabled(true)
            service.refresh()
            assertFailsWith<IllegalArgumentException> { service.install(service.state.value.entries.single()) }
            service.install(service.state.value.entries.single(), replaceUnmanaged = true)
            assertEquals(PluginInstallPhase.RESTART_REQUIRED, service.state.value.task?.phase)
            assertTrue(service.state.value.entries.single().managed!!.pendingRestart)
            assertEquals("1.0.0", service.state.value.entries.single().installed?.version)
            assertFalse(service.state.value.entries.single().updateAvailable)
        }
    }

    @Test
    fun `turning networking off cancels a download before installation`() {
        runBlocking {
            val io = MemoryIO().apply {
                responses[source] = catalog()
                downloadGate = CompletableDeferred()
            }
            var commits = 0
            val service = PluginRepositoryService(MemorySettings(), io, PluginTarget("windows", "x64"), { emptyList() }) {
                    _, _ -> commits++; PluginInstallOutcome()
            }
            service.addSource(source)
            service.setNetworkEnabled(true)
            service.refresh()
            val task = async { service.install(service.state.value.entries.single()) }
            io.downloadStarted.await()
            service.setNetworkEnabled(false)
            assertFailsWith<kotlinx.coroutines.CancellationException> { task.await() }
            assertEquals(0, commits)
        }
    }

    @Test
    fun `default repository stays offline and removing it persists across restarts`() {
        runBlocking {
            val settings = MemorySettings()
            val io = MemoryIO()
            val defaults = listOf(PluginRepositorySource(source, "Default"))
            fun service() = PluginRepositoryService(
                settings, io, PluginTarget("windows", "x64"), { emptyList() },
                defaultSources = defaults,
            ) { _, _ -> PluginInstallOutcome() }
            val first = service()
            first.loadCached()
            assertEquals(1, first.state.value.sources.size)
            assertFalse(first.state.value.networkEnabled)
            assertEquals(0, io.fetches)
            first.removeSource(source)
            assertTrue(service().state.value.sources.isEmpty())
        }
    }

    @Test
    fun `manual reimport clears provenance even when the version did not change`() {
        runBlocking {
            val io = MemoryIO().apply { responses[source] = catalog() }
            var inventory = emptyList<InstalledRepositoryPlugin>()
            val service = PluginRepositoryService(MemorySettings(), io, PluginTarget("windows", "x64"), { inventory }) { p, _ ->
                inventory = listOf(InstalledRepositoryPlugin(p.key, p.version))
                PluginInstallOutcome()
            }
            service.addSource(source)
            service.setNetworkEnabled(true)
            service.refresh()
            service.install(service.state.value.entries.single())
            assertNotNull(service.state.value.entries.single().managed)
            service.forgetInstall(plugin.key)
            assertEquals(null, service.state.value.entries.single().managed)
        }
    }

    @Test
    fun `failed restart clears pending status and offers an explicit retry`() {
        runBlocking {
            val settings = MemorySettings()
            val io = MemoryIO().apply { responses[source] = catalog() }
            settings.putString("installed", Json.encodeToString(listOf(
                ManagedPluginInstall(plugin.key, source, plugin.version, sha256 = artifact.sha256, pendingRestart = true),
            )))
            val service = PluginRepositoryService(
                settings, io, PluginTarget("windows", "x64"),
                { listOf(InstalledRepositoryPlugin(plugin.key, "1.0.0")) },
                pendingUpdateExists = { false },
            ) { _, _ -> PluginInstallOutcome() }
            service.addSource(source)
            service.setNetworkEnabled(true)
            service.refresh()
            val entry = service.state.value.entries.single()
            assertFalse(entry.managed!!.pendingRestart)
            assertTrue(entry.updateAvailable)
        }
    }
}
