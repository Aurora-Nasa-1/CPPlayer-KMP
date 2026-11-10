package cp.player.core.provider

import cp.player.core.BackendResult
import cp.player.core.media.MediaQualityOption
import cp.player.core.media.UnifiedMediaResolverImpl
import cp.player.core.music.UnifiedMusicSourceImpl
import cp.player.core.provider.typed.*
import cp.player.sdk.*
import cp.player.sdk.jni.JniMusicProvider
import cp.player.sdk.jni.JniProviderSession
import kotlinx.coroutines.*
import kotlin.test.*

class KmpSdkMigrationOperationsTest {
    @Test
    fun `all migration operations use the current account typed source`() {
        runBlocking {
            val cases = extendedOperationCases()
            val calls = mutableListOf<Pair<Int, Any>>()
            val binding = object : JniProviderSession {
                override fun invoke(operation: Int, request: Any): Any {
                    if (operation == ProviderProtocol.CAPABILITIES) return Capabilities(cases.map { it.capability })
                    calls += operation to request
                    return cases.single { it.tag == operation }.response
                }
                override fun cancel() {}
                override fun close() {}
            }
            val host = host(JniMusicProvider(binding))
            val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
            try {
                for (case in cases.filter { it.tag >= 127 }) {
                    val before = calls.size
                    val output = assertIs<BackendResult.Success<Map<String, Any?>>>(case.invokeSource(source), case.method)
                    assertEquals(case.responseValues, output.data, case.method)
                    assertEquals(before + 1, calls.size, case.method)
                    assertEquals(case.tag, calls.last().first, case.method)
                    assertTrue(case.matchesRequest(calls.last().second), case.method)
                }
                assertEquals((127..165).toList(), calls.map { it.first })
                assertEquals(listOf("Opaque-A", "a"), (calls.single { it.first == 138 }.second as PlaylistOrderRequest).playlistIds)
            } finally { host.close() }
        }
    }

