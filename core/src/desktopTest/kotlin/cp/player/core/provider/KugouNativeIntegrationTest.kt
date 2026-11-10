package cp.player.core.provider

import cp.player.core.provider.typed.createSdkRuntime
import cp.player.core.util.PlatformContext
import cp.player.sdk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class KugouNativeIntegrationTest {

    @Test
    fun `import kugou native package zip and verify live operations`() {
        // Locate generated kugou-provider.zip
        val candidates = listOf(
            File("../../kugouMusic-api-cpplayer/target/kugou-provider.zip"),
            File("../kugouMusic-api-cpplayer/target/kugou-provider.zip"),
            File("E:/project/kugouMusic-api-cpplayer/target/kugou-provider.zip"),
        )
        val zip = candidates.firstOrNull { it.isFile }
        if (zip == null) {
            println("Skipping KugouNativeIntegrationTest: kugou-provider.zip not found")
            return
        }

        println("Found Kugou native package: ${zip.absolutePath} (${zip.length()} bytes)")

        runBlocking {
            val root = Files.createTempDirectory("kugou-native-test-").toFile()
            val manager = ModuleManager(File(root, "modules").absolutePath, PlatformContext())
            val runtime = createSdkRuntime(emptySet())
            var session: MusicProvider? = null

            try {
                // 1. Import module zip via ModuleManager
                val imported = manager.importModuleDetails(zip.absolutePath)
                assertNotNull(imported, "Failed to import kugou-provider.zip: ${manager.lastLoadError}")
                assertEquals("kugou", imported.provider.id)
                assertEquals(ProviderType.JNI, imported.provider.type)
                assertEquals("kmp-sdk-v1", imported.provider.contract)
                println("Successfully imported module: ${imported.provider.name} v${imported.provider.version}")

                // 2. Create SDK session
                val context = ProviderContext("kugou", "test-account", emptyMap(), null, runtime)
                session = assertNotNull(imported.provider.createSdkProvider(context), "createSdkProvider returned null")

                // 3. Test capabilities
                val capabilities = session.capabilities()
                assertTrue(capabilities.supported.isNotEmpty(), "Capabilities should not be empty")
                assertTrue(Capability.SEARCH in capabilities.supported, "Should support tracks.search")
                assertTrue(Capability.RESOLVE in capabilities.supported, "Should support tracks.resolve")
                println("Declared capabilities count: ${capabilities.supported.size}")

                // 4. Test live search
                val searchPage = session.search(SearchRequest("纯音乐", 0, 10))
                assertTrue(searchPage.items.isNotEmpty(), "Search should return items")
                val firstTrack = searchPage.items.first()
                println("Search result first track: id=${firstTrack.id}, title=${firstTrack.title}, artists=${firstTrack.artists.map { it.name }}")
                assertNotNull(firstTrack.id)
                assertTrue(firstTrack.title.isNotEmpty())

                // 5. Test track detail
                val detail = session.get(IdRequest(firstTrack.id))
                assertEquals(firstTrack.id, detail.id)
                println("Detail title: ${detail.title}, durationMs: ${detail.durationMs}")

                // 6. Test lyrics
                val lyrics = session.getLyrics(IdRequest(firstTrack.id))
                println("Lyrics format: lrc=${lyrics.lrc != null}, ttml=${lyrics.ttml != null}")
                assertTrue(lyrics.lrc != null || lyrics.ttml != null, "Should return at least lrc or ttml")

                // 7. Test hot searches / discovery
                val hotSearches = session.getHotSearches(Bytes(byteArrayOf()))
                println("Hot searches count: ${hotSearches.items.size}")
                assertTrue(hotSearches.items.isNotEmpty(), "Hot searches should return items")

                // 8. Test rankings
                val rankings = session.getRankings(Bytes(byteArrayOf()))
                println("Rankings count: ${rankings.items.size}")
                assertTrue(rankings.items.isNotEmpty(), "Rankings should return items")

                // 9. Test resolve playback stream URL
                // Find a free track to verify playback resolution
                var resolvedResource: MediaResource? = null
                for (item in searchPage.items) {
                    try {
                        val res = session.resolve(ResolveRequest(item.id))
                        if (res.url.startsWith("http")) {
                            resolvedResource = res
                            println("Resolved track ${item.title} -> URL: ${res.url.take(60)}..., quality: ${res.actualQuality}")
                            break
                        }
                    } catch (e: Exception) {
                        // VIP song or restricted, continue to next
                    }
                }
                assertNotNull(resolvedResource, "Should be able to resolve at least one playable track from search results")
                assertTrue(resolvedResource.url.startsWith("http://") || resolvedResource.url.startsWith("https://"))

                println("All Kugou native package installation and invocation tests PASSED!")
            } finally {
                session?.close()
                manager.getAvailableProviders().forEach { it.close() }
                runtime.close()
                root.deleteRecursively()
            }
        }
    }
}
