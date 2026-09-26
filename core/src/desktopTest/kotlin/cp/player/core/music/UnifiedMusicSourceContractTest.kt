package cp.player.core.music

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.local.LocalMediaSource
import cp.player.core.local.ScanProgress
import cp.player.core.media.LocalMediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `UnifiedMusicSource` 的**契约测试**。
 *
 * 钉住四件此前「说的和做的不一致」的事（详见 `docs/INTEGRATION_PLAN.md` §3.1）：
 *
 * 1. **畸形 mediaId 必须返回 `BackendResult.Error`，不能抛异常。**
 *    原先三处 `CPMediaId.parse()` 都在 `try` 之外，`IllegalArgumentException` 会直接抛给调用方
 *    —— 违反 `MusicBackend` KDoc 承诺的「可失败操作一律返回 BackendResult」，
 *    在对外 API 场景下表现为 500 而不是 400。
 *
 * 2. **`providers` / `providerId` 指定非活跃音源时必须显式报错。**
 *    KDoc 曾写「支持跨多个 provider 搜索并聚合」，但实现里该参数**从未被读取** ——
 *    调用方传参后拿到的是活跃 Provider 的结果，属静默错误结果。
 *    跨 Provider 聚合尚未实现，正确行为是报 `Unsupported`，而不是假装成功。
 *
 * 3. **两条解析路径对同一份上游 JSON 必须产出相同结果。**
 *    `MusicSourceFromApi` 与 `UnifiedMusicSourceImpl` 曾各有一份 `toTrackSummary` 且已分叉。
 *
 * 4. **本地曲目必须命中已登记条目（白名单）。**
 *    这是 `/stream` 对外暴露后的安全边界：裸路径不得伪装成可播放成功。
 */
class UnifiedMusicSourceContractTest {

    // ============ 测试替身 ============

    private class FakeLocalSource : LocalMediaSource {
        private val items = MutableStateFlow<List<LocalMediaItem>>(emptyList())
        private val scanning = MutableStateFlow(false)
        override suspend fun scan(): Flow<ScanProgress> = emptyFlow()
        override suspend fun importFolder(uri: String): Int = 0
        override fun removeItem(item: LocalMediaItem) {}
        override fun items(): StateFlow<List<LocalMediaItem>> = items
        override val isScanningFlow: StateFlow<Boolean> = scanning
        override fun addExternalItems(items: List<LocalMediaItem>) {}
    }

    private fun source(
        api: MusicApiService = throwingApi(),
        activeProviderId: String? = ACTIVE,
    ) = UnifiedMusicSourceImpl(
        musicApiService = api,
        localMusicSource = FakeLocalSource(),
        activeProviderId = { activeProviderId },
    )

    // ============ 1. 畸形 mediaId 不抛异常 ============

    @Test
    fun `畸形 mediaId 返回 Error 而不是抛异常`() = runBlocking {
        val s = source()

        val detail = s.getTrackDetail("这不是一个 mediaId")
        assertTrue(detail is BackendResult.Error, "应返回 Error，实际=$detail")

        // 有 "://" 但缺少 resourceType 分隔符
        val url = s.getSongUrl("netease://song")
        assertTrue(url is BackendResult.Error, "应返回 Error，实际=$url")
    }

    @Test
    fun `批量取详情遇到畸形 id 不抛异常`() = runBlocking {
        val s = source()

        val allBad = s.getTrackDetails(listOf("bad-one", "bad-two"))
        assertTrue(allBad is BackendResult.Error, "全部非法时应返回 Error，实际=$allBad")

        val empty = s.getTrackDetails(emptyList())
        assertTrue(empty is BackendResult.Success, "空输入应返回空成功，实际=$empty")
        assertEquals(0, empty.getOrNull()?.size)
    }

    // ============ 2. providers / providerId 不再被静默忽略 ============