    @Test
    fun `search preserves opaque IDs pagination metadata and provider ownership for all new types`() {
        runBlocking {
            val requests = mutableListOf<SearchRequest>()
            val provider = object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(
                    ExtendedCapability.SEARCHMVS, ExtendedCapability.SEARCHVIDEOS,
                    ExtendedCapability.SEARCHUSERS, ExtendedCapability.SEARCHDJRADIOS,
                ))
                override suspend fun searchMvs(request: SearchRequest) = videos(request)
                override suspend fun searchVideos(request: SearchRequest) = videos(request)
                private fun videos(request: SearchRequest): VideoPage {
                    requests += request
                    return VideoPage(listOf(VideoItem("MV-A", "Video", durationMs = 1000)), 43, true)
                }
                override suspend fun searchUsers(request: SearchRequest): SocialUserPage {
                    requests += request
                    return SocialUserPage(listOf(SocialUser("User-A", "User")), 43, true)
                }
                override suspend fun searchDjRadios(request: SearchRequest): DjRadioPage {
                    requests += request
                    return DjRadioPage(listOf(DjRadio("Radio-A", "Radio")), 43, true)
                }
            }
            val host = host(provider)
            val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
            try {
                for (type in listOf(1004, 1014, 1002, 1009)) {
                    val result = assertIs<BackendResult.Success<cp.player.core.music.SearchResult>>(
                        source.searchPage("Keyword", type, 7, 21),
                    ).data
                    assertEquals(SearchRequest("Keyword", 7, 21), requests.last())
                    assertEquals(43L, result.total); assertEquals(true, result.hasMore)
                    when (type) {
                        1004, 1014 -> {
                            assertEquals("MV-A", result.videos.single().id)
                            assertEquals(if (type == 1004) "mv" else "video", result.videos.single().resourceType)
                        }
                        1002 -> assertEquals("User-A", result.users.single().id)
                        1009 -> assertEquals("Radio-A", result.radios.single().id)
                    }
                }
                val count = requests.size
                assertIs<BackendResult.Unsupported>(source.searchPage("Keyword", 1014, 7, 0, listOf("other")))
                assertIs<BackendResult.Unsupported>(source.searchPage("Keyword", 9999, 7, 0))
                assertEquals(count, requests.size)
            } finally { host.close() }
        }
    }

    @Test
    fun `cursor input is never normalized or replaced by offset-only fallback`() {
        runBlocking {
            val requests = mutableListOf<CommentQuery>()
            val host = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(ExtendedCapability.GETCOMMENTPAGE))
                override suspend fun getCommentPage(request: CommentQuery): CommentPage {
                    requests += request
                    return CommentPage(emptyList(), 0, false, "", 2)
                }
            })
            val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
            try {
                for (cursor in listOf("", "opaque+/==:%20")) {
                    val output = assertIs<BackendResult.Success<cp.player.core.music.CommentPage>>(
                        source.getCommentsPage("migration://playlist/Playlist-A", "playlist", 7, 21, 1, cursor),
                    ).data
                    assertEquals(CommentQuery("Playlist-A", "playlist", 7, 21, 1, cursor), requests.last())
                    assertEquals("", output.nextCursor)
                    assertEquals(2, output.sortType)
                }
                val count = requests.size
                assertIs<BackendResult.Unsupported>(source.getCommentsPage("other://song/A", "music", cursor = "next"))
                assertEquals(count, requests.size)
            } finally { host.close() }
        }
    }

    @Test
    fun `offset-only comments remain available but never consume an opaque cursor`() {
        runBlocking {
            val requests = mutableListOf<CommentPageRequest>()
            val host = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf("comments.list"))
                override suspend fun getComments(request: CommentPageRequest): CommentPage {
                    requests += request
                    return CommentPage(emptyList(), 0, false, "legacy-cursor", 2)
                }
            })
            val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
            try {
                val page = assertIs<BackendResult.Success<cp.player.core.music.CommentPage>>(
                    source.getCommentsPage("migration://song/A", "music", 7, 21, 1, null),
                ).data
                assertEquals(CommentPageRequest("A", "music", 7, 21, 1), requests.single())
                assertNull(page.nextCursor)
                assertEquals(2, page.sortType)
                for (cursor in listOf("", "opaque+/==:%20")) {
                    assertIs<BackendResult.Unsupported>(
                        source.getCommentsPage("migration://song/A", "music", 7, 21, 1, cursor),
                    )
                }
                assertEquals(1, requests.size)
            } finally { host.close() }
        }
    }

    @Test
    fun `album operations retain distinct semantics and chart date and catalog offset`() {
        runBlocking {
            val requests = mutableListOf<Any>()
            val host = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(
                    ExtendedCapability.BROWSENEWALBUMS, ExtendedCapability.GETLATESTALBUMS, ExtendedCapability.GETALBUMCHART,
                ))
                override suspend fun browseNewAlbums(request: AlbumPageRequest): AlbumPage {
                    requests += request; return AlbumPage()
                }
                override suspend fun getLatestAlbums(request: Bytes): AlbumPage {
                    requests += request; return AlbumPage()
                }
                override suspend fun getAlbumChart(request: AlbumChartRequest): AlbumPage {
                    requests += request; return AlbumPage()
                }
            })
            val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
            try {
                assertIs<BackendResult.Success<AlbumPage>>(source.browseNewAlbums(AlbumPageRequest("JP", 7, 21)))
                assertEquals(AlbumPageRequest("JP", 7, 21), requests.last())
                assertIs<BackendResult.Success<AlbumPage>>(source.getAlbumChart(AlbumChartRequest("KR", "hot", 2025, 12, 9, 18)))
                assertEquals(AlbumChartRequest("KR", "hot", 2025, 12, 9, 18), requests.last())
                assertIs<BackendResult.Success<AlbumPage>>(source.getLatestAlbums(Bytes(byteArrayOf())))
                assertIs<Bytes>(requests.last())
                assertEquals(3, requests.size)
            } finally { host.close() }
        }
    }

    @Test
    fun `notifications preserve null versus empty cursors and resource messages preserve their types`() {
        runBlocking {
            val requests = mutableListOf<Any>()
            val host = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(
                    ExtendedCapability.GETCOMMENTNOTIFICATIONS, ExtendedCapability.GETSYSTEMNOTIFICATIONS,
                    ExtendedCapability.SENDRESOURCEMESSAGE,
                ))
                override suspend fun getCommentNotifications(request: UserCursorRequest): NotificationPage {
                    requests += request; return NotificationPage(cursor = "next+/==")
                }
                override suspend fun getSystemNotifications(request: CursorRequest): NotificationPage {
                    requests += request; return NotificationPage(cursor = "next+/==")
                }
                override suspend fun sendResourceMessage(request: ResourceMessageRequest): MutationResult {
                    requests += request; return MutationResult(true)
                }
            })
            val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
            try {
                source.getCommentNotifications(UserCursorRequest("User-A"))
                assertNull((requests.last() as UserCursorRequest).cursor)
                source.getSystemNotifications(CursorRequest(cursor = "000123"))
                assertEquals("000123", (requests.last() as CursorRequest).cursor)
                source.getSystemNotifications(CursorRequest(cursor = ""))
                assertEquals("", (requests.last() as CursorRequest).cursor)
                for (type in listOf("song", "album", "playlist")) {
                    source.sendResourceMessage(ResourceMessageRequest(listOf("User-A", "user-a"), "Resource-A", type, ""))
                    assertEquals(ResourceMessageRequest(listOf("User-A", "user-a"), "Resource-A", type, ""), requests.last())
                }
            } finally { host.close() }
        }
    }

    @Test
    fun `remote quality enumeration is typed filters unavailable options and rejects cross-provider IDs`() {
        runBlocking {
            val requests = mutableListOf<MediaQualityRequest>()
            val host = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(ExtendedCapability.GETMEDIAQUALITIES))
                override suspend fun getMediaQualities(request: MediaQualityRequest): QualityPage {
                    requests += request
                    return QualityPage(listOf(
                        QualityOption("720", "720P", 128000, 1234, true),
                        QualityOption("1080", "1080P", available = false),
                    ))
                }
            })
            val local = SdkEmptyLocal()
            val resolver = UnifiedMediaResolverImpl(local, UnifiedMusicSourceImpl(local, { host }))
            try {
                val result = assertIs<BackendResult.Success<List<MediaQualityOption>>>(
                    resolver.getAvailableQualities("migration://video/Video-A"),
                ).data
                assertEquals(listOf(MediaQualityOption("720", "720P", 720, 128000, 1234)), result)
                assertEquals(MediaQualityRequest("Video-A", "video"), requests.single())
                assertIs<BackendResult.Unsupported>(resolver.getAvailableQualities("other://video/Video-A"))
                assertEquals(1, requests.size)
                assertIs<BackendResult.Error>(resolver.getAvailableQualities("migration://playlist/Playlist-A"))
            } finally { host.close() }
        }
    }

    @Test
    fun `new request bounds fail before provider access`() {
        runBlocking {
            val host = host(object : MusicProvider {
                override suspend fun capabilities(): Capabilities = error("Adapter must not be reached")
            })
            try {
                val invalid: List<suspend () -> Any> = listOf(
                    { host.searchVideos(SearchRequest(" ", 30, 0)) },
                    { host.getCommentPage(CommentQuery("A", "music", sortType = 4)) },
                    { host.getCommentPage(CommentQuery("A", "music", cursor = "x".repeat(4097))) },
                    { host.getAlbumChart(AlbumChartRequest(month = 13)) },
                    { host.browseDjRanking(DjRankingRequest("invalid")) },
                    { host.reorderPlaylists(PlaylistOrderRequest(listOf("A", "A"))) },
                    { host.reorderPlaylistTracks(PlaylistTrackOrderRequest("P", emptyList())) },
                    { host.uploadAvatar(ImageUploadRequest("../avatar.png", "image/png", byteArrayOf(1))) },
                    { host.uploadAvatar(ImageUploadRequest("avatar.svg", "image/svg+xml", byteArrayOf(1))) },
                    { host.uploadPlaylistCover(PlaylistImageUploadRequest("P", "cover.png", "image/png", byteArrayOf())) },
                    { host.uploadAvatar(ImageUploadRequest("avatar.png", "image/png", ByteArray(MAX_UPLOAD_CHUNK_BYTES + 1))) },
                    { host.prepareCloudUpload(CloudUploadPrepareRequest("song.flac", 1, "not-md5", 128000)) },
                    { host.prepareCloudUpload(CloudUploadPrepareRequest("song.flac", 0, "0".repeat(32), 128000)) },
                    { host.uploadCloudChunk(CloudUploadChunkRequest("U", MAX_SAFE_INTEGER, byteArrayOf(1), true)) },
                    { host.uploadCloudChunk(CloudUploadChunkRequest("U", 0, ByteArray(MAX_UPLOAD_CHUNK_BYTES + 1), false)) },
                    { host.createPlaylistImport(PlaylistImportRequest("Name", text = "Song", links = listOf("https://example.test"), importLiked = false)) },
                    { host.createPlaylistImport(PlaylistImportRequest("Name", importLiked = false)) },
                    { host.bindPhone(PhoneBindingRequest("invalid", "86", "captcha")) },
                    { host.replacePhone(PhoneReplacementRequest("13800138000", "86", "captcha", "")) },
                    { host.sendResourceMessage(ResourceMessageRequest(emptyList(), "A", "song", "")) },
                    { host.getMediaQualities(MediaQualityRequest("A", "playlist")) },
                )
                invalid.forEachIndexed { index, invoke ->
                    assertFailsWith<IllegalArgumentException>("Invalid request $index") { invoke() }
                }
            } finally { host.close() }
        }
    }

    @Test
    fun `upload acknowledgements import task identities and duplicate qualities are validated`() {
        runBlocking {
            val host = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(
                    ExtendedCapability.UPLOADCLOUDCHUNK, ExtendedCapability.GETPLAYLISTIMPORTSTATUS,
                    ExtendedCapability.GETMEDIAQUALITIES, ExtendedCapability.PREPARECLOUDUPLOAD,
                ))
                override suspend fun uploadCloudChunk(request: CloudUploadChunkRequest) =
                    CloudUploadProgress(request.uploadId, request.offset, request.last)
                override suspend fun getPlaylistImportStatus(request: IdRequest) = PlaylistImportTask("wrong", "completed")
                override suspend fun getMediaQualities(request: MediaQualityRequest) = QualityPage(listOf(
                    QualityOption("lossless", "Lossless", available = true), QualityOption("lossless", "Other", available = true),
                ))
                override suspend fun prepareCloudUpload(request: CloudUploadPrepareRequest) =
                    CloudUploadSession("U", true, 0, MAX_UPLOAD_CHUNK_BYTES + 1)
            })
            try {
                val invalid: List<suspend () -> Any> = listOf(
                    { host.uploadCloudChunk(CloudUploadChunkRequest("U", 0, byteArrayOf(1), true)) },
                    { host.getPlaylistImportStatus(IdRequest("task")) },
                    { host.getMediaQualities(MediaQualityRequest("A", "song")) },
                    { host.prepareCloudUpload(CloudUploadPrepareRequest("song.flac", 1, "0".repeat(32), 128000)) },
                )
                invalid.forEach { invoke ->
                    assertEquals(FailureCategory.INVALID_RESPONSE, assertFailsWith<ProviderException> { invoke() }.failure.category)
                }
            } finally { host.close() }
        }
    }

    @Test
    fun `binary input is copied before asynchronous provider execution`() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val original = byteArrayOf(0, 127, -128, -1)
            val host = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(ExtendedCapability.UPLOADAVATAR))
                override suspend fun uploadAvatar(request: ImageUploadRequest): ImageUploadResult {
                    entered.complete(Unit)
                    resume.await()
                    assertContentEquals(byteArrayOf(0, 127, -128, -1), request.content)
                    return ImageUploadResult("image")
                }
            })
            try {
                val pending = async { host.uploadAvatar(ImageUploadRequest("avatar.png", "image/png", original)) }
                withTimeout(5000) { entered.await() }
                original.fill(42)
                resume.complete(Unit)
                assertEquals("image", pending.await().imageId)
            } finally { host.close() }
        }
    }

    @Test
    fun `QuickJS receives large typed binary chunks with unsigned byte fidelity`() {
        runBlocking {
            val runtime = object : ProviderRuntime {
                override suspend fun http(request: HttpRequest): HttpResponse = error("Unused")
                override suspend fun sha256(bytes: ByteArray): ByteArray = error("Unused")
                override suspend fun read(key: String): ByteArray? = null
                override suspend fun write(key: String, value: ByteArray?) {}
            }
            val script = """
                const uploads = new Map();
                globalThis.provider = {
                    capabilities: ["library.cloud.upload.prepare", "library.cloud.upload.chunk", "library.cloud.upload.complete"],
                    async prepareCloudUpload(request) {
                        uploads.set("session-upload", { offset: 0, size: request.sizeBytes, complete: false });
                        return { uploadId: "session-upload", needsUpload: true, offset: 0, chunkSize: 1048576 };
                    },
                    async uploadCloudChunk(request) {
                        const upload = uploads.get(request.uploadId);
                        if (!upload || upload.complete || request.offset !== upload.offset) throw Error("Invalid upload");
                        if (!(request.content instanceof Int8Array) && !(request.content instanceof Uint8Array))
                            throw Error("Expected typed bytes");
                        for (let i = 0; i < request.content.length; i++)
                            if ((request.content[i] & 255) !== (i & 255)) throw Error("Byte mismatch");
                        upload.offset += request.content.byteLength;
                        if (upload.offset > upload.size || request.last !== (upload.offset === upload.size))
                            throw Error("Invalid chunk boundary");
                        upload.complete = request.last;
                        return { uploadId: request.uploadId, offset: upload.offset, complete: upload.complete };
                    },
                    async completeCloudUpload(request) {
                        const upload = uploads.get(request.uploadId);
                        if (!upload || !upload.complete) throw Error("Not uploaded");
                        uploads.delete(request.uploadId);
                        return { success: true, id: "published-song" };
                    }
                };
            """.trimIndent()
            val host = host(JsSdkProvider(script, ProviderContext("migration", "account", emptyMap(), null, runtime)))
            val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
            try {
                val size = MAX_UPLOAD_CHUNK_BYTES
                val prepared = assertIs<BackendResult.Success<CloudUploadSession>>(source.prepareCloudUpload(
                    CloudUploadPrepareRequest("song.flac", size.toLong(), "0".repeat(32), 128000),
                )).data
                val bytes = ByteArray(size) { it.toByte() }
                val progress = assertIs<BackendResult.Success<CloudUploadProgress>>(source.uploadCloudChunk(
                    CloudUploadChunkRequest(prepared.uploadId, 0, bytes, true),
                )).data
                assertEquals(size.toLong(), progress.offset); assertTrue(progress.complete)
                assertEquals("published-song", assertIs<BackendResult.Success<MutationResult>>(
                    source.completeCloudUpload(CloudUploadCompleteRequest(prepared.uploadId, title = "Song")),
                ).data.id)
                assertIs<BackendResult.Error>(source.completeCloudUpload(CloudUploadCompleteRequest(prepared.uploadId)))
            } finally { host.close() }
        }
    }

    @Test
    fun `retiring a session cancels pending uploads without publishing success`() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val host = host(object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(ExtendedCapability.UPLOADCLOUDCHUNK))
                override suspend fun uploadCloudChunk(request: CloudUploadChunkRequest): CloudUploadProgress {
                    entered.complete(Unit); awaitCancellation()
                }
            })
            try {
                val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
                val pending = async { source.uploadCloudChunk(CloudUploadChunkRequest("U", 0, byteArrayOf(1), true)) }
                withTimeout(5000) { entered.await() }
                host.invalidate()
                assertFailsWith<CancellationException> { pending.await() }
            } finally { host.close() }
        }
    }

    private fun host(provider: MusicProvider) =
        ProviderHost(ProviderCallContext(ProviderSessionKey("migration", "account", 0)), SessionMusicProvider(provider))
}
