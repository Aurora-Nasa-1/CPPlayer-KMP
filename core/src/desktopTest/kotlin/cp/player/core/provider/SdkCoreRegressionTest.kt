package cp.player.core.provider

import cp.player.core.BackendResult
import cp.player.core.download.*
import cp.player.core.listentogether.*
import cp.player.core.media.MediaType
import cp.player.core.model.DownloadStatus
import cp.player.core.model.DownloadTask
import cp.player.core.music.*
import cp.player.core.playback.*
import cp.player.core.provider.typed.*
import cp.player.core.util.PlatformContext
import cp.player.core.util.stableHash64
import cp.player.sdk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class SdkCoreRegressionTest {
    private fun host(provider: MusicProvider) =
        ProviderHost(ProviderCallContext(ProviderSessionKey("review", "default", 0)), provider)

    @Test
    fun `paged favorites collect more than 500 opaque IDs through the SDK`() {
        runBlocking {
            val all = List(1_203) { "00$it" }
            val requests = mutableListOf<PagedIdRequest>()
            val host = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(ExtendedCapability.GETLIKELISTPAGE))
                override suspend fun getLikeListPage(request: PagedIdRequest): IdPage {
                    requests += request
                    return IdPage(all.drop(request.offset).take(request.limit), all.size.toLong(),
                        request.offset + request.limit < all.size)
                }
            })
            try {
                val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
                assertEquals(all.toSet(), source.getLikeList("self").getOrThrow())
                assertEquals(listOf(0, 500, 1000), requests.map { it.offset })
                assertTrue(requests.all { it.id == "self" && it.limit == 500 })
            } finally { host.close() }
        }
    }

    @Test
    fun `legacy favorites remain compatible and paged failures never publish partial data`() {
        runBlocking {
            val legacy = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(Capability.ACCOUNT_LIKES))
                override suspend fun getLikeList(request: UserRequest) = IdList(listOf("001", "A", "a"))
            })
            val broken = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(ExtendedCapability.GETLIKELISTPAGE))
                override suspend fun getLikeListPage(request: PagedIdRequest) =
                    if (request.offset == 0) IdPage(listOf("001"), null, true) else IdPage(emptyList(), null, true)
            })
            try {
                assertEquals(setOf("001", "A", "a"), UnifiedMusicSourceImpl(SdkEmptyLocal(), { legacy }).getLikeList("self").getOrThrow())
                assertIs<BackendResult.Error>(UnifiedMusicSourceImpl(SdkEmptyLocal(), { broken }).getLikeList("self"))
            } finally { legacy.close(); broken.close() }
        }
    }

    @Test
    fun `credential changes cancel work immediately but unrelated accounts do not`() {
        runBlocking {
            val settings = SdkMemorySettings()
            val cookies = ProviderCookieStorage(settings)
            cookies.saveCookie("review", "credential-A")
            val manager = ProviderManager(settings, cookies)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val provider = SdkBackendProvider("review", "Review", "1", ProviderType.JNI, factory = {
                object : MusicProvider {
                    override suspend fun capabilities() = Capabilities(listOf(Capability.GET))
                    override suspend fun get(request: IdRequest): Track {
                        entered.complete(Unit)
                        try { awaitCancellation() } finally { cancelled.complete(Unit) }
                    }
                }
            })
            manager.switchProvider(provider, save = false)
            try {
                val old = assertNotNull(manager.sdkSessions.current())
                val pending = launch { old.get("track") }
                entered.await()
                cookies.clear("other")
                cookies.saveCookie("review", "credential-B", "other-account")
                assertTrue(old.isActive)
                cookies.clear("review")
                assertFalse(old.isActive)
                withTimeout(2_000) { cancelled.await(); pending.join() }
                assertNotSame(old, manager.sdkSessions.current())
            } finally { manager.close(); provider.close() }
        }
    }

    @Test
    fun `favorite logout and provider identity changes clear previous account IDs`() {
        runBlocking {
            var current = MusicSourceIdentity("review", "account-A")
            var response: BackendResult<Set<String>> = BackendResult.Success(setOf("track-A"))
            val source = object : TestSource() {
                override fun accountIdentity() = current
                override suspend fun getLikeList(uid: String) = response
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val favorites = PlaybackFavoritesManager(source, scope) {}
            try {
                favorites.refreshFavorites()
                assertTrue(favorites.isFavorite("review://song/track-A"))
                assertFalse(favorites.isFavorite("other://song/track-A"))
                assertFalse(favorites.isFavorite("review://video/track-A"))
                response = BackendResult.Error("Logged out")
                favorites.refreshFavorites()
                assertEquals(emptySet(), favorites.likedIds.value)
                response = BackendResult.Success(setOf("track-A"))
                favorites.refreshFavorites()
                current = MusicSourceIdentity("review", "account-B")
                assertFalse(favorites.isFavorite("review://song/track-A"))
                assertEquals(emptySet(), favorites.likedIds.value)
                response = BackendResult.Unsupported("Unavailable")
                favorites.refreshFavorites()
                assertEquals(emptySet(), favorites.likedIds.value)
            } finally { favorites.cancel(); scope.cancel() }
        }
    }

    @Test
    fun `late favorite loads and mutation rollback cannot restore old account state`() {
        runBlocking {
            var current = MusicSourceIdentity("review", "A")
            val loaded = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var first = true
            val mutating = CompletableDeferred<Unit>()
            val finishMutation = CompletableDeferred<Unit>()
            val source = object : TestSource() {
                override fun accountIdentity() = current
                override suspend fun getLikeList(uid: String): BackendResult<Set<String>> {
                    if (first) {
                        first = false
                        loaded.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        return BackendResult.Success(setOf("old"))
                    }
                    return BackendResult.Success(if (current.namespace == "C") setOf("C-only") else setOf("new"))
                }
                override suspend fun likeSong(mediaId: String, liked: Boolean): BackendResult<Boolean> {
                    mutating.complete(Unit)
                    finishMutation.await()
                    return BackendResult.Error("Failed")
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val favorites = PlaybackFavoritesManager(source, scope) {}
            try {
                val oldLoad = launch { favorites.refreshFavorites() }
                loaded.await()
                current = MusicSourceIdentity("review", "B")
                favorites.refreshFavorites()
                release.complete(Unit)
                oldLoad.join()
                assertEquals(setOf("new"), favorites.likedIds.value)
                val mutation = launch { favorites.toggleFavoriteFor("review://song/new") }
                mutating.await()
                current = MusicSourceIdentity("review", "C")
                favorites.refreshFavorites()
                finishMutation.complete(Unit)
                mutation.join()
                assertEquals(setOf("C-only"), favorites.likedIds.value)
            } finally {
                release.complete(Unit)
                finishMutation.complete(Unit)
                favorites.cancel()
                scope.cancel()
            }
        }
    }

    @Test
    fun `favorite cancellation propagates and account observation survives a retired load`() {
        runBlocking {
            val changes = MutableStateFlow(0)
            val source = object : TestSource() {
                override fun accountIdentity() = MusicSourceIdentity("review", changes.value.toString())
                override val accountChanges = changes.map { Unit }
                override suspend fun getLikeList(uid: String): BackendResult<Set<String>> {
                    if (changes.value == 0) throw CancellationException("Retired session")
                    return BackendResult.Success(setOf("track"))
                }
                override suspend fun likeSong(mediaId: String, liked: Boolean): BackendResult<Boolean> =
                    throw CancellationException("Cancelled mutation")
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val favorites = PlaybackFavoritesManager(source, scope) {}
            try {
                yield()
                changes.value = 1
                withTimeout(2_000) { favorites.likedIds.first { it == setOf("track") } }
                assertFailsWith<CancellationException> { favorites.toggleFavoriteFor("review://song/track") }
                assertTrue(favorites.isFavorite("review://song/track"))
            } finally { favorites.cancel(); scope.cancel() }
        }
    }

    @Test
    fun `desktop fallback cache stays inside namespace across quality and restart`() {
        runBlocking {
            val dir = Files.createTempDirectory("sdk-scoped-cache").toFile()
            val executor = object : DownloadExecutor {
                override suspend fun download(url: String, headers: Map<String, String>, targetPartPath: String,
                    resumeBytes: Long, onProgress: suspend (Long, Long?) -> Unit): Long {
                    File(targetPartPath).writeBytes("fLaCtest".toByteArray())
                    onProgress(8, 8)
                    return 8
                }
            }
            try {
                val media = "review://song/001"
                val cache = DesktopStreamLocalizer(dir, executor = executor)
                val pathA = cache.localize("https://example.test/a", "$media@lossless#${stableHash64("A")}", emptyMap())
                assertNotNull(pathA)
                cache.entries() // Tag repair must preserve namespace metadata.
                val restarted = DesktopStreamLocalizer(dir, executor = executor)
                assertEquals(pathA, restarted.findByMediaId(media, "A")?.let { restarted.entryPath(it.id) })
                assertNull(restarted.findByMediaId(media, "B"))
                assertNull(restarted.findByMediaId(media, ""))
                val pathB = cache.localize("https://example.test/b", "$media@hires#${stableHash64("B")}", emptyMap())
                assertNotNull(pathB)
                assertNotEquals(pathA, pathB)
                assertEquals("hires", restarted.findByMediaId(media, "B")?.qualityLevel)
                assertEquals("lossless", restarted.findByMediaId(media, "A")?.qualityLevel)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test
    fun `offline playback never uses an unscoped account cache fallback`() {
        runBlocking {
            var loaded: String? = null
            val player = object : PlatformPlayer {
                override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
                override val positionMs = MutableStateFlow(0L)
                override val durationMs = MutableStateFlow(0L)
                override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)
                override suspend fun load(url: String, startPositionMs: Long, headers: Map<String, String>, metadata: PlaybackMetadata?) { loaded = url }
                override fun play() {}
                override fun pause() {}
                override fun stop() {}
                override fun seekTo(positionMs: Long) {}
                override fun release() {}
                override fun setVolume(volume: Float) {}
                override fun getVolume() = 1f
            }
            val cache = object : StreamLocalizer {
                override suspend fun localize(url: String, cacheKey: String, headers: Map<String, String>, meta: SongCacheMeta?) = null
                override fun findByMediaId(mediaId: String) = SongCacheEntry("account-A.mp3", mediaId, "standard")
                override fun entryPath(id: String) = "C:/cache/account-A.mp3"
            }
            val source = object : TestSource() {
                override suspend fun getTrackDetail(mediaId: String) =
                    BackendResult.Success(TrackSummary(mediaId, "Track", "Artist", null, null, 1000))
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val controller = PlaybackControllerImpl(player, source, scope, streamLocalizer = cache, cacheNamespace = { "B" })
            try {
                controller.setPreloadNext(false)
                controller.playQueue(listOf("review://song/track"), 0)
                withTimeout(3_000) { controller.state.first { it.error != null } }
                assertNull(loaded)
            } finally { controller.release(); scope.cancel() }
        }
    }

    @Test
    fun `video downloads resolve MV and video resources with quality headers expiry and format`() {
        runBlocking {
            val dir = Files.createTempDirectory("sdk-video-download").toFile()
            val requests = mutableListOf<Pair<String, Int>>()
            var resolved = 0
            val source = object : TestSource() {
                override suspend fun getSongUrl(mediaId: String, level: String): BackendResult<SongUrl> = error("Audio resolver called")
                override suspend fun resolveVideoResource(mediaId: String, resolution: Int): BackendResult<MediaResource> {
                    requests += mediaId to resolution
                    resolved++
                    return BackendResult.Success(MediaResource("https://example.test/stream", "720",
                        listOf(Header("X-Video", "token")), if (resolved == 1) 1 else Long.MAX_VALUE,
                        "webm", 1000, 8))
                }
            }
            val config = DownloadConfig(SdkMemorySettings(), PlatformContext()).apply { setRootDir(dir.absolutePath.replace('\\', '/')) }
            val executor = object : DownloadExecutor {
                override suspend fun download(url: String, headers: Map<String, String>, targetPartPath: String,
                    resumeBytes: Long, onProgress: suspend (Long, Long?) -> Unit): Long {
                    assertEquals("https://example.test/stream", url)
                    assertEquals(mapOf("X-Video" to "token"), headers)
                    File(targetPartPath).writeBytes(ByteArray(8) { 42 })
                    return 8
                }
            }
            val engine = DownloadEngine(source, config, executor)
            try {
                for (type in listOf("mv", "video")) {
                    engine.submit(DownloadTask(type, "review://$type/001", "review", type, mediaType = MediaType.VIDEO,
                        qualityLevel = "1080", createdAt = 1))
                    val completed = withTimeout(5_000) {
                        engine.tasksFlow.first { tasks -> tasks.any { it.id == type && it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) } }
                    }.single { it.id == type }
                    assertEquals(DownloadStatus.COMPLETED, completed.status, completed.error)
                    assertEquals("720", completed.qualityLevel)
                    assertTrue(completed.localPath!!.endsWith(".webm"))
                    assertEquals(8L, File(completed.localPath!!).length())
                }
                assertEquals(listOf("review://mv/001" to 1080, "review://mv/001" to 1080, "review://video/001" to 1080), requests)
            } finally { engine.shutdown(); dir.deleteRecursively() }
        }
    }

    @Test
    fun `SDK listen together maps room command queue and rejects cross account writes`() {
        runBlocking {
            var identity = MusicSourceIdentity("review", "A")
            val commands = mutableListOf<ListenCommandRequest>()
            val queues = mutableListOf<ListenQueueRequest>()
            val room = ListenRoom("Room-A", listOf(ListenMember(SocialUser("12", "Alice"), true)), "12", "FRIEND", 10, 100, 20)
            val provider = object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(
                    ExtendedCapability.GETLISTENTOGETHERSTATUS, ExtendedCapability.CREATELISTENTOGETHERROOM,
                    ExtendedCapability.ACCEPTLISTENTOGETHERINVITATION, ExtendedCapability.ENDLISTENTOGETHER,
                    ExtendedCapability.HEARTBEATLISTENTOGETHER, ExtendedCapability.GETLISTENTOGETHERSNAPSHOT,
                    ExtendedCapability.SENDLISTENTOGETHERCOMMAND, ExtendedCapability.SYNCLISTENTOGETHERQUEUE,
                ))
                override suspend fun getListenTogetherStatus(request: Bytes) = ListenStatus(true, room, "CONNECTED")
                override suspend fun createListenTogetherRoom(request: Bytes) = room
                override suspend fun acceptListenTogetherInvitation(request: ListenInvitationRequest): ListenRoom {
                    assertEquals(ListenInvitationRequest("Room-A", "12"), request)
                    return room
                }
                override suspend fun endListenTogether(request: ListenRoomRequest) = MutationResult(false)
                override suspend fun heartbeatListenTogether(request: ListenHeartbeatRequest) = ListenHeartbeat(30_000)
                override suspend fun getListenTogetherSnapshot(request: ListenRoomRequest) = ListenSnapshot(
                    ListenCommand("PLAY", "001", 300, 4, "PLAY", "old", 5, "12"),
                    ListenPlaylist(listOf("001", "A", "001"), true, listOf(ListenQueueVersion("12", 6))),
                )
                override suspend fun sendListenTogetherCommand(request: ListenCommandRequest): MutationResult {
                    commands += request; return MutationResult(true)
                }
                override suspend fun syncListenTogetherQueue(request: ListenQueueRequest): MutationResult {
                    queues += request; return MutationResult(true)
                }
            }
            val host = host(provider)
            val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host }, MusicSourceAccountContext { identity })
            val backend = SdkListenTogetherBackend(source)
            try {
                assertTrue(backend.isSupported())
                assertEquals(12L, backend.membership().getOrThrow().room!!.creatorId)
                assertEquals("FRIEND", backend.createRoom().getOrThrow().roomType)
                assertIs<BackendResult.Success<Unit>>(backend.accept("Room-A", 12))
                val snapshot = backend.snapshot("Room-A").getOrThrow()
                assertEquals(5L, snapshot.command!!.serverSeq)
                assertEquals(mapOf(12L to 6L), snapshot.playlist!!.versions)
                assertIs<BackendResult.Success<Unit>>(backend.heartbeat("Room-A", "001", "PLAY", 300))
                assertIs<BackendResult.Success<Unit>>(backend.reportPlayCommand("Room-A", "GOTO", "PLAY", 300, "", "001", 7))
                assertNull(commands.single().command.formerTrackId)
                assertEquals("001", commands.single().command.trackId)
                assertIs<BackendResult.Success<Unit>>(backend.reportPlaylist("Room-A", 12, 8, listOf("001", "A", "001")))
                assertEquals(listOf("001", "A", "001"), queues.single().trackIds)
                assertEquals("REPLACE", queues.single().action)
                assertIs<BackendResult.Error>(backend.end("Room-A"))
                identity = MusicSourceIdentity("review", "B")
                assertIs<BackendResult.Unsupported>(backend.reportPlayCommand("Room-A", "PLAY", "PLAY", 0, null, "001", 8))
                assertEquals(1, commands.size)
            } finally { host.close() }
        }
    }

    @Test
    fun `listen together unsupported and opaque user identities are explicit`() {
        runBlocking {
            for (id in listOf("001", "user-A", "9223372036854775808")) {
                val source = object : TestSource() {
                    override fun accountIdentity() = MusicSourceIdentity("review", "A")
                    override suspend fun getListenTogetherStatus(request: Bytes) =
                        BackendResult.Success(ListenStatus(true, ListenRoom("room", creatorId = id)))
                }
                assertIs<BackendResult.Unsupported>(SdkListenTogetherBackend(source).membership())
            }
            val backend = SdkListenTogetherBackend(object : TestSource() {})
            assertFalse(backend.isSupported())
            assertIs<BackendResult.Unsupported>(backend.membership())
        }
    }

    private abstract class TestSource : UnifiedMusicSource {
        override suspend fun getTrackDetail(mediaId: String): BackendResult<TrackSummary> = BackendResult.Unsupported("No detail")
        override suspend fun getTrackDetails(mediaIds: List<String>): BackendResult<List<TrackSummary>> = BackendResult.Unsupported("No detail")
        override suspend fun getSongUrl(mediaId: String, level: String): BackendResult<SongUrl> = BackendResult.Unsupported("No access")
        override suspend fun search(keywords: String, type: Int, providers: List<String>?): BackendResult<SearchResult> = BackendResult.Unsupported("No search")
        override suspend fun getUserPlaylists(providerId: String, uid: Long): BackendResult<List<PlaylistSummary>> = BackendResult.Unsupported("No playlists")
    }
}
