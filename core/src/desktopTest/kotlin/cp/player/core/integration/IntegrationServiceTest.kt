package cp.player.core.integration

import cp.player.core.BackendResult
import cp.player.core.control.LocalServerConfig
import cp.player.core.music.ArtistSummary
import cp.player.core.music.MusicResult
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.SearchResult
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.QueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用例层**只读**契约测试 —— 用 fake [UnifiedMusicSource]，**不起 HTTP**。
 *
 * 这里钉的是「领域结果 → 对外契约」的映射，重点是三类最容易写错的地方：
 *
 * 1. **错误分类**：`Unsupported` 必须是 `501`（换音源），不能和 `502`（重试）混为一谈；
 *    格式非法的 mediaId 必须在本机判死为 `400`，**不能**去碰上游。
 * 2. **批量语义**：单项失败不整体失败，且顺序与请求一致。
 * 3. **流地址**：必须给本机 `/stream` 地址（含令牌），绝不能把上游签名 URL 或 cookie 漏出去。
 *
 * **播控写路径不在这里**，在 `IntegrationPlaybackControlTest` —— 那里要断言的是
 * 「动作确实被派发到控制线程」，用到的脚手架（可观测的控制线程）与本文件完全不同。
 *
 * HTTP 层的接线由 `IntegrationDataApiTest` 端到端覆盖。
 *
 * ⚠️ **测试体写成 `fun x() = runBlocking { ... }` 时，最后一句的类型决定方法返回类型。**
 * 最后一句若是 `assertNotNull(...)`（它返回入参本身）或本文件的 `assertOk(...)`，
 * 方法就变成非 void，JUnit 抛 `InvalidTestClassError` 且**不告诉你是哪一行**
 * （只能在 `test-results` 的 XML 里看到方法名）。
 * 所以这里的辅助函数返回类型是刻意设计的：`assertFailure` 返回 `Unit`，
 * `assertOk` 只允许出现在赋值右侧。
 */
class IntegrationServiceTest {

    // ============ search ============

    @Test
    fun `关键词为空白时返回 400`() = runBlocking {
        val source = RecordingSource()
        val result = service(source).search(SearchRequestDto(keywords = "   "))

        assertFailure(FailureKind.BAD_REQUEST, result)
        assertTrue(source.searchCalls.isEmpty(), "参数就没过，不该触碰上游")
    }

    @Test
    fun `不支持的 type 返回 400 且不触碰上游`() = runBlocking {
        val source = RecordingSource()
        val result = service(source).search(SearchRequestDto(keywords = "周杰伦", type = 7))

        assertFailure(FailureKind.BAD_REQUEST, result)
        assertTrue(source.searchCalls.isEmpty(), "非法 type 必须本机判死")
    }

    @Test
    fun `limit 非正数返回 400`() = runBlocking {
        val result = service(RecordingSource()).search(SearchRequestDto(keywords = "x", limit = 0))
        assertFailure(FailureKind.BAD_REQUEST, result)
    }

    @Test
    fun `type 省略时按单曲搜索`() = runBlocking {
        val source = RecordingSource()
        service(source).search(SearchRequestDto(keywords = "周杰伦"))

        assertEquals(listOf("周杰伦" to SEARCH_TYPE_SONG), source.searchCalls)
    }

    @Test
    fun `limit 在本机截断而不是透传上游`() = runBlocking {
        val source = RecordingSource(
            searchResult = BackendResult.Success(
                SearchResult(
                    songs = (1..5).map { track("netease://song/$it") },
                    playlists = (1..5).map { playlist(it.toLong()) },
                    artists = (1..5).map { artist(it.toLong()) },
                )
            ),
        )

        val result = service(source).search(SearchRequestDto(keywords = "x", limit = 2))

        val ok = assertOk(result)
        assertEquals(2, ok.songs.size)
        assertEquals(2, ok.playlists.size)
        assertEquals(2, ok.artists.size)
        // 上游拿不到 limit —— 它没有分页参数，所以「截断」只可能发生在本机
        assertEquals(listOf("x" to SEARCH_TYPE_SONG), source.searchCalls)
    }

