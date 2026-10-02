package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.core.api.MusicApiService
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.testing.ManualDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 播控导航回归测试。
 *
 * 覆盖本次修复的几条关键路径：
 * 1. 播放队列定位正确；
 * 2. 自然播完自动续播下一首；
 * 3. **「上一首播完」与「用户点下一首」同帧并发时只前进一首**（[PlaybackControllerImpl.navigationSeq] 守卫）；
 * 4. **过期的 Ended 不会让单曲循环把新曲目归零重播**（守卫的入口分支，单测互补）；
 * 5. seek / skipPrevious 正确委派给平台播放器。
 *
 * 第 3、4 条是核心：生产环境 `scope` 挂在 `Dispatchers.Main`（单线程队列），
 * 所以用 [ManualDispatcher] 精确复现「事件已入队、用户操作先执行」的交错顺序。
 */
class PlaybackControllerNavigationTest {

    // ============ 测试替身 ============

    private class FakePlatformPlayer : PlatformPlayer {
        override val state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val formatInfo = MutableStateFlow<AudioFormatInfo?>(null)

        val loadedUrls = mutableListOf<String>()
        val seekCalls = mutableListOf<Long>()
        var stopCount = 0

        override suspend fun load(
            url: String,
            startPositionMs: Long,
            headers: Map<String, String>,
            metadata: PlaybackMetadata?,
        ) {
            loadedUrls += url
            state.value = PlatformPlaybackState.Playing
        }

        override fun play() { state.value = PlatformPlaybackState.Playing }
        override fun pause() { state.value = PlatformPlaybackState.Paused }
        override fun seekTo(positionMs: Long) {
            seekCalls += positionMs
            this.positionMs.value = positionMs
        }
        override fun stop() { stopCount++; state.value = PlatformPlaybackState.Idle }
        override fun release() {}
        override fun setVolume(volume: Float) {}
        override fun getVolume(): Float = 1f

        /** 模拟底层播放器上报「自然播完」。 */
        fun emitEnded() { state.value = PlatformPlaybackState.Ended }
    }

    private class FakeSource : UnifiedMusicSource {
        override suspend fun getTrackDetail(mediaId: String) = BackendResult.Success(summaryOf(mediaId))

        override suspend fun getTrackDetails(mediaIds: List<String>) =
            BackendResult.Success(mediaIds.map { summaryOf(it) })

        override suspend fun getSongUrl(mediaId: String, level: String) = BackendResult.Success(
            SongUrl(
                url = "https://cdn/${mediaId.substringAfterLast('/')}",
                level = level,
                sizeBytes = null,
                expireAt = null,
            ),
        )

        override suspend fun search(keywords: String, type: Int, providers: List<String>?) =
            BackendResult.Error("not used in this test")

        override suspend fun getUserPlaylists(providerId: String, uid: Long) =
            BackendResult.Error("not used in this test")
    }

    /**
     * [MusicApiService] 有 120+ 个 suspend 方法，逐个实现没有意义。
     * 控制器里所有调用点都被 `runCatching` / `try-catch` 包住，
     * 因此一个「调用即抛」的代理完全够用。
     */
    @Suppress("UNCHECKED_CAST")
    private fun throwingApi(): MusicApiService =
        java.lang.reflect.Proxy.newProxyInstance(
            MusicApiService::class.java.classLoader,
            arrayOf(MusicApiService::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("stub api: ${method.name}")
        } as MusicApiService

    private fun controller(
        platform: PlatformPlayer,
        source: UnifiedMusicSource,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): PlaybackControllerImpl = PlaybackControllerImpl(
        platform = platform,
        source = source,
        api = throwingApi(),
        cookieProvider = { null },
        scope = CoroutineScope(SupervisorJob() + dispatcher),
    )

    // ============ 测试 ============

    @Test
    fun `playQueue loads the requested index`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())

        c.playQueue(listOf(mid(1), mid(2), mid(3)), startIndex = 1)