    @Test
    fun `providers 指定非活跃音源时返回 Unsupported 而不是静默用活跃音源`() = runBlocking {
        val s = source(api = searchApi(SEARCH_JSON))

        val other = s.search("周杰伦", 1, listOf("qqmusic"))
        assertTrue(
            other is BackendResult.Unsupported,
            "指定非活跃音源必须报 Unsupported（曾经是静默返回活跃音源的结果），实际=$other",
        )
    }

    @Test
    fun `providers 为 null 或含活跃音源时照常搜索`() = runBlocking {
        val s = source(api = searchApi(SEARCH_JSON))

        val unspecified = s.search("周杰伦", 1, null)
        assertTrue(unspecified is BackendResult.Success, "null 应使用活跃音源，实际=$unspecified")

        val matching = s.search("周杰伦", 1, listOf(ACTIVE))
        assertTrue(matching is BackendResult.Success, "含活跃音源应放行，实际=$matching")
        assertEquals("七里香", matching.getOrNull()?.songs?.firstOrNull()?.name)
    }

    @Test
    fun `providers 为空列表时报错而不是当成不限`() = runBlocking {
        val s = source(api = searchApi(SEARCH_JSON))
        val result = s.search("周杰伦", 1, emptyList())
        assertTrue(result is BackendResult.Error, "空列表语义不明，应显式报错，实际=$result")
    }

    @Test
    fun `没有活跃音源时按 providers 限定会报错`() = runBlocking {
        val s = source(api = searchApi(SEARCH_JSON), activeProviderId = null)
        val result = s.search("周杰伦", 1, listOf(ACTIVE))
        assertTrue(result is BackendResult.Error, "无活跃音源无法判定，应报错，实际=$result")
    }

    @Test
    fun `getUserPlaylists 指定非活跃音源时返回 Unsupported`() = runBlocking {
        val s = source(api = searchApi(SEARCH_JSON))

        val other = s.getUserPlaylists("qqmusic", 123L)
        assertTrue(other is BackendResult.Unsupported, "应报 Unsupported，实际=$other")

        val blank = s.getUserPlaylists("", 123L)
        assertTrue(blank is BackendResult.Error, "空 providerId 应报错，实际=$blank")
    }

    // ============ 3. 两条解析路径结果一致 ============

    @Test
    fun `统一音源与搜索路径对同一份曲目 JSON 解析出相同字段`() = runBlocking {
        val s = source(api = detailApi(SONG_DETAIL_JSON))

        val viaUnified = s.getTrackDetail("netease://song/999").getOrNull()
            ?: fail("统一音源路径应能解析出曲目")
        val viaSearch = MusicSourceFromApi.parseSearchSongs(SEARCH_JSON).getOrNull()
            ?.songs?.firstOrNull()
            ?: fail("搜索路径应能解析出曲目")

        // id 语义不同是**有意为之**：统一音源用调用方给的带命名空间 mediaId，
        // 搜索路径用上游返回的裸 id。其余字段必须完全一致。
        assertEquals("netease://song/999", viaUnified.id)
        assertEquals("999", viaSearch.id)
        assertEquals(viaSearch.name, viaUnified.name)
        assertEquals(viaSearch.artist, viaUnified.artist)
        assertEquals(viaSearch.album, viaUnified.album)
        assertEquals(viaSearch.coverUrl, viaUnified.coverUrl)
        assertEquals(viaSearch.durationMs, viaUnified.durationMs)
    }

    @Test
    fun `字段回退键名 artists 与 album 与 duration 也能解析`() = runBlocking {
        val s = source(api = detailApi(FALLBACK_KEY_JSON))

        val track = s.getTrackDetail("netease://song/1").getOrNull()
            ?: fail("应能解析回退键名")
        assertEquals("回退键名", track.name)
        assertEquals("甲 / 乙", track.artist, "artists 数组应拼成 ' / ' 分隔")
        assertEquals("回退专辑", track.album)
        assertEquals(123_000L, track.durationMs, "duration 应作为 dt 的回退")
    }

