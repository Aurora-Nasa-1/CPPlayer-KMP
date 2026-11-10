package cp.player.core.provider

import cp.player.core.music.UnifiedMusicSourceImpl
import cp.player.sdk.*
import java.nio.file.Files
import java.nio.charset.CharacterCodingException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ProviderConvergenceTest {
    private fun manifest() = ModuleManifest(
        "convergence", "Convergence", "1.0.0", "js", "index.js", contract = PROVIDER_SDK_CONTRACT,
    )

    @Test
    fun `only SDK script packages are loadable`() {
        assertTrue(manifest().isLoadable)
        for (contract in listOf(null, "standard", "netease-legacy", "v1", "v3", "kmp-sdk-v2")) {
            assertFalse(manifest().copy(contract = contract).isLoadable)
        }
        for (type in listOf("http", "binary", "jni", "jna", "ffm", "unknown")) {
            assertFalse(manifest().copy(type = type).isLoadable)
        }
        assertTrue(manifest().copy(type = "script").isLoadable)
        assertFalse(manifest().copy(minHostApiVersion = HOST_PROVIDER_API_VERSION + 1).isLoadable)
        assertEquals(listOf("search", "playback"), manifest().copy(
            capabilities = listOf(Capability.SEARCH, Capability.RESOLVE, "comments", "qrLogin"),
        ).knownCapabilities)
        assertFalse(manifest().copy(capabilities = listOf("comments")).isFeatureAvailable("comments"))
    }

    @Test
    fun `identity and entry point cannot escape the package`() {
        for (id in listOf("../outside", "local", "", "UPPER", "a..b", "a/b")) {
            assertFalse(manifest().copy(id = id).isLoadable, id)
        }
        for (entry in listOf("../index.js", "dir/../../outside", "dir\\..\\outside", "/tmp/a", "C:\\a", "")) {
            assertFalse(manifest().copy(entryPoint = entry).isLoadable, entry)
        }
        val directory = Files.createTempDirectory("provider-policy-").toFile()
        try {
            assertNull(ProviderFactory.create(manifest(), directory.absolutePath))
            directory.resolve("index.js").writeBytes(ByteArray(1024 * 1024 + 1))
            assertNull(ProviderFactory.create(manifest(), directory.absolutePath))
            directory.resolve("index.js").writeText("globalThis.provider = { capabilities: [] };", Charsets.UTF_8)
            val provider = assertNotNull(ProviderFactory.create(manifest(), directory.absolutePath))
            assertEquals(PROVIDER_SDK_CONTRACT, provider.contract)
            provider.close()
            directory.resolve("index.js").writeBytes(byteArrayOf(0xE4.toByte(), 0xB8.toByte()))
            assertFailsWith<CharacterCodingException> { ProviderFactory.create(manifest(), directory.absolutePath) }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `switching rejects legacy descriptors without creating a session`() {
        val settings = SdkMemorySettings()
        val manager = ProviderManager(settings, ProviderCookieStorage(settings))
        var sessions = 0
        val sdk = SdkBackendProvider("convergence", "SDK", "1", ProviderType.SCRIPT, { sessions++; object : MusicProvider {} })
        val legacy = object : BackendProvider by sdk { override val contract = "standard" }
        assertFalse(manager.switchProvider(legacy))
        assertNull(manager.currentProvider)
        assertTrue(manager.lastSwitchError.orEmpty().contains("retired"))
        assertTrue(manager.switchProvider(sdk))
        assertSame(sdk, manager.currentProvider)
        assertEquals(0, sessions)
        val reloaded = SdkBackendProvider("convergence", "Reloaded", "2", ProviderType.SCRIPT, { object : MusicProvider {} })
        assertTrue(manager.restoreLastProvider(listOf(reloaded), cp.player.core.util.PlatformContext()))
        assertSame(reloaded, manager.currentProvider)
        assertTrue(manager.switchProvider(null))
        assertNull(manager.currentProvider)
        runBlocking { manager.sdkSessions.close() }
        sdk.close()
        reloaded.close()
    }

    @Test
    fun `capabilities are read once per immutable session`() {
        runBlocking {
            val settings = SdkMemorySettings()
            val manager = ProviderManager(settings, ProviderCookieStorage(settings))
            val calls = AtomicInteger()
            val descriptor = SdkBackendProvider("convergence", "SDK", "1", ProviderType.SCRIPT, {
                object : MusicProvider {
                    override suspend fun capabilities(): Capabilities {
                        calls.incrementAndGet()
                        delay(10)
                        return Capabilities(listOf(Capability.GET))
                    }
                    override suspend fun get(request: IdRequest) = Track(request.id, request.id, durationMs = 9_500)
                }
            })
            manager.switchProvider(descriptor)
            try {
                val first = assertNotNull(manager.sdkSessions.current())
                coroutineScope { List(8) { async { first.get(it.toString()) } }.awaitAll() }
                first.getMany(listOf("001", "001"))
                assertEquals(1, calls.get())
                manager.sdkSessions.selectAccount("second")
                val second = assertNotNull(manager.sdkSessions.current())
                assertNotSame(first, second)
                second.get("001")
                assertEquals(2, calls.get())
            } finally { manager.sdkSessions.close(); descriptor.close() }
        }
    }

    @Test
    fun `SDK capability failures are retried and not cached`() {
        runBlocking {
            var calls = 0
            val provider = SessionMusicProvider(object : MusicProvider {
                override suspend fun capabilities(): Capabilities {
                    if (++calls == 1) throw CancellationException("cancelled")
                    return Capabilities(listOf(Capability.GET))
                }
            })
            assertFailsWith<CancellationException> { provider.capabilities() }
            assertEquals(listOf(Capability.GET), provider.capabilities().supported)
            provider.capabilities()
            assertEquals(2, calls)
        }
    }

    @Test
    fun `configuration changes during factory creation fence the stale session`() {
        runBlocking {
            val settings = SdkMemorySettings()
            val manager = ProviderManager(settings, ProviderCookieStorage(settings))
            val config = mutableMapOf("origin" to "https://old.example")
            var changesRemaining = 2
            lateinit var sdk: SdkBackendProvider
            sdk = SdkBackendProvider("convergence", "SDK", "1", ProviderType.SCRIPT, { context ->
                if (changesRemaining > 0) {
                    config["origin"] = "https://new-${changesRemaining--}.example"
                    sdk.retireSessions()
                }
                object : MusicProvider {
                    override suspend fun capabilities() = Capabilities(listOf(Capability.GET))
                    override suspend fun get(request: IdRequest) =
                        Track(request.id, context.settings.getValue("origin"), durationMs = 1)
                }
            })
            val descriptor = object : BackendProvider by sdk {
                override fun getCustomSettings() = config.toMap()
            }
            manager.switchProvider(descriptor)
            try {
                assertFailsWith<CancellationException> { manager.sdkSessions.current() }
                assertFailsWith<CancellationException> { manager.sdkSessions.current() }
                val current = assertNotNull(manager.sdkSessions.current())
                assertEquals("https://new-1.example", current.get("001").title)
            } finally { manager.sdkSessions.close(); descriptor.close() }
        }
    }

    @Test
    fun `typed source asks the SDK once and never splits opaque ids`() {
        runBlocking {
            val settings = SdkMemorySettings()
            val manager = ProviderManager(settings, ProviderCookieStorage(settings))
            var resolves = 0
            val received = mutableListOf<String>()
            val descriptor = SdkBackendProvider("convergence", "SDK", "1", ProviderType.SCRIPT, {
                object : MusicProvider {
                    override suspend fun capabilities() = Capabilities(listOf(Capability.GET, Capability.RESOLVE))
                    override suspend fun get(request: IdRequest): Track {
                        received += request.id
                        return Track(request.id, "Track", durationMs = 9_500)
                    }
                    override suspend fun resolve(request: ResolveRequest): MediaResource {
                        resolves++
                        return MediaResource("https://audio.example/test", "actual-quality")
                    }
                }
            })
            manager.switchProvider(descriptor)
            val domain = UnifiedMusicSourceImpl(SdkEmptyLocal(), { manager.sdkSessions.current() })
            try {
                val ids = listOf("001", "A,a", "001").map { "convergence://song/$it" }
                val details = assertIs<cp.player.core.BackendResult.Success<*>>(domain.getTrackDetails(ids)).data
                assertEquals(listOf("001", "A,a", "001"), received)
                assertEquals(3, (details as List<*>).size)
                domain.getSongUrl("convergence://song/001", "requested")
                assertEquals(1, resolves)
                assertIs<cp.player.core.BackendResult.Unsupported>(
                    domain.getComments("convergence://song/001", "music", 20, 0, 1),
                )
            } finally { manager.sdkSessions.close(); descriptor.close() }
        }
    }

    @Test
    fun `no active provider is an explicit error, not a successful empty page`() {
        runBlocking {
            val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { null })
            assertIs<cp.player.core.BackendResult.Error>(source.getTrackDetails(listOf("x://song/1")))
        }
    }
}