        assertEquals(1, c.state.value.currentIndex)
        assertEquals("https://cdn/2", platform.loadedUrls.last())
    }

    @Test
    fun `natural Ended auto-advances to the next track`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2), mid(3)), startIndex = 0)

        platform.emitEnded()

        assertEquals(1, c.state.value.currentIndex)
        assertEquals("https://cdn/2", platform.loadedUrls.last())
    }

    @Test
    fun `Ended racing with a manual skip advances only one track`() {
        val platform = FakePlatformPlayer()
        val dispatcher = ManualDispatcher()
        val c = controller(platform, FakeSource(), dispatcher)

        runBlocking { c.playQueue(listOf(mid(1), mid(2), mid(3)), startIndex = 0) }
        dispatcher.drain()
        assertEquals(0, c.state.value.currentIndex)

        // 同一帧内：上一首刚好播完（事件入队），用户同时点了「下一首」。
        platform.emitEnded()
        c.skipNext()
        dispatcher.drain()

        // 修复前会连跳两首（→ 2）；修复后两者只生效一个。
        //
        // 这条实际锁定的是 onTrackEnded 里 `navMutex` 内的二次世代校验
        // （实测：仅关掉入口守卫时本用例仍然通过，只有连内层一起关掉才会退化成 → 2）。
        assertEquals(1, c.state.value.currentIndex, "并发下只应前进一首")
        assertEquals(2, platform.loadedUrls.size, "只应加载第 1、2 首，不应把第 3 首也拉起来")
    }

    /**
     * 与上一条互补：这里锁定的是 [PlaybackControllerImpl.onTrackEnded] **入口处**的世代守卫。
     *
     * 单曲循环分支（`RepeatMode.ONE`）不经过 `navMutex` 内的二次校验，
     * 因此「过期的 Ended」一旦漏进入口，就会对**用户刚切过去的新曲目**执行
     * `seekTo(0) + play()`——表现为切歌后立刻从头重播。
     * 这条测试专门堵住这个入口。
     */
    @Test
    fun `stale Ended after a manual skip does not restart the new track in repeat-one`() {
        val platform = FakePlatformPlayer()
        val dispatcher = ManualDispatcher()
        val c = controller(platform, FakeSource(), dispatcher)

        runBlocking { c.playQueue(listOf(mid(1), mid(2), mid(3)), startIndex = 0) }
        dispatcher.drain()
        c.setRepeatMode(RepeatMode.ONE)

        // 上一首的 Ended 已入队，用户抢先点了「下一首」——这个 Ended 已经属于过去那一首。
        platform.emitEnded()
        c.skipNext()
        dispatcher.drain()

        assertEquals(1, c.state.value.currentIndex, "应停在用户选择的曲目上")
        assertTrue(
            platform.seekCalls.isEmpty(),
            "过期的 Ended 不应触发单曲循环的归零重播，实际 seek=${platform.seekCalls}",
        )
        assertEquals(2, platform.loadedUrls.size, "只应加载两首")
    }

    @Test
    fun `seekTo is forwarded to the platform`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 0)

        c.seekTo(12_345L)

        assertEquals(listOf(12_345L), platform.seekCalls)
    }

    @Test
    fun `skipPrevious restarts the current track when past three seconds`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 1)

        platform.positionMs.value = 10_000L
        c.skipPrevious()

        assertEquals(1, c.state.value.currentIndex, "超过 3 秒应回到本曲开头，而不是上一首")
        assertTrue(platform.seekCalls.contains(0L))
    }

    @Test
    fun `skipNext at the end of the queue with repeat off stops playback`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 0)
        c.playAt(1)

        c.skipNext()

        assertTrue(platform.stopCount > 0, "队列末尾应停止播放")
        assertFalse(c.state.value.isPlaying)
    }

    // ============ 第二组：边界与重复导航 ============

    @Test
    fun `skipPrevious within three seconds goes to the previous track`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 1)

        platform.positionMs.value = 1_000L
        c.skipPrevious()

        assertEquals(0, c.state.value.currentIndex, "3 秒内应切到上一首")
        assertTrue(platform.seekCalls.isEmpty(), "3 秒内不应回退本曲开头")
    }

    @Test
    fun `skipNext at the end with repeat all wraps to the first track`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 0)
        c.playAt(1)
        c.setRepeatMode(RepeatMode.ALL)

        c.skipNext()

        assertEquals(0, c.state.value.currentIndex, "列表循环下末尾应回到第一首")
        assertEquals("https://cdn/1", platform.loadedUrls.last())
    }

    @Test
    fun `Ended at the end with repeat all wraps to the first track`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 0)
        c.playAt(1)
        c.setRepeatMode(RepeatMode.ALL)

        platform.emitEnded()

        assertEquals(0, c.state.value.currentIndex, "自动续播也应按列表循环回绕")
        assertEquals("https://cdn/1", platform.loadedUrls.last())
    }

    @Test
    fun `playAt with an out of range index does not change the current track`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 0)

        c.playAt(99)

        assertEquals(0, c.state.value.currentIndex, "越界下标不应改变当前曲目")
        assertEquals(2, c.state.value.queue.size)
    }

    @Test
    fun `seekTo forwards the most recent target when called twice`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 0)

        c.seekTo(1_000L)
        c.seekTo(2_000L)

        assertEquals(listOf(1_000L, 2_000L), platform.seekCalls)
    }

    @Test
    fun `removing the current track leaves a valid current index`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2), mid(3)), startIndex = 0)
        c.playAt(1)

        c.removeQueueItem(1)

        assertEquals(2, c.state.value.queue.size)
        assertTrue(
            c.state.value.currentIndex in 0..1,
            "移除当前曲目后 currentIndex 必须落在新队列范围内，实际=${c.state.value.currentIndex}",
        )
    }

    @Test
    fun `shuffle keeps the current track and skip follows the shuffled order`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2), mid(3)), startIndex = 0)
        c.playAt(1)

        c.toggleShuffle()

        assertTrue(c.state.value.shuffleEnabled)
        assertEquals(1, c.state.value.currentIndex, "开启随机不应改变当前曲目")

        // 用列表循环避免"随机顺序末尾"这个边界，专注验证跳转走的是随机序而非自然序。
        c.setRepeatMode(RepeatMode.ALL)
        c.skipNext()

        val next = c.state.value.currentIndex
        assertTrue(next in 0..2, "跳转后下标应合法，实际=$next")
        assertTrue(next != 1, "随机序下不应停在原曲目，实际=$next")
    }

    // ============ 随机播放：顺序必须始终是队列的一个置换 ============

    /**
     * 开启列表循环后连按 [steps] 次「下一首」，返回每次落到的曲目下标。
     *
     * [PlaybackControllerImpl] 内的随机播放顺序（元素 = 队列下标）必须**恰好是队列的置换**：
     * 每首歌占一个槽位，不多不少。一轮完整遍历会覆盖每一首恰好一次 ——
     * 这条不变式比肉眼观察「顺序随机吗」强得多，且不受随机种子影响。
     */
    private suspend fun PlaybackControllerImpl.walkNext(steps: Int): List<Int> {
        val visited = mutableListOf<Int>()
        repeat(steps) {
            skipNext()
            visited += state.value.currentIndex
        }
        return visited
    }

    @Test
    fun `turning shuffle off resumes the natural order from the current track`() = runBlocking {
        val c = controller(FakePlatformPlayer(), FakeSource())
        c.playQueue(listOf(mid(1), mid(2), mid(3), mid(4), mid(5)), startIndex = 0)
        c.playAt(2)

        c.toggleShuffle()
        c.toggleShuffle()
        assertFalse(c.state.value.shuffleEnabled)

        c.skipNext()
        assertEquals(3, c.state.value.currentIndex, "关闭随机后应从当前曲的自然后继继续")
    }

    @Test
    fun `a full round under shuffle visits every queue entry exactly once`() = runBlocking {
        val c = controller(FakePlatformPlayer(), FakeSource())
        c.playQueue(listOf(mid(1), mid(2), mid(3), mid(4), mid(5)), startIndex = 3)
        c.toggleShuffle()
        c.setRepeatMode(RepeatMode.ALL)

        val visited = c.walkNext(5)

        assertEquals(listOf(0, 1, 2, 3, 4), visited.sorted(), "一轮随机应覆盖全部 5 首各一次")
    }

    @Test
    fun `adding to the queue while shuffle is on keeps one slot per track`() = runBlocking {
        val c = controller(FakePlatformPlayer(), FakeSource())
        c.playQueue(listOf(mid(1), mid(2), mid(3)), startIndex = 0)
        c.toggleShuffle()
        c.setRepeatMode(RepeatMode.ALL)

        c.addToQueue(mid(4))

        val visited = c.walkNext(4)
        assertEquals(listOf(0, 1, 2, 3), visited.sorted(), "加曲后每首仍应只占一个槽位")
    }

    @Test
    fun `removing a track while shuffle is on keeps one slot per track`() = runBlocking {
        val c = controller(FakePlatformPlayer(), FakeSource())
        c.playQueue(listOf(mid(1), mid(2), mid(3), mid(4)), startIndex = 0)
        c.toggleShuffle()
        c.setRepeatMode(RepeatMode.ALL)

        c.removeQueueItem(2)

        val visited = c.walkNext(3)
        assertEquals(listOf(0, 1, 2), visited.sorted(), "删曲后剩余 3 首应各占一个槽位")
    }

    @Test
    fun `dragging a track while shuffle is on keeps one slot per track`() = runBlocking {
        val c = controller(FakePlatformPlayer(), FakeSource())
        c.playQueue(listOf(mid(1), mid(2), mid(3), mid(4)), startIndex = 1)
        c.toggleShuffle()
        c.setRepeatMode(RepeatMode.ALL)

        c.moveQueueItem(from = 0, to = 3)

        val visited = c.walkNext(4)
        assertEquals(listOf(0, 1, 2, 3), visited.sorted(), "拖拽重建顺序后每首应只占一个槽位")
    }

    /**
     * 随机**开着**清空队列、再逐曲添加回来 —— 这是最容易踩的路径：
     * 清队只置空顺序、并不关掉随机开关，此时「补一个槽位」的算错会让新曲占两个位置，
     * 而游标按首次匹配定位 ⇒ 同一首被反复播放、后面的曲再也轮不到。
     */
    @Test
    fun `queue rebuilt by hand while shuffle is on stays a clean permutation`() = runBlocking {
        val c = controller(FakePlatformPlayer(), FakeSource())
        c.playQueue(listOf(mid(1)), startIndex = 0)
        c.toggleShuffle()

        c.clearQueue()
        c.addToQueue(mid(2))
        c.addToQueue(mid(3))
        c.playAt(0)
        c.setRepeatMode(RepeatMode.ALL)

        val visited = c.walkNext(2)
        assertEquals(listOf(0, 1), visited.sorted(), "两首排队应各播一次，实际=$visited")
    }

    @Test
    fun `dragging in a not-yet-started queue injects no bogus slot`() = runBlocking {
        val c = controller(FakePlatformPlayer(), FakeSource())
        c.playQueue(listOf(mid(1)), startIndex = 0)
        c.toggleShuffle()

        c.clearQueue()
        c.addToQueue(mid(2))
        c.addToQueue(mid(3))
        c.addToQueue(mid(4))
        // 尚未起播（没有当前曲目）时拖拽，不能把 -1 当成顺序里的一个槽位。
        c.moveQueueItem(from = 0, to = 2)
        c.playAt(1)
        c.setRepeatMode(RepeatMode.ALL)

        val visited = c.walkNext(3)
        assertEquals(listOf(0, 1, 2), visited.sorted(), "三首排队应各播一次，实际=$visited")
    }

    // ============ 加曲与队列去重 ============

    /**
     * 队列条目按 mediaId 唯一（队列弹层以它做 LazyColumn 的 key）。
     * 私人FM 的整批热门歌与用户搜索高度重叠：重复添加会直接炸掉队列 UI，
     * 也会让同一首连播两遍。
     */
    @Test
    fun `addToQueue skips a track that is already queued`() = runBlocking {
        val c = controller(FakePlatformPlayer(), FakeSource())
        c.playQueue(listOf(mid(1), mid(2)), startIndex = 0)

        c.addToQueue(mid(2))
        c.addToQueue(mid(3))

        assertEquals(
            listOf(mid(1), mid(2), mid(3)),
            c.state.value.queue.map { it.mediaId },
            "已在队列里的歌不应被再次追加",
        )
    }

    /** 电台/长队列场景：加进来的歌必须本曲播完就轮到，而不是排在几十首之后。 */
    @Test
    fun `addNextToQueue inserts right after the current track`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2), mid(3), mid(4)), startIndex = 0)

        c.addNextToQueue(mid(9))

        assertEquals(
            listOf(mid(1), mid(9), mid(2), mid(3), mid(4)),
            c.state.value.queue.map { it.mediaId },
        )
        platform.emitEnded()
        assertEquals(1, c.state.value.currentIndex, "本曲播完应立刻轮到插播的歌")
        assertEquals(mid(9), c.state.value.currentTrack?.id)
    }

    /** 随机模式下「下一首播放」同样要插在当前曲的下一个播放位。 */
    @Test
    fun `addNextToQueue under shuffle still plays the added song next`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2), mid(3), mid(4), mid(5)), startIndex = 0)
        c.toggleShuffle()
        // 列表循环：随机序可能恰好把当前曲排在末尾，不循环的话 walk 会在队尾停住。
        c.setRepeatMode(RepeatMode.ALL)

        c.addNextToQueue(mid(9))

        platform.emitEnded()
        assertEquals(mid(9), c.state.value.currentTrack?.id, "随机序下插播歌也应紧随当前曲")

        // 随机序仍然必须是队列的置换：新曲占一个槽位，其余曲各轮到一次。
        val visited = c.walkNext(6)
        assertEquals((0..5).toList(), visited.sorted(), "加曲后随机序应覆盖全部 6 首各一次")
    }

    /** 歌已在队列里（哪怕是 FM 队列靠后的位置）时，「下一首播放」应把它移上来。 */
    @Test
    fun `addNextToQueue moves an already queued track to next`() = runBlocking {
        val platform = FakePlatformPlayer()
        val c = controller(platform, FakeSource())
        c.playQueue(listOf(mid(1), mid(2), mid(3)), startIndex = 0)

        c.addNextToQueue(mid(3))

        assertEquals(
            listOf(mid(1), mid(3), mid(2)),
            c.state.value.queue.map { it.mediaId },
            "已在队列的歌应被移到当前曲之后，而不是重复添加",
        )
        platform.emitEnded()
        assertEquals(1, c.state.value.currentIndex)
        assertEquals(mid(3), c.state.value.currentTrack?.id)
    }

    /** 队列里没有这首时「下一首播放」不能把别的歌挤掉或弄出空槽。 */
    @Test
    fun `addNextToQueue with shuffle on keeps a clean permutation`() = runBlocking {
        val c = controller(FakePlatformPlayer(), FakeSource())
        c.playQueue(listOf(mid(1), mid(2), mid(3)), startIndex = 0)
        c.toggleShuffle()
        c.setRepeatMode(RepeatMode.ALL)

        c.addNextToQueue(mid(9))

        val visited = c.walkNext(4)
        assertEquals(listOf(0, 1, 2, 3), visited.sorted(), "插播后每首应各占一个槽位，实际=$visited")
    }
}

private fun mid(n: Int) = "netease://song/$n"

private fun summaryOf(id: String) = TrackSummary(
    id = id,
    name = "曲目 $id",
    artist = "测试歌手",
    album = "测试专辑",
    coverUrl = null,
    durationMs = 180_000L,
)
