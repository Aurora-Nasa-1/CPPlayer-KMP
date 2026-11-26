package cp.player.core.provider

import cp.player.core.BackendResult
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.music.UnifiedMusicSourceImpl
import cp.player.core.playback.AudioFormatInfo
import cp.player.core.playback.PlaybackControllerImpl
import cp.player.core.playback.PlaybackMetadata
import cp.player.core.playback.PlatformPlaybackState
import cp.player.core.playback.PlatformPlayer
import cp.player.core.util.PlatformContext
import cp.player.sdk.Capabilities
import cp.player.sdk.Capability
import cp.player.sdk.IdRequest
import cp.player.sdk.MusicProvider
import cp.player.sdk.Track
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderSwitchingResilienceTest {

    @Test
    fun `switching to null immediately closes retired SDK host without waiting for another current call`() = runBlocking {
        val settings = SdkMemorySettings()
        val manager = ProviderManager(settings, ProviderCookieStorage(settings))
        val providerClosed = CompletableDeferred<Unit>()
        val provider = SdkBackendProvider(
            id = "close-probe",
            name = "Close Probe",
            version = "1.0.0",
            type = ProviderType.SCRIPT,
            factory = {
                object : MusicProvider {
                    override suspend fun capabilities() = Capabilities(listOf(Capability.GET))
                    override suspend fun get(request: IdRequest) = Track(request.id, "Ok", durationMs = 1000)
                    override suspend fun close() {
                        providerClosed.complete(Unit)
                    }
                }
            },
        )
        try {
            assertTrue(manager.switchProvider(provider))
            assertNotNull(manager.sdkSessions.current())
            assertTrue(manager.switchProvider(null))
            withTimeout(2_000) { providerClosed.await() }
            assertNull(manager.getLastActiveProviderId())
        } finally {
            manager.close()
            provider.close()
        }
    }

    @Test
    fun `custom settings and credential changes rebuild SDK session with updated context and close prior host`() = runBlocking {
        val settings = SdkMemorySettings()
        val cookies = ProviderCookieStorage(settings)
        cookies.saveCookie("scope-test", "cookie-1")
        val manager = ProviderManager(settings, cookies)
        val contexts = CopyOnWriteArrayList<cp.player.sdk.ProviderContext>()
        val closedCount = CompletableDeferred<Unit>()
        val customConfig = mutableMapOf("origin" to "https://api-a.example")
        var created = 0
        val delegate = SdkBackendProvider(
            id = "scope-test",
            name = "Scope Test",
            version = "1.0.0",
            type = ProviderType.SCRIPT,
            factory = { ctx ->
                val index = ++created
                contexts += ctx
                object : MusicProvider {
                    override suspend fun capabilities() = Capabilities(listOf(Capability.GET))
                    override suspend fun get(request: IdRequest) = Track(request.id, "Ok", durationMs = 1000)
                    override suspend fun close() {
                        if (index == 1) closedCount.complete(Unit)
                    }
                }
            },
        )
        val sdk = object : BackendProvider by delegate {
            override fun getCustomSettings(): Map<String, String> = customConfig.toMap()
        }
        try {
            assertTrue(manager.switchProvider(sdk))
            val host1 = assertNotNull(manager.sdkSessions.current())
            assertEquals("https://api-a.example", host1.context.settings["origin"])

            customConfig["origin"] = "https://api-b.example"
            sdk.retireSessions()
            withTimeout(2_000) { closedCount.await() }

            val host2 = assertNotNull(manager.sdkSessions.current())
            assertEquals("https://api-b.example", host2.context.settings["origin"])
            assertTrue(contexts.size >= 2)
        } finally {
            manager.close()
            sdk.close()
        }
    }

    @Test
    fun `module manager init falls back synchronously to healthy modules when last active fails and cleans orphaned dirs`() {
        val tempDir = Files.createTempDirectory("cp_module_fallback_").toFile()
        val zipDir = Files.createTempDirectory("cp_zip_fallback_").toFile()
        try {
            // Leftover transient directories from an earlier crash/update must be cleaned on startup,
            // while .repository-pending must stay intact for pending update processing.
            val orphanRuntime = File(tempDir, ".repository-runtime-old-123").apply { mkdirs(); resolve("junk.bin").writeText("x") }
            val orphanOld = File(tempDir, ".repository-old-abc-1").apply { mkdirs(); resolve("junk.bin").writeText("x") }
            val orphanImport = File(tempDir, ".repository-import-99").apply { mkdirs(); resolve("junk.bin").writeText("x") }

            // Broken last-active module directory
            File(tempDir, "broken-last").apply { mkdirs() }
                .resolve("manifest.json").writeText("""{"id":"broken-last","name":"Broken","type":"js","contract":"kmp-sdk-v1","entryPoint":"missing.js","version":"1.0.0"}""")

            // Healthy secondary module directory
            File(tempDir, "healthy-alt").apply { mkdirs() }.apply {
                resolve("manifest.json").writeText(
                    """{"id":"healthy-alt","name":"Healthy Alt","type":"js","contract":"kmp-sdk-v1","entryPoint":"index.js","version":"1.0.0","targetAppPackage":"com.example.alt","loginMethods":["qr","cookie"]}""",
                )
                resolve("index.js").writeText("globalThis.provider = { capabilities: [] };")
            }

            val settings = SdkMemorySettings().apply { putString("last_active_provider_id", "broken-last") }
            val manager = ProviderManager(settings, ProviderCookieStorage(settings))
            val moduleManager = ModuleManager(tempDir.absolutePath, PlatformContext())

            moduleManager.init(manager)

            assertFalse(orphanRuntime.exists(), "Orphaned .repository-runtime-* directory should be cleaned")
            assertFalse(orphanOld.exists(), "Orphaned .repository-old-* directory should be cleaned")
            assertFalse(orphanImport.exists(), "Orphaned .repository-import-* directory should be cleaned")

            val active = assertNotNull(manager.currentProvider, "Healthy fallback module should be activated synchronously")
            assertEquals("healthy-alt", active.id)
            assertEquals("com.example.alt", active.targetAppPackage)
            assertEquals(listOf("qr", "cookie"), active.loginMethods)

            runBlocking { manager.close() }
            moduleManager.getAvailableProviders().forEach { it.close() }
        } finally {
            tempDir.deleteRecursively()
            zipDir.deleteRecursively()
        }
    }

    @Test
    fun `boundary converts LinkageError from native provider into BackendResult Error`() = runBlocking {
        val source = UnifiedMusicSourceImpl(
            localMusicSource = SdkEmptyLocal(),
            sdkHost = { throw UnsatisfiedLinkError("missing native symbol") },
        )
        val result = source.getTrackDetail("demo://song/1")
        val err = assertIs<BackendResult.Error>(result)
        assertTrue(err.message.contains("native library error"))
    }

    @Test
    fun `playback does not retry standard quality on Unsupported cross-provider result and resolves per-provider cache namespace`() = runBlocking {
        val requested = CopyOnWriteArrayList<Pair<String, String>>()
        val queriedNamespaces = CopyOnWriteArrayList<String>()
        val source = object : UnifiedMusicSource {
            override suspend fun getTrackDetail(mediaId: String) =
                BackendResult.Success(TrackSummary(mediaId, "Track A", "Artist", null, null, 10_000L))
            override suspend fun getTrackDetails(mediaIds: List<String>) =
                BackendResult.Success(mediaIds.map { TrackSummary(it, "Track A", "Artist", null, null, 10_000L) })
            override suspend fun getSongUrl(mediaId: String, level: String): BackendResult<SongUrl> {
                requested += mediaId to level
                return BackendResult.Unsupported("Cross-provider requests are not supported")
            }
            override suspend fun search(keywords: String, type: Int, providers: List<String>?) =
                BackendResult.Error("not used")
            override suspend fun getUserPlaylists(providerId: String, uid: Long) =
                BackendResult.Error("not used")
        }
        val player = object : PlatformPlayer {
            override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
            override val positionMs = MutableStateFlow(0L)
            override val durationMs = MutableStateFlow(0L)
            override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)
            override suspend fun load(url: String, startPositionMs: Long, headers: Map<String, String>, metadata: PlaybackMetadata?) {}
            override fun play() {}
            override fun pause() {}
            override fun stop() {}
            override fun seekTo(positionMs: Long) {}
            override fun release() {}
            override fun setVolume(volume: Float) {}
            override fun getVolume() = 1f
        }
        val localizer = object : cp.player.core.playback.StreamLocalizer {
            override fun cachedPath(cacheKey: String): String? {
                queriedNamespaces += cacheKey
                return null
            }
            override suspend fun localize(url: String, cacheKey: String, headers: Map<String, String>, meta: cp.player.core.playback.SongCacheMeta?) = null
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = PlaybackControllerImpl(
            platform = player,
            source = source,
            scope = scope,
            streamLocalizer = localizer,
            cacheNamespace = { "providerB-ns" },
            cacheNamespaceForProvider = { pid -> "$pid-ns" },
        )
        try {
            controller.setPreloadNext(false)
            controller.setQuality("lossless")
            controller.playQueue(listOf("providerA://song/123"), 0)
            withTimeout(2_000) {
                while (controller.state.value.error == null) delay(10)
            }
            assertEquals(listOf("providerA://song/123" to "lossless"), requested)
            assertTrue(queriedNamespaces.any { it.contains(cp.player.core.util.stableHash64("providerA-ns")) })
        } finally {
            controller.release()
            scope.cancel()
        }
    }
}