    @Test
    fun `上游 Unsupported 映射为 501 而不是 502`() = runBlocking {
        val source = RecordingSource(searchResult = BackendResult.Unsupported("该音源不支持搜索"))
        assertFailure(FailureKind.UNSUPPORTED, service(source).search(SearchRequestDto(keywords = "x")))
    }

    @Test
    fun `上游 Error 映射为 502 且保留原因`() = runBlocking {
        val source = RecordingSource(searchResult = BackendResult.Error("上游炸了"))
        val message = assertFailureMessage(
            FailureKind.UPSTREAM_FAILED,
            service(source).search(SearchRequestDto(keywords = "x")),
        )
        assertEquals("上游炸了", message, "上游原因要透出去，否则集成方无从定位")
    }

    @Test
    fun `搜索结果里的曲目带本机流地址与令牌`() = runBlocking {
        val source = RecordingSource(
            searchResult = BackendResult.Success(
                SearchResult(songs = listOf(track("netease://song/1")), playlists = emptyList(), artists = emptyList())
            ),
        )

        val song = assertOk(service(source).search(SearchRequestDto(keywords = "x"))).songs.single()

        assertEquals("netease://song/1", song.mediaId)
        assertEquals(
            "http://127.0.0.1:8080/stream?mediaId=netease%3A%2F%2Fsong%2F1&token=tok",
            song.streamUrl,
            "必须是本机 /stream 地址，且 mediaId 要转义",
        )
    }

    @Test
    fun `未配置令牌时流地址不带 token 参数`() = runBlocking {
        val source = RecordingSource(
            searchResult = BackendResult.Success(
                SearchResult(songs = listOf(track("netease://song/1")), playlists = emptyList(), artists = emptyList())
            ),
        )
        val config = LocalServerConfig(streamPort = 9090, accessToken = "")

        val song = assertOk(service(source, config = config).search(SearchRequestDto(keywords = "x"))).songs.single()

        assertEquals("http://127.0.0.1:9090/stream?mediaId=netease%3A%2F%2Fsong%2F1", song.streamUrl)
    }

    // ============ track ============

    @Test
    fun `畸形 mediaId 返回 400 且不触碰上游`() = runBlocking {
        val source = RecordingSource()
        val result = service(source).track("这不是一个-mediaId")

        assertFailure(FailureKind.BAD_REQUEST, result)
        assertTrue(source.detailCalls.isEmpty(), "格式错必须本机判死，不该变成一次上游请求")
    }

    @Test
    fun `空 mediaId 返回 400`() = runBlocking {
        assertFailure(FailureKind.BAD_REQUEST, service(RecordingSource()).track(""))
    }

    @Test
    fun `正常曲目返回 DTO 且带流地址`() = runBlocking {
        val source = RecordingSource(detailResult = { BackendResult.Success(track(it, name = "晴天")) })
        val dto = assertOk(service(source).track("netease://song/42"))

        assertEquals("netease://song/42", dto.mediaId)
        assertEquals("晴天", dto.name)
        assertEquals("歌手", dto.artist)
        assertEquals(1000L, dto.durationMs)
        assertNotNull(dto.streamUrl)
        assertTrue(dto.streamUrl.contains("mediaId=netease%3A%2F%2Fsong%2F42"), "流地址: ${dto.streamUrl}")
    }

    @Test
    fun `曲目不存在时是 502 而不是 404`() = runBlocking {
        // 领域层把「不存在」与「取数失败」都收敛成 BackendResult.Error，
        // 用例层无从区分 —— 这个测试把「当前是 502」钉住，避免有人以为契约里已有 404 而写分支。
        // 若将来领域层补齐了区分（Phase 4），这里会失败，正好提醒同步改文档与能力清单。
        val source = RecordingSource(detailResult = { BackendResult.Error("Song not found: $it") })
        assertFailure(FailureKind.UPSTREAM_FAILED, service(source).track("netease://song/404"))
    }

    @Test
    fun `音源不支持取详情时返回 501`() = runBlocking {
        val source = RecordingSource(detailResult = { BackendResult.Unsupported("apiMap 标记为 unsupported") })
        assertFailure(FailureKind.UNSUPPORTED, service(source).track("netease://song/1"))
    }

    @Test
    fun `id 为空的曲目不给流地址`() = runBlocking {
        val source = RecordingSource(detailResult = { BackendResult.Success(track(id = "")) })
        val dto = assertOk(service(source).track("netease://song/1"))
        assertNull(dto.streamUrl, "给一个必然 404 的地址比给 null 更糟")
    }

