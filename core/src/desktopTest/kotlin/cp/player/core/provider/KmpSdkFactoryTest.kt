package cp.player.core.provider

import cp.player.core.music.UnifiedMusicSourceImpl
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class KmpSdkFactoryTest {
    @Test
    fun `manifest opts into SDK while unknown majors and implicit native trust are rejected`() {
        val directory = Files.createTempDirectory("kmp-sdk-factory-").toFile()
        try {
            directory.resolve("index.js").writeText("""
                globalThis.provider = {
                    capabilities: ["tracks.get"],
                    get(r) { return { id: r.id, title: "Loaded through factory", durationMs: 9500 }; }
                };
            """.trimIndent(), Charsets.UTF_8)
            val manifest = ModuleManifest("factory-sdk", "Factory", "1.0", "js", "index.js", contract = "kmp-sdk-v1")
            val provider = assertNotNull(ProviderFactory.create(manifest, directory.absolutePath))
            assertIs<SdkBackendProvider>(provider)
            assertNull(ProviderFactory.create(manifest.copy(contract = "kmp-sdk-v2"), directory.absolutePath))
            assertNull(ProviderFactory.create(manifest.copy(type = "jni"), directory.absolutePath))
            runBlocking {
                val settings = SdkMemorySettings()
                val manager = ProviderManager(settings, ProviderCookieStorage(settings))
                manager.switchProvider(provider, save = false)
                val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { manager.sdkSessions.current() }, { provider })
                try {
                    assertEquals("Loaded through factory", source.getTrackDetail("factory-sdk://song/001").getOrNull()?.name)
                } finally { manager.sdkSessions.close(); provider.close() }
            }
        } finally { directory.deleteRecursively() }
    }
}
