package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 歌词归属：切歌的**每一条入口**都必须在切歌瞬间把上一首的词清掉。
 *
 * ### 修的病灶
 * 「歌词该不该重取」此前由两个间接判据决定：
 * 1. `playCurrent` 里 `currentTrack?.id == summary?.id` —— 而 `TrackSummary.id`
 *    在「列表 / 云盘裸 id」「getTrackDetail 的 mediaId」「本地 originalMediaId」
 *    三条路径上形态不同，判等会随解析先后摇摆；
 * 2. `resolveEntry` 里 `lyrics is LyricsState.Idle` —— 残留的 `Success` 不满足条件，
 *    于是新曲目**永远不发起取词**，界面一直挂着上一首的词。
 *
 * 第 2 条正是「恢复上次播放」与「一起听远端替换队列」两个入口上必现的不切换：
 * 它们都不经过 `playCurrent` 的换曲分支，只能靠 resolve 兜底。
 *
 * ### 现在钉住的契约
 * 歌词归属是显式的（`lyricsMediaId`）：换队列 / 换曲必须改写它，取词结果只能认领
 * 自己那一首；判据不再看「状态是不是 Idle」，也不再看 `TrackSummary.id`。
 */
class LyricsOwnershipTest {

    private class FakePlatformPlayer : PlatformPlayer {
        override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)

        override suspend fun load(
            url: String,
            startPositionMs: Long,
            headers: Map<String, String>,
            metadata: PlaybackMetadata?,
        ) {
            state.value = PlatformPlaybackState.Playing
        }
        override fun play() { state.value = PlatformPlaybackState.Playing }
        override fun pause() { state.value = PlatformPlaybackState.Paused }
        override fun seekTo(positionMs: Long) { this.positionMs.value = positionMs }
        override fun stop() { state.value = PlatformPlaybackState.Idle }
        override fun release() {}
        override fun setVolume(volume: Float) {}
        override fun getVolume(): Float = 1f
    }

    /**
     * 队列解析（`getTrackDetails`）返回**裸 id** 的 summary —— 与云盘 / 搜索路径一致。
     *
     * 刻意让 `getTrackDetail`（单曲）返回带命名空间的 mediaId，复现「同一首在两条
     * 解析路径上 id 形态不同」这个真实分叉。
     */
    private class BareIdListSource : UnifiedMusicSource {
        override suspend fun getTrackDetail(mediaId: String) =
            BackendResult.Success(summaryOf(mediaId, mediaId))

        override suspend fun getTrackDetails(mediaIds: List<String>) =
            BackendResult.Success(mediaIds.map { summaryOf(it, bareOf(it)) })

        override suspend fun getSongUrl(mediaId: String, level: String) = BackendResult.Success(
            SongUrl("https://cdn/${bareOf(mediaId)}", level, null, null),
        )
        override suspend fun search(keywords: String, type: Int, providers: List<String>?) =
            BackendResult.Error("not used")
        override suspend fun getUserPlaylists(providerId: String, uid: Long) =
            BackendResult.Error("not used")
    }

    /** 每个 songId 返回一首不同的词，用行文本区分「是哪一首的词」。 */
    private class LyricApi : MusicApiService by StubApi {
        override suspend fun getLyric(songId: String): JsonElement = JsonObject(
            mapOf("lrc" to JsonObject(mapOf("lyric" to JsonPrimitive("[00:00.00]词-$songId")))),
        )

        private companion object {
            @Suppress("UNCHECKED_CAST")
            val StubApi: MusicApiService = java.lang.reflect.Proxy.newProxyInstance(
                MusicApiService::class.java.classLoader,
                arrayOf(MusicApiService::class.java),
            ) { _, method, _ ->
                throw UnsupportedOperationException("stub api: ${method.name}")
            } as MusicApiService
        }
    }

    private fun controller(source: UnifiedMusicSource = BareIdListSource()): PlaybackControllerImpl =
        PlaybackControllerImpl(
            platform = FakePlatformPlayer(),
            source = source,
            api = LyricApi(),
            cookieProvider = { null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

    /** 当前状态里的歌词文本（无词返回 null）。 */
    private fun PlaybackUiState.lyricText(): String? =
        (lyrics as? LyricsState.Success)?.lines?.firstOrNull()?.text

    /**
     * **恢复上次播放**：只摆队列、不 playCurrent。
     *
     * 旧实现里这条路径的取词全靠 `resolveEntry` 的 `lyrics is Idle` 守卫；
     * 只要状态里残留着上一首的 `Success`，新曲目就永远不取词。
     * 本用例先放一首词进去，再 setQueue 换到另一首，断言歌词**不再属于上一首**。
     */
    @Test
    fun `setQueue 换歌后必须清掉上一首的歌词`() = runBlocking {
        val c = controller()
        c.playQueue(listOf(mid(1)), startIndex = 0)
        val before = c.state.value.lyricText()
        assertEquals("词-1", before, "第一首应取到自己的词")

        // 换队列（恢复上次播放 / 一起听远端替换都走这条）：不装载，只摆队列。
        c.setQueue(listOf(mid(99), mid(1)), startIndex = 0)

        val after = c.state.value.lyricText()
        assertTrue(
            after != before,
            "换了队列不得继续显示上一首的词，实际还挂着：$after",
        )
    }

    /**
     * 云盘式 id 分叉：列表解析给出裸 id、单曲解析给出 mediaId。
     *
     * 旧实现的 `sameSong` 拿 `TrackSummary.id` 判等，在这种分叉下会判成「不是同一首」
     * 或「是同一首」来回摇摆 —— 云盘曲必现、本地曲随机。
     * 现在同一 mediaId 重播必须识别为同一首（不重复清空），换到另一首必须识别为不同首。
     */
    @Test
    fun `同一 mediaId 重播不应清掉已有歌词`() = runBlocking {
        val c = controller()
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 0)
        assertEquals("词-1", c.state.value.lyricText())

        // 重播同一首（索引不变、mediaId 不变）。
        c.playAt(0)

        assertEquals(
            "词-1",
            c.state.value.lyricText(),
            "同一首重播不该把歌词打成 Loading / 清空，那会闪一下",
        )
    }

    @Test
    fun `切到另一首必须换成新曲目的歌词`() = runBlocking {
        val c = controller()
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 0)
        assertEquals("词-1", c.state.value.lyricText())

        c.playAt(1)

        assertEquals(
            "词-2",
            c.state.value.lyricText(),
            "切到第 2 首后歌词必须是第 2 首的，不能残留第 1 首",
        )
    }
}

private fun mid(n: Int) = "netease://song/$n"

/** `netease://song/2` → `"2"`：模拟列表 / 云盘 / 搜索路径给出的**裸 id**。 */
private fun bareOf(mediaId: String) = mediaId.substringAfterLast('/')

private fun summaryOf(id: String, trackId: String) = TrackSummary(
    id = trackId,
    name = "曲目 $trackId",
    artist = "测试歌手",
    album = "测试专辑",
    coverUrl = null,
    durationMs = 200_000L,
)