    // ============ tracks（批量） ============

    @Test
    fun `空数组返回 400`() = runBlocking {
        assertFailure(FailureKind.BAD_REQUEST, service(RecordingSource()).tracks(emptyList()))
    }

    @Test
    fun `超过单次上限返回 400`() = runBlocking {
        val tooMany = List(MAX_TRACK_BATCH_SIZE + 1) { "netease://song/$it" }
        assertFailure(FailureKind.BAD_REQUEST, service(RecordingSource()).tracks(tooMany))
    }

    @Test
    fun `畸形 id 逐项报错且不影响其它项`() = runBlocking {
        val source = RecordingSource(
            detailsResult = { ids -> BackendResult.Success(ids.map { track(it, name = "命中") }) },
        )

        val items = assertOk(service(source).tracks(listOf("bad-id", "netease://song/1")))

        assertEquals(2, items.size)
        assertNull(items[0].track)
        assertNotNull(items[0].error, "畸形 id 必须有逐项错误")
        assertNotNull(items[1].track, "同一批里的合法项不能受影响")
        assertNull(items[1].error)
        assertEquals(listOf(listOf("netease://song/1")), source.detailsCalls, "只把合法 id 送上游")
    }

    @Test
    fun `上游未返回的项标记 error 而不是整体失败`() = runBlocking {
        val source = RecordingSource(
            // 只回一条，模拟「上游没返回另一条」
            detailsResult = { BackendResult.Success(listOf(track("netease://song/1"))) },
        )

        val items = assertOk(service(source).tracks(listOf("netease://song/1", "netease://song/2")))

        assertNotNull(items[0].track)
        assertNull(items[1].track)
        assertNotNull(items[1].error)
        // 措辞必须承认歧义，不能假装知道是「不存在」
        assertTrue(items[1].error!!.contains("上游未返回"), "实际: ${items[1].error}")
    }

    @Test
    fun `整批失败时逐项报未返回而不是整体 502`() = runBlocking {
        val source = RecordingSource(detailsResult = { BackendResult.Error("上游 500") })

        val items = assertOk(service(source).tracks(listOf("netease://song/1", "netease://song/2")))

        assertEquals(2, items.size, "批量端点承诺『单项失败不整体失败』")
        assertTrue(items.all { it.track == null && it.error != null })
    }

    @Test
    fun `批量结果顺序与请求一致`() = runBlocking {
        val source = RecordingSource(
            // 上游返回顺序被打乱
            detailsResult = { BackendResult.Success(listOf(track("netease://song/3"), track("netease://song/1"))) },
        )

        val requested = listOf("netease://song/1", "netease://song/2", "netease://song/3")
        val items = assertOk(service(source).tracks(requested))

        assertEquals(requested, items.map { it.mediaId }, "顺序必须按请求，不能按上游返回")
        assertEquals("netease://song/1", items[0].track?.mediaId, "第一项应命中")
        assertNull(items[1].track, "上游没返回的第二项应缺 track")
        assertEquals("netease://song/3", items[2].track?.mediaId, "第三项应命中")
    }

    @Test
    fun `重复 id 只向上游请求一次`() = runBlocking {
        val source = RecordingSource(
            detailsResult = { ids -> BackendResult.Success(ids.map { track(it) }) },
        )

        val items = assertOk(service(source).tracks(listOf("netease://song/1", "netease://song/1")))

        assertEquals(2, items.size, "两项都要回，集成方按位置取值")
        assertEquals(listOf(listOf("netease://song/1")), source.detailsCalls, "去重后只发一次")
    }

    @Test
    fun `全部畸形时不去上游`() = runBlocking {
        val source = RecordingSource()
        val items = assertOk(service(source).tracks(listOf("bad", "worse")))
        assertTrue(items.all { it.error != null })
        assertTrue(source.detailsCalls.isEmpty())
    }

    // ============ playback ============

