package cp.player.core.pluginrepo

import cp.player.core.lyricsplugin.FileLyricsPluginStore
import cp.player.core.lyricsplugin.LyricoLyricsPluginService
import cp.player.core.provider.ModuleManager
import cp.player.core.provider.ProviderCookieStorage
import cp.player.core.provider.ProviderManager
import cp.player.core.util.PlatformContext
import cp.player.core.util.SettingsStorage
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginRepositoryArchiveTest {
    private val plugin = RepositoryPlugin("test-provider", RepositoryPluginKind.PROVIDER, "Test", "1.0.0",
        artifacts = emptyList())
    private fun manifest(version: String = "1.0.0", type: String = "http", id: String = "test-provider") =
        """{"id":"$id","name":"Test","version":"$version","type":"$type","entryPoint":"https://example.org"}"""

    private fun zip(root: File, name: String, entries: Map<String, String>): File =
        File(root, name).also { file ->
            ZipOutputStream(file.outputStream()).use { output ->
                entries.forEach { (path, text) ->
                    output.putNextEntry(ZipEntry(path))
                    output.write(text.toByteArray(Charsets.UTF_8))
                    output.closeEntry()
                }
            }
        }

    private fun artifact(file: File) = PluginArtifact("any", runtime = "http", url = "https://example.org/p.zip",
        sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
        bytes = file.length())

    private fun inTemporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("cp-repo-test-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test
    fun `real archive verifies digest size identity and unsafe paths before extraction`() {
        inTemporaryDirectory { root ->
            val valid = zip(root, "valid.zip", mapOf("manifest.json" to manifest()))
            verifyRepositoryPackage(valid, plugin, artifact(valid))
            assertFailsWith<IllegalArgumentException> { verifyRepositoryPackage(valid, plugin, artifact(valid).copy(bytes = 1)) }
            assertFailsWith<IllegalArgumentException> { verifyRepositoryPackage(valid, plugin, artifact(valid).copy(sha256 = "0".repeat(64))) }
            assertFailsWith<IllegalArgumentException> { verifyRepositoryPackage(valid, plugin.copy(version = "2.0.0"), artifact(valid)) }
            listOf("../escape", "C:/escape", "a\\evil", "CON.txt", "file:stream", "nested/manifest.json").forEachIndexed { index, path ->
                val bad = zip(root, "bad-$index.zip", mapOf("manifest.json" to manifest(), path to "x"))
                assertFailsWith<IllegalArgumentException>(path) { verifyRepositoryPackage(bad, plugin, artifact(bad)) }
            }
            val duplicate = zip(root, "case.zip", mapOf("manifest.json" to manifest(), "a.js" to "x", "A.js" to "y"))
            assertFailsWith<IllegalArgumentException> { verifyRepositoryPackage(duplicate, plugin, artifact(duplicate)) }
        }
    }

    @Test
    fun `lyrics require matching versionCode APIs and real entry script`() {
        inTemporaryDirectory { root ->
            val p = plugin.copy(kind = RepositoryPluginKind.LYRICS, versionCode = 7, apiVersion = 3)
            val manifest = """{"id":"test-provider","name":"Test","versionName":"1.0.0","versionCode":7,"apiVersion":3,"minHostApiVersion":1,"entry":"source.js"}"""
            val valid = zip(root, "lyrics.zip", mapOf("manifest.json" to manifest, "source.js" to "globalThis.getLyrics = function() {};"))
            val a = artifact(valid).copy(runtime = "rhino-js")
            verifyRepositoryPackage(valid, p, a)
            assertFailsWith<IllegalArgumentException> { verifyRepositoryPackage(valid, p.copy(versionCode = 8), a) }
            val noEntry = zip(root, "missing.zip", mapOf("manifest.json" to manifest))
            assertFailsWith<IllegalArgumentException> { verifyRepositoryPackage(noEntry, p, artifact(noEntry).copy(runtime = "rhino-js")) }
        }
    }

    @Test
    fun `provider replacement rolls back on load failure and never overwrites same name different id`() {
        inTemporaryDirectory { root ->
            val modules = File(root, "modules")
            val manager = ModuleManager(modules.absolutePath, PlatformContext())
            val v1 = zip(root, "v1.zip", mapOf("manifest.json" to manifest()))
            assertNotNull(manager.installRepositoryModule(v1.absolutePath, plugin.id))
            val broken = zip(root, "broken.zip", mapOf("manifest.json" to manifest("2.0.0", type = "unsupported")))
            assertNull(manager.installRepositoryModule(broken.absolutePath, plugin.id))
            assertEquals("1.0.0", manager.getProvider(plugin.id)?.version)
            assertTrue(File(modules, "${plugin.id}/manifest.json").readText().contains("1.0.0"))
            val conflict = zip(root, "conflict.zip", mapOf("manifest.json" to manifest(id = "different-id")))
            assertNull(manager.installRepositoryModule(conflict.absolutePath, "different-id"))
            assertTrue(File(modules, "${plugin.id}/manifest.json").isFile)
            manager.getAvailableProviders().forEach { it.close() }
        }
    }

    private class MemorySettings : SettingsStorage {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?) = values[key] ?: default
        override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun contains(key: String) = key in values
        override fun clear() = values.clear()
    }

    @Test
    fun `restart loads staged update exactly once and failed staged update keeps old files`() {
        inTemporaryDirectory { root ->
            val modules = File(root, "modules")
            val first = ModuleManager(modules.absolutePath, PlatformContext())
            val v1 = zip(root, "v1.zip", mapOf("manifest.json" to manifest()))
            assertNotNull(first.installRepositoryModule(v1.absolutePath, plugin.id))
            val broken = zip(root, "bad.zip", mapOf("manifest.json" to manifest("2.0.0", "unsupported")))
            assertTrue(first.queueRepositoryUpdate(broken.absolutePath, plugin.id))
            val settings = MemorySettings()
            fun restart(): ModuleManager = ModuleManager(modules.absolutePath, PlatformContext()).also {
                it.init(ProviderManager(settings, ProviderCookieStorage(settings)))
            }
            val failed = restart()
            assertEquals("1.0.0", failed.getProvider(plugin.id)?.version)
            assertNotNull(failed.lastPendingUpdateError)
            assertFalse(failed.hasPendingRepositoryUpdate(plugin.id))
            val v2 = zip(root, "v2.zip", mapOf("manifest.json" to manifest("2.0.0")))
            assertTrue(failed.queueRepositoryUpdate(v2.absolutePath, plugin.id))
            val next = restart()
            assertEquals(listOf("2.0.0"), next.getAvailableProviders().map { it.version })
            assertFalse(next.hasPendingRepositoryUpdate(plugin.id))
            listOf(first, failed, next).forEach { m -> m.getAvailableProviders().forEach { it.close() } }
        }
    }

    @Test
    fun `lyrics update invalidates runtime and retains enabled state`() {
        inTemporaryDirectory { root ->
            fun lyricsZip(version: Int): File = zip(root, "lyrics-$version.zip", mapOf(
                "manifest.json" to """{"id":"repo-test","name":"Test","versionName":"1.0.$version","versionCode":$version,"apiVersion":3,"entry":"source.js","capabilities":["getLyrics","searchSongs"]}""",
                "source.js" to """
                    globalThis.searchSongs = function() { return [{id:'1', title:'Song', artist:'A'}]; };
                    globalThis.getLyrics = function() { return {type:'rawPlainLrc', rawPlainLrc:'[00:01.00]version$version'}; };
                """.trimIndent(),
            ))
            val service = LyricoLyricsPluginService(File(root, "store"), null)
            try {
                runBlocking {
                    assertEquals(listOf("repo-test"), service.importPluginZip(lyricsZip(1).absolutePath))
                    service.setEnabled("repo-test", true)
                    assertEquals("version1", service.fetchLyricsFrom("repo-test", "Song", "A", "")?.lines?.first()?.text)
                    assertEquals(listOf("repo-test"), service.importPluginZip(lyricsZip(2).absolutePath))
                    assertTrue(service.listSources().single { it.id == "repo-test" }.enabled)
                    assertEquals("version2", service.fetchLyricsFrom("repo-test", "Song", "A", "")?.lines?.first()?.text)
                }
            } finally { service.close() }
        }
    }

    @Test
    fun `abandoned lyrics backup is recovered before listing`() {
        inTemporaryDirectory { root ->
            val backup = File(root, ".backup-recovery").apply { mkdirs() }
            File(backup, "manifest.json").writeText("""{"id":"recovery","name":"Test","versionName":"1.0.0","apiVersion":3,"entry":"source.js"}""")
            File(backup, "source.js").writeText("globalThis.getLyrics = function() {};")
            assertTrue(FileLyricsPluginStore(root).loadSources().any { it.manifest.id == "recovery" })
            assertTrue(File(root, "recovery/manifest.json").isFile)
        }
    }

    @Test
    fun `deleting a provider also removes its pending activation marker`() {
        inTemporaryDirectory { root ->
            val modules = File(root, "modules")
            val manager = ModuleManager(modules.absolutePath, PlatformContext())
            val v1 = zip(root, "v1.zip", mapOf("manifest.json" to manifest()))
            assertNotNull(manager.installRepositoryModule(v1.absolutePath, plugin.id))
            assertTrue(manager.queueRepositoryUpdate(v1.absolutePath, plugin.id))
            assertTrue(manager.hasPendingRepositoryUpdate(plugin.id))
            assertTrue(manager.deleteModule(plugin.id))
            assertFalse(manager.hasPendingRepositoryUpdate(plugin.id))
        }
    }
}
