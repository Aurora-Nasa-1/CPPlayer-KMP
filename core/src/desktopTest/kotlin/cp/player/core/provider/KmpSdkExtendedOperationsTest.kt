package cp.player.core.provider

import cp.player.core.BackendResult
import cp.player.core.music.UnifiedMusicSourceImpl
import cp.player.core.provider.typed.*
import cp.player.sdk.*
import cp.player.sdk.jni.JniMusicProvider
import cp.player.sdk.jni.JniProviderSession
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class KmpSdkExtendedOperationsTest {
    @Test
    fun `all additive operations round trip nested wire records`() {
        val cases = extendedOperationCases()
        assertEquals(106, cases.size)
        assertEquals((61..166).toList(), cases.map { it.tag })
        cases.forEach { assertTrue(it.checkWire(), it.method) }
    }

    @Test
    fun `all additive operations traverse JNI host and unified source`() {
        runBlocking {
            val cases = extendedOperationCases()
            val calls = mutableListOf<Int>()
            val binding = object : JniProviderSession {
                override fun invoke(operation: Int, request: Any): Any {
                    if (operation == ProviderProtocol.CAPABILITIES) return Capabilities(cases.map { it.capability })
                    val case = cases.single { it.tag == operation }
                    assertTrue(case.matchesRequest(request), case.method)
                    calls += operation
                    return case.response
                }
                override fun cancel() {}
                override fun close() {}
            }
            val host = host(SessionMusicProvider(JniMusicProvider(binding)))
            try {
                assertEquals(106, host.capabilities().supported.size)
                val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
                for (case in cases) {
                    assertEquals(case.responseValues, case.invoke(host), case.method)
                    val result = assertIs<BackendResult.Success<Map<String, Any?>>>(case.invokeSource(source))
                    assertEquals(case.responseValues, result.data, case.method)
                }
                assertEquals(cases.flatMap { listOf(it.tag, it.tag) }, calls)
            } finally { host.close() }
        }
    }

    @Test
    fun `all additive operations traverse real QuickJS typed values`() {
        runBlocking {
            val cases = extendedOperationCases()
            val methods = cases.joinToString(",\n") { case ->
                val expected = json(case.requestValues)
                """
                async ${case.method}(request) {
                    if (!equal(request, $expected)) throw Error("Request mismatch: ${case.method}");
                    return ${json(case.responseValues)};
                }
                """.trimIndent()
            }
            val script = """
                function equal(a, b) {
                    if (a === b) return true;
                    if (!a || !b || typeof a !== "object" || typeof b !== "object") return false;
                    const keys = Object.keys(a), other = Object.keys(b);
                    return keys.length === other.length && keys.every(k => equal(a[k], b[k]));
                }
                globalThis.provider = {
                    capabilities: ${json(cases.map { it.capability })},
                    $methods
                };
            """.trimIndent()
            val runtime = object : ProviderRuntime {
                override suspend fun http(request: HttpRequest): HttpResponse = error("No HTTP in dispatch fixture")
                override suspend fun sha256(bytes: ByteArray): ByteArray = error("Unused")
                override suspend fun read(key: String): ByteArray? = null
                override suspend fun write(key: String, value: ByteArray?) {}
            }
            val adapter = JsSdkProvider(script, ProviderContext("extended", "account", emptyMap(), null, runtime))
            val host = host(SessionMusicProvider(adapter))
            try {
                val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
                for (case in cases) {
                    assertEquals(case.responseValues, case.invoke(host), case.method)
                    val result = assertIs<BackendResult.Success<Map<String, Any?>>>(case.invokeSource(source))
                    assertEquals(case.responseValues, result.data, case.method)
                }
            } finally { host.close() }
        }
    }

    @Test
    fun `absent capabilities and default methods never pretend to succeed`() {
        runBlocking {
            val cases = extendedOperationCases()
            for (declare in listOf(false, true)) {
                val host = host(object : MusicProvider {
                    override suspend fun capabilities() = Capabilities(if (declare) cases.map { it.capability } else emptyList())
                })
                try {
                    val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
                    for (case in cases) {
                        val failure = assertFailsWith<ProviderException>(case.method) { case.invoke(host) }
                        assertEquals(FailureCategory.UNSUPPORTED, failure.failure.category, case.method)
                        assertIs<BackendResult.Unsupported>(case.invokeSource(source), case.method)
                    }
                } finally { host.close() }
            }
        }
    }

    @Test
    fun `malformed mutations and paging are rejected before dispatch`() {
        runBlocking {
            val host = host(object : MusicProvider {
                override suspend fun capabilities(): Capabilities = error("Validation must happen before adapter access")
            })
            try {
                val invalid: List<suspend () -> Any> = listOf(
                    { host.sendMessage(SendMessageRequest(emptyList(), "hello")) },
                    { host.sendMessage(SendMessageRequest(listOf("user"), " ")) },
                    { host.getMessageHistory(MessageHistoryRequest("user", 501)) },
                    { host.getRecentContacts(PageRequest(0, 0)) },
                    { host.getAllMvs(VideoBrowseRequest("ALL", 30, -1)) },
                    { host.updatePlaylist(PlaylistUpdateRequest("playlist")) },
                    { host.updateUserProfile(UserProfileUpdateRequest()) },
                    { host.deleteComment(DeleteCommentRequest("track", "music", 0)) },
                    { host.getCalendar(CalendarRequest(20, 10)) },
                    { host.heartbeatListenTogether(ListenHeartbeatRequest("room", null, -1, "PLAY")) },
                    { host.heartbeatListenTogether(ListenHeartbeatRequest("room", null, 0, "invalid")) },
                    { host.sendListenTogetherCommand(ListenCommandRequest("room",
                        ListenCommand("GOTO", null, 0, 1, "PLAY", serverSequence = 0))) },
                    { host.syncListenTogetherQueue(ListenQueueRequest("room", List(501) { "track" }, 1, "user", "REPLACE")) },
                    { host.getRecommendationHistoryTracks(HistoryTracksRequest("bad-date")) },
                    { host.resolveVideo(VideoResolveRequest("video", -1)) },
                    { host.checkTrackAvailable(TrackAvailabilityRequest("track", 0)) },
                    { host.getListenTogetherStatus(Bytes(byteArrayOf(1))) },
                    { host.likeComment(CommentLikeRequest("track", MAX_SAFE_INTEGER + 1, "music", true)) },
                    { host.postComment(PostCommentRequest("track", "music", "text", MAX_SAFE_INTEGER + 1)) },
                    { host.getFloorComments(CommentFloorRequest("track", MAX_SAFE_INTEGER + 1, "music", 30, 0)) },
                )
                invalid.forEachIndexed { index, invoke ->
                    assertFailsWith<IllegalArgumentException>("Invalid case $index") { invoke() }
                }
            } finally { host.close() }
        }
    }

    @Test
    fun `empty replacements and absent snapshots retain distinct meanings`() {
        runBlocking {
            val requests = mutableListOf<Any>()
            val host = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(
                    ExtendedCapability.SYNCLISTENTOGETHERQUEUE, ExtendedCapability.GETLISTENTOGETHERSNAPSHOT,
                    ExtendedCapability.UPDATEPLAYLIST,
                ))
                override suspend fun syncListenTogetherQueue(request: ListenQueueRequest): MutationResult {
                    requests += request
                    return MutationResult(true)
                }
                override suspend fun updatePlaylist(request: PlaylistUpdateRequest): MutationResult {
                    requests += request
                    return MutationResult(true)
                }
                override suspend fun getListenTogetherSnapshot(request: ListenRoomRequest) =
                    if (request.roomId == "absent") ListenSnapshot(null, null)
                    else ListenSnapshot(null, ListenPlaylist(emptyList(), true, emptyList()))
            })
            try {
                assertTrue(host.syncListenTogetherQueue(
                    ListenQueueRequest("room", emptyList(), 1, "user", "REPLACE"),
                ).success)
                assertEquals(emptyList(), (requests.last() as ListenQueueRequest).trackIds)
                host.updatePlaylist(PlaylistUpdateRequest("playlist", description = "", tags = null))
                assertNull((requests.last() as PlaylistUpdateRequest).tags)
                host.updatePlaylist(PlaylistUpdateRequest("playlist", tags = emptyList()))
                assertEquals(emptyList(), (requests.last() as PlaylistUpdateRequest).tags)
                assertNull(host.getListenTogetherSnapshot(ListenRoomRequest("absent")).playlist)
                assertNotNull(host.getListenTogetherSnapshot(ListenRoomRequest("empty")).playlist)
            } finally { host.close() }
        }
    }

    @Test
    fun `invalid nested results headers and mismatched IDs are rejected`() {
        runBlocking {
            val cases = extendedOperationCases()
            val adapter = object : MusicProvider {
                override suspend fun capabilities() = Capabilities(cases.map { it.capability })
                override suspend fun getUnreadCount(request: Bytes) = UnreadCount(-1, 0, 0, 0)
                override suspend fun getMv(request: IdRequest) = VideoItem("wrong-id", "Title", durationMs = 1)
                override suspend fun getVideo(request: IdRequest) = VideoItem(request.id, "Title", durationMs = MAX_SAFE_INTEGER + 1)
                override suspend fun getRecentContacts(request: PageRequest) =
                    SocialUserPage(List(request.limit + 1) { SocialUser("u", "User") })
                override suspend fun resolveVideo(request: VideoResolveRequest) = MediaResource(
                    "https://example.test/media", "720", listOf(Header("X-Test", "a\r\nb")),
                )
                override suspend fun heartbeatListenTogether(request: ListenHeartbeatRequest) = ListenHeartbeat(0)
                override suspend fun getListenTogetherStatus(request: Bytes) = ListenStatus(true, null)
            }
            val host = host(adapter)
            try {
                val invalid: List<suspend () -> Any> = listOf(
                    { host.getUnreadCount(Bytes(byteArrayOf())) },
                    { host.getMv(IdRequest("mv")) },
                    { host.getVideo(IdRequest("video")) },
                    { host.getRecentContacts(PageRequest(1, 0)) },
                    { host.resolveVideo(VideoResolveRequest("video", 720)) },
                    { host.heartbeatListenTogether(ListenHeartbeatRequest("room", null, 0, "PLAY")) },
                    { host.getListenTogetherStatus(Bytes(byteArrayOf())) },
                )
                invalid.forEach { invoke ->
                    assertEquals(FailureCategory.INVALID_RESPONSE,
                        assertFailsWith<ProviderException> { invoke() }.failure.category)
                }
            } finally { host.close() }
        }
    }

    @Test
    fun `retiring a session cancels pending social requests without publishing an empty page`() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val host = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(ExtendedCapability.GETMESSAGEHISTORY))
                override suspend fun getMessageHistory(request: MessageHistoryRequest): MessagePage {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            })
            try {
                val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
                val pending = async { source.getMessageHistory(MessageHistoryRequest("user", 30)) }
                withTimeout(5000) { entered.await() }
                host.invalidate()
                assertFailsWith<CancellationException> { pending.await() }
            } finally { host.close() }
        }
    }

    private fun host(provider: MusicProvider) =
        ProviderHost(ProviderCallContext(ProviderSessionKey("extended", "account", 0)), provider)
}

internal fun json(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is ByteArray -> JsonArray(value.map { JsonPrimitive(it) })
    is List<*> -> JsonArray(value.map(::json))
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to json(it.value) })
    else -> error("Unexpected fixture value")
}