    @Test
    fun `播放状态映射当前曲目与队列长度`() = runBlocking {
        val playback = PlaybackUiState(
            currentTrack = track("netease://song/7", name = "七里香"),
            currentIndex = 0,
            queue = listOf(
                QueueItem("netease://song/7", "七里香", "周杰伦", null, null, 1000L),
                QueueItem("netease://song/8", "八", "周杰伦", null, null, 1000L),
            ),
            isPlaying = true,
            positionMs = 42_000L,
            qualityLevel = "lossless",
        )

        val dto = service(RecordingSource(), playback = playback).playback()

        assertTrue(dto.isPlaying)
        assertEquals(42_000L, dto.positionMs)
        assertEquals(0, dto.currentIndex)
        assertEquals("lossless", dto.qualityLevel)
        assertEquals(2, dto.queueLength)
        assertEquals("七里香", dto.currentTrack?.name)
        assertEquals(
            "http://127.0.0.1:8080/stream?mediaId=netease%3A%2F%2Fsong%2F7&token=tok",
            dto.currentTrack?.streamUrl,
            "当前曲目也要带本机流地址",
        )
    }

    @Test
    fun `没有当前曲目时 currentTrack 为 null`() = runBlocking {
        val dto = service(RecordingSource()).playback()

        assertNull(dto.currentTrack)
        assertEquals(0, dto.queueLength)
        assertEquals(-1, dto.currentIndex)
    }

    // ============ 流地址的广播地址处理 ============

    @Test
    fun `绑定 0_0_0_0 时流地址不用不可连接的 0_0_0_0`() = runBlocking {
        val source = RecordingSource(
            searchResult = BackendResult.Success(
                SearchResult(songs = listOf(track("netease://song/1")), playlists = emptyList(), artists = emptyList())
            ),
        )
        val config = LocalServerConfig(
            bindAddress = LocalServerConfig.BIND_ALL,
            streamPort = 8080,
            accessToken = "tok",
        )

        val url = assertOk(service(source, config = config).search(SearchRequestDto(keywords = "x"))).songs.single().streamUrl

        assertNotNull(url)
        assertTrue(!url.contains("0.0.0.0"), "0.0.0.0 不是可连接地址，不能下发: $url")
        assertTrue(url.endsWith("/stream?mediaId=netease%3A%2F%2Fsong%2F1&token=tok"), "流地址: $url")
    }

    // ============ FailureKind → HTTP 状态码映射表 ============

    @Test
    fun `失败分类到状态码与错误码的映射表`() {
        assertEquals(400 to ApiErrorCodes.BAD_REQUEST, FailureKind.BAD_REQUEST.statusAndCode())
        assertEquals(401 to ApiErrorCodes.UNAUTHORIZED, FailureKind.UNAUTHORIZED.statusAndCode())
        assertEquals(404 to ApiErrorCodes.NOT_FOUND, FailureKind.NOT_FOUND.statusAndCode())
        assertEquals(501 to ApiErrorCodes.UNSUPPORTED, FailureKind.UNSUPPORTED.statusAndCode())
        assertEquals(502 to ApiErrorCodes.UPSTREAM_FAILED, FailureKind.UPSTREAM_FAILED.statusAndCode())
        assertEquals(500 to ApiErrorCodes.INTERNAL, FailureKind.INTERNAL.statusAndCode())
    }

    @Test
    fun `不支持的音源与上游故障必须是不同状态码`() {
        assertTrue(
            FailureKind.UNSUPPORTED.httpStatus != FailureKind.UPSTREAM_FAILED.httpStatus,
            "『换音源』与『重试』是两种处置，混成一个码等于逼集成方瞎试",
        )
    }

    // ============ 脚手架 ============

    private fun FailureKind.statusAndCode() = httpStatus to errorCode

    private fun service(
        source: UnifiedMusicSource,
        playback: PlaybackUiState = PlaybackUiState(),
        config: LocalServerConfig = LocalServerConfig(streamPort = 8080, accessToken = "tok"),
    ): IntegrationService {
        // 一个 service 实例配**一个**状态流。若写成 `playbackState = { MutableStateFlow(playback) }`，
        // 每次调用都返回新对象 —— 事件流端点会订阅到一个永远不发射变化的流。
        val state = MutableStateFlow(playback)
        return IntegrationService(
            source = { source },
            playbackState = { state },
            // 本文件只钉只读用例；写路径（含线程 marshalling）在 IntegrationPlaybackControlTest
            playbackControl = { NoopPlaybackControl },
            controlDispatcher = Dispatchers.Unconfined,
            availableProviders = { emptyList() },
            activeProviderId = { null },
            loggedIn = { false },
            config = { config },
        )
    }