    @Test
    fun `songId 与 song 可作为 id 与 name 的回退`() = runBlocking {
        // 这两条回退原先只存在于 MusicSourceFromApi 的那份拷贝里，
        // 合并到 TrackJsonMapper 后统一音源路径也应生效。
        val viaSearch = MusicSourceFromApi.parseSearchSongs(SONG_ID_FALLBACK_JSON).getOrNull()
            ?.songs?.firstOrNull()
            ?: fail("搜索路径应能解析出曲目")
        assertEquals("777", viaSearch.id)
        assertEquals("回退曲名", viaSearch.name)

        val s = source(api = detailApi(SONG_ID_FALLBACK_JSON))
        val unified = s.getTrackDetail("netease://song/777").getOrNull()
        assertEquals("回退曲名", unified?.name)
    }

    @Test
    fun `非 JSON 对象响应不会让批量解析抛出`() = runBlocking {
        val s = source(api = detailApi(Json.parseToJsonElement("[1,2,3]")))

        val result = s.getTrackDetails(listOf("netease://song/1"))
        // songs 字段取不到 ⇒ 空结果，但必须是 Success 而不是异常
        assertTrue(result is BackendResult.Success, "实际=$result")
        assertEquals(0, result.getOrNull()?.size)
    }

    // ============ 4. 本地曲目白名单（安全边界） ============

    @Test
    fun `本地曲目必须命中已登记条目，裸路径不得伪装成功`() = runBlocking {
        val s = source()

        val detail = s.getTrackDetail("local://audio//etc/passwd")
        assertTrue(detail is BackendResult.Error, "未登记的本地路径必须报错，实际=$detail")

        val url = s.getSongUrl("local://audio//etc/passwd")
        assertTrue(url is BackendResult.Error, "未登记的本地路径不得返回可播放地址，实际=$url")
    }

    // ============ 固定数据 ============

    private companion object {
        const val ACTIVE = "netease"

        /** 网易云风格：`ar` / `al` / `dt`。 */
        const val TRACK_NETEASE = """
            {
              "id": 999,
              "name": "七里香",
              "ar": [{"name": "周杰伦"}],
              "al": {"name": "七里香", "picUrl": "https://cdn/cover.jpg"},
              "dt": 269000
            }
        """

        val SONG_DETAIL_JSON: JsonElement =
            Json.parseToJsonElement("""{"code":200,"songs":[$TRACK_NETEASE]}""")

        val SEARCH_JSON: JsonElement =
            Json.parseToJsonElement("""{"code":200,"result":{"songs":[$TRACK_NETEASE]}}""")

        /** 通用风格：`artists` / `album` / `duration`。 */
        val FALLBACK_KEY_JSON: JsonElement = Json.parseToJsonElement(
            """
            {"code":200,"songs":[{
              "id": 1,
              "name": "回退键名",
              "artists": [{"name": "甲"}, {"name": "乙"}],
              "album": {"name": "回退专辑"},
              "duration": 123000
            }]}
            """
        )

        /** 只给 `songId` / `song`：检验 id 与 name 的回退。 */
        val SONG_ID_FALLBACK_JSON: JsonElement = Json.parseToJsonElement(
            """{"code":200,"songs":[{"songId":"777","song":"回退曲名","artists":[{"name":"丙"}]}]}"""
        )

        fun detailApi(json: JsonElement) = object : MusicApiService by throwingApi() {
            override suspend fun getSongDetail(ids: List<String>): JsonElement = json
        }

        fun searchApi(json: JsonElement) = object : MusicApiService by throwingApi() {
            override suspend fun search(keywords: String, type: Int): JsonElement = json
        }

        /**
         * [MusicApiService] 有 120+ 个方法，逐个实现没有意义。
         * 用「调用即抛」的代理做委托基类，再按需覆写本测试真正会走到的那几个方法。
         */
        @Suppress("UNCHECKED_CAST")
        fun throwingApi(): MusicApiService =
            java.lang.reflect.Proxy.newProxyInstance(
                MusicApiService::class.java.classLoader,
                arrayOf(MusicApiService::class.java),
            ) { _, method, _ ->
                throw UnsupportedOperationException("stub api: ${method.name}")
            } as MusicApiService
    }
}
