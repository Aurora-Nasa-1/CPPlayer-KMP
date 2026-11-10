package cp.player.core.provider

import cp.player.core.BackendResult
import cp.player.core.music.UnifiedMusicSourceImpl
import cp.player.core.provider.typed.*
import cp.player.sdk.*
import cp.player.sdk.example.CatalogProvider
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class KmpSdkContractTest {
    private fun root() = File(System.getProperty("cp.typed.repo", ".."))

    @Test
    fun `compiled Kotlin and JS providers share HTTP domain and playback behavior`() {
        KmpSdkFixture().use { fixture ->
            val source = root().resolve("provider-sdk-kmp/examples/js/index.js").readText(Charsets.UTF_8)
            for (js in listOf(false, true)) runBlocking {
                val provider = SdkBackendProvider("kmp-fixture", "Catalog", "1.0.0", if (js) ProviderType.SCRIPT else ProviderType.JNI,
                    factory = { if (js) JsSdkProvider(source, it) else CatalogProvider(it) },
                    settingsSchema = listOf(ProviderSettingItemSchema("origin", "Origin", "string", default = fixture.origin)),
                    sdkAllowedOrigins = setOf(fixture.origin))
                val settings = SdkMemorySettings()
                val cookies = ProviderCookieStorage(settings)
                val manager = ProviderManager(settings, cookies)
                assertTrue(manager.switchProvider(provider, save = false))
                val domain = UnifiedMusicSourceImpl(SdkEmptyLocal(), { manager.sdkSessions.current() }, { provider })
                try {
                    val host = assertNotNull(manager.sdkSessions.current())
                    val first = assertNotNull(domain.searchPage("all", 1, 2, 0).getOrNull())
                    assertEquals(listOf("kmp-fixture://song/001", "kmp-fixture://song/UUID-A"), first.songs.map { it.id })
                    assertEquals(4L, first.total)
                    assertEquals(true, first.hasMore)
                    val second = assertNotNull(domain.searchPage("all", 1, 2, 2).getOrNull())
                    assertEquals(listOf("kmp-fixture://song/A", "kmp-fixture://song/a"), second.songs.map { it.id })
                    val detail = assertNotNull(domain.getTrackDetail(first.songs.first().id).getOrNull())
                    assertEquals(9_500L, detail.durationMs)
                    assertEquals("artist-A", detail.artists.single().resourceId)
                    val ids = listOf("001", "missing", "A", "a", "001").map { "kmp-fixture://song/$it" }
                    val batch = assertNotNull(domain.getTrackOutcomes(ids).getOrNull())
                    assertEquals(ids, batch.map { it.mediaId })
                    assertEquals(listOf(null, "NotFound", null, null, null), batch.map { it.error })
                    assertEquals("Catalog A", batch[2].track?.name)
                    assertEquals("Catalog a", batch[3].track?.name)
                    val resource = assertNotNull(domain.getSongUrl(ids.first(), "standard").getOrNull())
                    assertEquals("lossless", resource.level)
                    assertEquals("001", resource.headers?.get("X-Catalog-Id"))
                    assertEquals(1_900_000_000_000L, resource.expireAt)
                    assertTrue(domain.search("all", 10) is BackendResult.Unsupported)
                    assertTrue(domain.getTrackDetail("other://song/001") is BackendResult.Unsupported)
                    assertFailsWith<IllegalArgumentException> { host.get("") }
                    val runtime = createSdkRuntime(setOf(fixture.origin))
                    try {
                        val audio = runtime.http(HttpRequest(resource.url, "GET"))
                        assertContentEquals(fixture.wav, audio.body)
                        assertEquals("RIFF", audio.body.copyOfRange(0, 4).decodeToString())
                        assertFailsWith<IllegalArgumentException> { runtime.http(HttpRequest("https://example.invalid", "GET")) }
                    } finally { runtime.close() }
                } finally { manager.sdkSessions.close() }
            }
        }
    }

    @Test
    fun `business bridge works with JSON globally disabled`() {
        runBlocking {
            val runtime = createSdkRuntime(emptySet())
            val js = JsSdkProvider("""
                JSON.parse = JSON.stringify = () => { throw Error("JSON bridge forbidden"); };
                globalThis.provider = {
                    capabilities: ["tracks.search", "tracks.get", "tracks.resolve"],
                    search(r) { return { items: [{ id: "001", title: "Unicode \u4e2d", artists: [], durationMs: 5 }], total: 1 }; },
                    get(r) { return { id: r.id, title: "Opaque " + r.id, durationMs: 5 }; },
                    resolve(r) { return { url: "https://audio.example/track", actualQuality: "future-quality" }; }
                };
            """.trimIndent(), ProviderContext("values", "a", emptyMap(), null, runtime))
            val host = ProviderHost(ProviderCallContext(ProviderSessionKey("values", "a", 0)), js)
            try {
                assertEquals("Unicode \u4e2d", host.search(SearchRequest("x", 1, 0)).items.single().title)
                assertEquals("UUID-A", host.get("UUID-A").id)
                assertEquals(2, host.getMany(listOf("001", "001")).items.size)
                assertEquals("future-quality", host.resolve(ResolveRequest("001", "standard")).actualQuality)
            } finally { host.close(); host.close(); runtime.close() }
        }
    }

    @Test
    fun `timeouts cancellation draining concurrency and invalid results are explicit`() {
        runBlocking {
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val released = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val provider = object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(Capability.GET))
                override suspend fun get(request: IdRequest): Track {
                    val count = inFlight.incrementAndGet()
                    peak.accumulateAndGet(count, ::maxOf)
                    entered.complete(Unit)
                    try { delay(if (request.id == "slow") 10_000 else 30); return Track(request.id, "Track", durationMs = 1) }
                    finally { inFlight.decrementAndGet() }
                }
                override suspend fun close() { assertEquals(0, inFlight.get()); released.incrementAndGet() }
            }
            val host = ProviderHost(ProviderCallContext(ProviderSessionKey("lifecycle", "a", 0)), provider,
                timeoutMs = 150, maxConcurrency = 2)
            try {
                coroutineScope { List(4) { async { host.get(it.toString()) } }.awaitAll() }
                assertEquals(2, peak.get())
                val timeout = assertFailsWith<ProviderException> { host.get("slow") }
                assertEquals(FailureCategory.TIMEOUT, timeout.failure.category)
                val caller = launch { host.get("slow") }
                entered.await(); delay(10); caller.cancelAndJoin()
                assertEquals(0, inFlight.get())
                val pending = async { host.get("slow") }
                delay(10)
                host.invalidate()
                assertFailsWith<CancellationException> { pending.await() }
            } finally { host.close(); host.close() }
            assertEquals(1, released.get())
            assertFailsWith<CancellationException> { host.get("after-close") }

            val malformed = ProviderHost(ProviderCallContext(ProviderSessionKey("bad", "a", 0)), object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(Capability.GET))
                override suspend fun get(request: IdRequest) = Track("wrong", "Wrong", durationMs = -1)
            })
            try {
                assertEquals(FailureCategory.INVALID_RESPONSE, assertFailsWith<ProviderException> { malformed.get("001") }.failure.category)
                assertEquals(FailureCategory.UNSUPPORTED, assertFailsWith<ProviderException> { malformed.search(SearchRequest("q", 1, 0)) }.failure.category)
            } finally { malformed.close() }
        }
    }

    @Test
    fun `account switches fence old calls and isolate storage and credentials`() {
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val settings = SdkMemorySettings()
            val cookies = ProviderCookieStorage(settings)
            cookies.saveCookie("accounts", "cookie-A", "A")
            cookies.saveCookie("accounts", "cookie-B", "B")
            val manager = ProviderManager(settings, cookies)
            val observed = mutableListOf<ProviderContext>()
            val provider = SdkBackendProvider("accounts", "Accounts", "1", ProviderType.JNI, factory = { context ->
                observed.add(context)
                object : MusicProvider {
                    override suspend fun capabilities() = Capabilities(listOf(Capability.GET))
                    override suspend fun get(request: IdRequest): Track {
                        started.complete(Unit); proceed.await()
                        return Track(request.id, context.accountId, durationMs = 1)
                    }
                }
            })
            manager.switchProvider(provider, save = false)
            manager.sdkSessions.selectAccount("A")
            val first = assertNotNull(manager.sdkSessions.current())
            observed.last().runtime.write("session", byteArrayOf(1))
            val pending = async { first.get("001") }
            started.await()
            manager.sdkSessions.selectAccount("B")
            val second = assertNotNull(manager.sdkSessions.current())
            proceed.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
            assertNull(observed.last().runtime.read("session"))
            assertEquals(listOf("cookie-A", "cookie-B"), observed.map { it.credential })
            assertEquals("B", second.get("001").title)
            assertTrue(observed.none { it.toString().contains("cookie-") })
            manager.sdkSessions.close()
            manager.sdkSessions.close()
        }
    }

    @Test
    fun `JS interrupts cycles validation crypto and runtime shutdown are bounded`() {
        runBlocking {
            val runtime = createSdkRuntime(emptySet())
            val js = JsSdkProvider("""
                globalThis.provider = {
                    capabilities: ["tracks.get"],
                    async get(r, context) {
                        if (r.id === "loop") { while (true) {} }
                        if (r.id === "cycle") { const x = {}; x.self = x; return x; }
                        if (r.id === "negative") return { id: r.id, title: "Invalid", durationMs: -1 };
                        const bytes = await context.sha256(context.utf8("abc"));
                        await context.write("digest", bytes);
                        const saved = await context.read("digest");
                        return { id: r.id, title: String(saved.length), durationMs: 1 };
                    }
                };
            """.trimIndent(), ProviderContext("bounds", "a", emptyMap(), null, runtime))
            val host = ProviderHost(ProviderCallContext(ProviderSessionKey("bounds", "a", 0)), js, timeoutMs = 3_000)
            try {
                assertEquals("32", host.get("001").title)
                assertEquals(FailureCategory.TIMEOUT, assertFailsWith<ProviderException> {
                    withTimeout(5_000) { host.get("loop") }
                }.failure.category)
                assertEquals(FailureCategory.INVALID_RESPONSE, assertFailsWith<ProviderException> { host.get("cycle") }.failure.category)
                assertEquals(FailureCategory.INVALID_RESPONSE, assertFailsWith<ProviderException> { host.get("negative") }.failure.category)
                assertEquals("32", host.get("after").title)
                assertFailsWith<IllegalArgumentException> { host.getMany(List(501) { "a" }) }
            } finally { host.close(); js.close(); runtime.close(); runtime.close() }
            assertFailsWith<IllegalStateException> { runtime.read("digest") }
        }
    }

    @Test
    fun `descriptor retirement cancels pending work before reloading or unloading`() {
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val released = AtomicInteger()
            val settings = SdkMemorySettings()
            val manager = ProviderManager(settings, ProviderCookieStorage(settings))
            val provider = SdkBackendProvider("retire", "Retire", "1", ProviderType.JNI, factory = {
                object : MusicProvider {
                    override suspend fun capabilities() = Capabilities(listOf(Capability.GET))
                    override suspend fun get(request: IdRequest): Track {
                        started.complete(Unit); delay(10_000)
                        return Track(request.id, "Retired", durationMs = 1)
                    }
                    override suspend fun close() { released.incrementAndGet() }
                }
            })
            manager.switchProvider(provider, save = false)
            val first = assertNotNull(manager.sdkSessions.current())
            val pending = async { first.get("001") }
            started.await()
            provider.retireSessions()
            assertFailsWith<CancellationException> { pending.await() }
            val second = assertNotNull(manager.sdkSessions.current())
            assertNotSame(first, second)
            provider.close()
            provider.close()
            assertFailsWith<CancellationException> { second.get("001") }
            manager.sdkSessions.close()
            assertEquals(2, released.get())
        }
    }
}