    /**
     * 只读用例不该触发播控。真被调到了说明测试写错了，所以直接让测试失败 ——
     * 静默空实现会把「某只读路径意外写了播放状态」这种真 bug 掩盖掉。
     */
    private object NoopPlaybackControl : IntegrationPlaybackControl {
        override suspend fun play() {
            kotlin.test.fail("只读用例不应触发播控：play")
        }

        override suspend fun pause() {
            kotlin.test.fail("只读用例不应触发播控：pause")
        }

        override suspend fun next() {
            kotlin.test.fail("只读用例不应触发播控：next")
        }

        override suspend fun previous() {
            kotlin.test.fail("只读用例不应触发播控：previous")
        }
    }

    private fun <T> assertOk(result: IntegrationResult<T>): T = when (result) {
        is IntegrationResult.Ok -> result.value
        is IntegrationResult.Failure ->
            kotlin.test.fail("期望成功，实际失败：${result.kind} / ${result.message}")
    }

    /**
     * 断言失败分类。
     *
     * 刻意返回 `Unit`：测试体是 `fun x() = runBlocking { ... }`，
     * **最后一句的类型决定方法返回类型**，最后一句返回非 Unit 会让 JUnit 报
     * `InvalidTestClassError`，而且**不告诉你是哪一行**。
     * 需要 message 时用 [assertFailureMessage]。
     */
    private fun <T> assertFailure(kind: FailureKind, result: IntegrationResult<T>) {
        assertFailureMessage(kind, result)
    }

    /** 断言失败分类并返回 message，供进一步断言。 */
    private fun <T> assertFailureMessage(kind: FailureKind, result: IntegrationResult<T>): String =
        when (result) {
            is IntegrationResult.Ok -> kotlin.test.fail("期望失败（$kind），实际成功：${result.value}")
            is IntegrationResult.Failure -> {
                assertEquals(kind, result.kind, "失败分类不符：${result.message}")
                assertTrue(result.message.isNotBlank(), "失败必须带可读原因")
                result.message
            }
        }

    private fun track(id: String, name: String = "曲目") = TrackSummary(
        id = id,
        name = name,
        artist = "歌手",
        album = "专辑",
        coverUrl = "https://cover.example/x.jpg",
        durationMs = 1000L,
    )

    private fun playlist(id: Long) = PlaylistSummary(id, "歌单$id", null, 10, "创建者")

    private fun artist(id: Long) = ArtistSummary(id, "歌手$id", null)

    /** 记录调用参数，便于断言「哪些请求本机就拦下了」。 */
    private class RecordingSource(
        var detailResult: (String) -> MusicResult<TrackSummary> = { BackendResult.Error("测试未配置 detailResult") },
        var detailsResult: (List<String>) -> MusicResult<List<TrackSummary>> = { BackendResult.Success(emptyList()) },
        var searchResult: MusicResult<SearchResult> =
            BackendResult.Success(SearchResult(emptyList(), emptyList(), emptyList())),
    ) : UnifiedMusicSource {

        val detailCalls = mutableListOf<String>()
        val detailsCalls = mutableListOf<List<String>>()
        val searchCalls = mutableListOf<Pair<String, Int>>()

        override suspend fun getTrackDetail(mediaId: String): MusicResult<TrackSummary> {
            detailCalls += mediaId
            return detailResult(mediaId)
        }

        override suspend fun getTrackDetails(mediaIds: List<String>): MusicResult<List<TrackSummary>> {
            detailsCalls += mediaIds
            return detailsResult(mediaIds)
        }

        override suspend fun getSongUrl(mediaId: String, level: String): MusicResult<SongUrl> =
            BackendResult.Error("本测试不使用 getSongUrl")

        override suspend fun search(keywords: String, type: Int, providers: List<String>?): MusicResult<SearchResult> {
            searchCalls += keywords to type
            return searchResult
        }

        override suspend fun getUserPlaylists(providerId: String, uid: Long): MusicResult<List<PlaylistSummary>> =
            BackendResult.Error("本测试不使用 getUserPlaylists")
    }
}
