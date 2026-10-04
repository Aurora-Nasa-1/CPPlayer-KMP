package cp.player.core.listentogether

import cp.player.core.BackendResult
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState
import cp.player.core.music.TrackSummary
import cp.player.core.playback.QueueItem
import cp.player.core.playback.RepeatMode
import cp.player.core.playback.SeekFailure
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 指令同步层的行为契约（P2）。
 *
 * 钉的都是**会导致实际事故**的行为：
 * - 回声不抑制 → 自己上报的指令绕回来把自己 seek / 切歌（持续打嗝）；
 * - serverSeq 不判新 → 同一条旧指令反复应用；
 * - 切歌加载中的 `isPlaying=false` 不挡 → 被误判成「用户暂停」广播出去，把对方暂停掉；
 * - 退房不清理 → 「退出了一起听，点下一首还在给别人发指令」（方案 §3.3(d)）。
 */
class ListenTogetherSyncTest {

    // ======================== 测试替身 ========================

    /** 可控假后端：快照内容可设，上报调用全量记录。 */
    private class FakeSyncBackend : ListenTogetherBackend {
        override val providerId: String = "test"
        override fun isSupported(): Boolean = true

        var membershipResult: BackendResult<ListenTogetherMembership> =
            BackendResult.Success(membership(inRoom = false))
        var snapshotResult: BackendResult<RoomSnapshot> =
            BackendResult.Success(RoomSnapshot(null, null))

        val reported = mutableListOf<Reported>()
        var snapshotCalls = 0

        data class Reported(
            val roomId: String,
            val commandType: String,
            val playStatus: String,
            val progressMs: Long,
            val formerSongId: String,
            val targetSongId: String,
            val clientSeq: Long,
        )

        override suspend fun membership() = membershipResult
        override suspend fun createRoom(): BackendResult<ListenTogetherRoom> =
            BackendResult.Success(room())

        override suspend fun accept(roomId: String, inviterId: Long) = BackendResult.Success(Unit)
        override suspend fun end(roomId: String) = BackendResult.Success(Unit)

        override suspend fun snapshot(roomId: String): BackendResult<RoomSnapshot> {
            snapshotCalls++
            return snapshotResult
        }

        override suspend fun heartbeat(
            roomId: String, songId: String, playStatus: String, progressMs: Long,
        ) = BackendResult.Success(Unit)

        override suspend fun reportPlayCommand(
            roomId: String, commandType: String, playStatus: String, progressMs: Long,
            formerSongId: String?, targetSongId: String?, clientSeq: Long,
        ): BackendResult<Unit> {
            reported += Reported(
                roomId, commandType, playStatus, progressMs,
                formerSongId.orEmpty(), targetSongId.orEmpty(), clientSeq,
            )
            return BackendResult.Success(Unit)
        }

        override suspend fun reportPlaylist(
            roomId: String, userId: Long, version: Long, songIds: List<String>,
        ) = BackendResult.Success(Unit)
    }

    /** 可控假播放器：状态可手改，控制调用全量记录。 */
    private class FakePlayback : PlaybackController {
        val stateFlow = MutableStateFlow(PlaybackUiState())
        override val state: StateFlow<PlaybackUiState> get() = stateFlow.asStateFlow()

        override val seekFailures = MutableSharedFlow<SeekFailure>()
        override val likedIds: StateFlow<Set<String>> = MutableStateFlow(emptySet())

        val calls = mutableListOf<String>()

        override suspend fun playQueue(
            mediaIds: List<String>, startIndex: Int, sourceId: String?,
        ) {
            calls += "playQueue:${mediaIds.joinToString(",")}@$startIndex"
            stateFlow.value = stateFlow.value.copy(
                currentTrack = track(bareIdOf(mediaIds[startIndex])),
                queue = mediaIds.map { QueueItem(it, "", "", null, null, 0L) },
                currentIndex = startIndex,
            )
        }

        override suspend fun playAt(index: Int) {
            calls += "playAt:$index"
            stateFlow.value = stateFlow.value.copy(
                currentTrack = stateFlow.value.queue.getOrNull(index)
                    ?.let { track(bareIdOf(it.mediaId)) },
                currentIndex = index,
            )
        }

        override fun pause() {
            calls += "pause"
            stateFlow.value = stateFlow.value.copy(isPlaying = false)
        }

        override fun resume() {
            calls += "resume"
            stateFlow.value = stateFlow.value.copy(isPlaying = true)
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek:$positionMs"
            stateFlow.value = stateFlow.value.copy(positionMs = positionMs)
        }

        override suspend fun setQueue(
            mediaIds: List<String>, startIndex: Int, sourceId: String?,
        ) {
            calls += "setQueue:${mediaIds.joinToString(",")}@$startIndex"
            // 模拟真实语义：替换队列并定位（不播放；当前歌若仍是同一首则保持 currentTrack）
            val cur = stateFlow.value.currentTrack
            val newId = mediaIds.getOrNull(startIndex)
            val sameSong = cur != null && newId != null && bareIdOf(newId) == bareIdOf(cur.id)
            stateFlow.value = stateFlow.value.copy(
                queue = mediaIds.map { QueueItem(it, "", "", null, null, 0L) },
                currentIndex = startIndex,
                currentTrack = if (sameSong) cur else null,
            )
        }

        override suspend fun addToQueue(mediaId: String) {
            calls += "addToQueue"
        }

        override suspend fun addNextToQueue(mediaId: String) {
            calls += "addNextToQueue"
        }

        override suspend fun removeQueueItem(index: Int) {
            calls += "removeQueueItem"
        }

        override suspend fun moveQueueItem(from: Int, to: Int) {
            calls += "moveQueueItem"
        }

        override fun clearQueue() {
            calls += "clearQueue"
        }

        override fun togglePlayPause() {
            calls += "togglePlayPause"
        }

        override fun skipNext() {
            calls += "skipNext"
        }

        override fun skipPrevious() {
            calls += "skipPrevious"
        }

        override fun setRepeatMode(mode: RepeatMode) {
            calls += "setRepeatMode"
        }

        override fun toggleShuffle() {
            calls += "toggleShuffle"
        }

        override suspend fun toggleFavorite() {
            calls += "toggleFavorite"
        }

        override suspend fun toggleFavoriteFor(mediaId: String) {
            calls += "toggleFavoriteFor"
        }

        override suspend fun refreshFavorites() {
            calls += "refreshFavorites"
        }

        override fun setQuality(level: String) {
            calls += "setQuality"
        }

        override fun setSleepTimer(minutes: Int) {
            calls += "setSleepTimer"
        }

        override fun cancelSleepTimer() {
            calls += "cancelSleepTimer"
        }

        override suspend fun refreshLyrics() {
            calls += "refreshLyrics"
        }

        override fun setVolume(volume: Float) {
            calls += "setVolume"
        }

        override fun release() {
            calls += "release"
        }

        private fun bareIdOf(mediaId: String): String =
            mediaId.substringAfterLast('/')
    }

    private fun engine(
        backend: FakeSyncBackend,
        playback: FakePlayback,
    ) = ListenTogetherEngine(
        backend = backend,
        // 用例直接调 suspend/internal 方法，不跑 start() 的轮询循环。
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        myUserId = { 42L },
        playback = playback,
        bareSongIdOf = { mediaId ->
            // 与生产接线同构：providerId 对不上返回 null（不同步）
            if (mediaId.startsWith("p://song/")) mediaId.substringAfterLast('/') else null
        },
        mediaIdForSong = { songId -> "p://song/$songId" },
    )

    /** 把引擎置成「在房」：让 membership 返回在房状态后走真实 refresh 路径。 */
    private suspend fun forceInRoom(e: ListenTogetherEngine, backend: FakeSyncBackend) {
        backend.membershipResult = BackendResult.Success(membership(inRoom = true))
        e.refresh()
    }

    private fun command(
        target: String? = "111",
        type: LtCommandType = LtCommandType.GOTO,
        playStatus: LtCommandType = LtCommandType.PLAY,
        progress: Long = 0L,
        serverSeq: Long = 1_000L,
        clientSeq: Long = 0L,
        userId: Long = 99L,
    ) = RoomPlaybackCommand(
        commandType = type,
        playStatus = playStatus,
        progressMs = progress,
        targetSongId = target,
        formerSongId = null,
        userId = userId,
        clientSeq = clientSeq,
        serverSeq = serverSeq,
    )

    // ======================== 应用侧：远端 → 本机 ========================

    @Test
    fun `远端切歌指令让本机跟着切`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        val e = engine(backend, pb)
        forceInRoom(e, backend)

        e.applySnapshot(RoomSnapshot(command(target = "222"), null))

        assertTrue(pb.calls.any { it.startsWith("playQueue:p://song/222") }, "实际调用：${pb.calls}")
    }

    @Test
    fun `远端切歌且歌在本地队列里时用 playAt 保留队列上下文`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(
            queue = listOf(
                QueueItem("p://song/111", "", "", null, null, 0L),
                QueueItem("p://song/222", "", "", null, null, 0L),
            ),
        )
        val e = engine(backend, pb)
        forceInRoom(e, backend)

        e.applySnapshot(RoomSnapshot(command(target = "222"), null))

        assertTrue("playAt:1" in pb.calls, "实际调用：${pb.calls}")
    }

    @Test
    fun `远端暂停指令让本机暂停`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(currentTrack = track("111"), isPlaying = true)
        val e = engine(backend, pb)
        forceInRoom(e, backend)

        e.applySnapshot(
            RoomSnapshot(
                command(
                    target = "111", type = LtCommandType.PAUSE,
                    playStatus = LtCommandType.PAUSE, progress = 45_000L,
                ),
                null,
            ),
        )

        assertTrue("pause" in pb.calls, "实际调用：${pb.calls}")
    }

    @Test
    fun `自己的回声指令不应用`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(currentTrack = track("111"), isPlaying = true)
        val e = engine(backend, pb)
        forceInRoom(e, backend)

        // 本机暂停 → 上报（clientSeq 记为已发）
        pb.pause()
        e.onPlaybackObserved(pb.stateFlow.value)
        val seq = e.lastReportEnqueued?.clientSeq
        assertTrue(seq != null && seq > 0L, "前置：应已上报过一条指令")

        // 同一条指令被服务端存下又读回来（serverSeq 更新、userId=自己、clientSeq=已发）
        e.applySnapshot(
            RoomSnapshot(
                command(
                    target = "111", type = LtCommandType.PAUSE,
                    playStatus = LtCommandType.PAUSE, progress = 45_000L,
                    clientSeq = seq, userId = 42L, serverSeq = 2_000L,
                ),
                null,
            ),
        )

        assertTrue(pb.calls.none { it.startsWith("seek") }, "回声不应产生反向 seek：${pb.calls}")
        assertTrue("resume" !in pb.calls, "回声不应反向切回播放：${pb.calls}")
    }

    @Test
    fun `同账号另一台设备的指令要应用而不能被当成回声`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        val e = engine(backend, pb)
        forceInRoom(e, backend)

        e.applySnapshot(
            RoomSnapshot(
                // userId=42（同账号）但 clientSeq=0 不在本机已发区间（起点随机 > 0 且未上报过）
                command(target = "555", clientSeq = 0L, userId = 42L, serverSeq = 3_000L),
                null,
            ),
        )

        assertTrue(
            pb.calls.any { it.startsWith("playQueue:p://song/555") },
            "另一台设备的切歌必须被应用：${pb.calls}",
        )
    }

    @Test
    fun `旧 serverSeq 的指令不重复应用`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        val e = engine(backend, pb)
        forceInRoom(e, backend)

        e.applySnapshot(RoomSnapshot(command(target = "222", serverSeq = 1_000L), null))
        e.applySnapshot(RoomSnapshot(command(target = "222", serverSeq = 999L), null))

        assertEquals(
            1,
            pb.calls.count { it.startsWith("playQueue:p://song/222") },
            "同一条指令只该应用一次：${pb.calls}",
        )
    }

    @Test
    fun `上游新出现的指令类型保守跳过`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(currentTrack = track("111"), isPlaying = true)
        val e = engine(backend, pb)
        forceInRoom(e, backend)

        e.applySnapshot(RoomSnapshot(command(target = "222", type = LtCommandType.UNKNOWN), null))

        assertTrue(pb.calls.isEmpty(), "语义不明的指令不该动本机：${pb.calls}")
    }

    // ======================== 上报侧：本机 → 远端 ========================

    @Test
    fun `建房者首次观察把本机状态广播为房间初始状态`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(currentTrack = track("111"), isPlaying = true, positionMs = 12_000L)
        val e = engine(backend, pb)
        forceInRoom(e, backend)

        e.onPlaybackObserved(pb.stateFlow.value)

        val r = e.lastReportEnqueued ?: error("首次观察应广播初始状态")
        assertEquals(ListenTogetherEngine.CMD_PLAY, r.commandType)
        assertEquals("111", r.targetSongId)
        assertEquals(12_000L, r.progressMs)
    }

    @Test
    fun `本机切歌上报 GOTO`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(currentTrack = track("111"), isPlaying = true)
        val e = engine(backend, pb)
        forceInRoom(e, backend)
        e.onPlaybackObserved(pb.stateFlow.value) // 初始化房间状态 = 111

        pb.playQueue(listOf("p://song/333"))
        e.onPlaybackObserved(pb.stateFlow.value)

        val r = e.lastReportEnqueued ?: error("切歌应上报 GOTO")
        assertEquals(ListenTogetherEngine.CMD_GOTO, r.commandType)
        assertEquals("333", r.targetSongId)
        assertEquals("111", r.formerSongId)
    }

    @Test
    fun `本机暂停上报 PAUSE 而不是被当成加载抖动`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(currentTrack = track("111"), isPlaying = true, positionMs = 10_000L)
        val e = engine(backend, pb)
        forceInRoom(e, backend)
        e.onPlaybackObserved(pb.stateFlow.value)

        pb.pause()
        e.onPlaybackObserved(pb.stateFlow.value)

        val r = e.lastReportEnqueued ?: error("暂停应上报 PAUSE")
        assertEquals(ListenTogetherEngine.CMD_PAUSE, r.commandType)
        assertEquals("111", r.targetSongId)
    }

    @Test
    fun `本机 seek 上报 PROGRESS`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(currentTrack = track("111"), isPlaying = true, positionMs = 10_000L)
        val e = engine(backend, pb)
        forceInRoom(e, backend)
        e.onPlaybackObserved(pb.stateFlow.value)

        // 手动把进度挪 30 秒（模拟用户拖动），播放态不变
        pb.seekTo(40_000L)
        e.onPlaybackObserved(pb.stateFlow.value)

        val r = e.lastReportEnqueued ?: error("seek 应上报 PROGRESS")
        assertEquals(ListenTogetherEngine.CMD_PROGRESS, r.commandType)
        assertEquals(40_000L, r.progressMs)
    }

    @Test
    fun `音源不一致时本机状态不上报`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        // other://... 的曲目：bareSongIdOf 返回 null
        pb.stateFlow.value = PlaybackUiState(currentTrack = trackFromMediaId("other://song/111"), isPlaying = true)
        val e = engine(backend, pb)
        forceInRoom(e, backend)

        e.onPlaybackObserved(pb.stateFlow.value)

        assertEquals(null, e.lastReportEnqueued, "跨音源内容塞进房间会毒害对端")
    }

    // ======================== 队列同步（P3） ========================

    @Test
    fun `本机加歌触发队列REPLACE上报且版本自增`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(
            currentTrack = track("111"),
            isPlaying = true,
            queue = listOf(QueueItem("p://song/111", "", "", null, null, 0L)),
        )
        val e = engine(backend, pb)
        forceInRoom(e, backend)

        // 首次观察：初始化房间队列基准（REPLACE 一次）
        e.onPlaybackObserved(pb.stateFlow.value)
        val v0 = e.lastPlaylistEnqueued?.version
        assertTrue(v0 != null && v0 > 0L, "首次观察应广播初始队列")

        // 本机加一首 → 队列变化 → 再次 REPLACE，版本 +1
        pb.stateFlow.value = pb.stateFlow.value.copy(
            queue = pb.stateFlow.value.queue + QueueItem("p://song/222", "", "", null, null, 0L),
        )
        e.onPlaybackObserved(pb.stateFlow.value)

        val r = e.lastPlaylistEnqueued ?: error("加歌后应上报 REPLACE")
        assertEquals(listOf("111", "222"), r.songIds)
        assertEquals((v0 ?: 0L) + 1L, r.version, "版本必须自增")
    }

    @Test
    fun `队列没变时不重复上报`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(
            currentTrack = track("111"),
            isPlaying = true,
            queue = listOf(QueueItem("p://song/111", "", "", null, null, 0L)),
        )
        val e = engine(backend, pb)
        forceInRoom(e, backend)
        e.onPlaybackObserved(pb.stateFlow.value)
        val afterFirst = e.lastPlaylistEnqueued

        // 同一队列再观察 N 次（positionMs 在变）→ 不再上报
        e.onPlaybackObserved(pb.stateFlow.value.copy(positionMs = 5_000L))
        e.onPlaybackObserved(pb.stateFlow.value.copy(positionMs = 6_000L))

        assertEquals(afterFirst, e.lastPlaylistEnqueued, "队列无变化不应重复上报")
    }

    @Test
    fun `远端REPLACE且当前歌在队列里时setQueue定位到当前歌`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(currentTrack = track("111"), isPlaying = true)
        val e = engine(backend, pb)
        forceInRoom(e, backend)
        e.onPlaybackObserved(pb.stateFlow.value) // 建立房间基准（=[111]）

        // 对方把队列改成 [111, 222, 333]（版本表变化）
        e.applySnapshot(
            RoomSnapshot(
                null,
                RoomPlaylist(
                    songIds = listOf("111", "222", "333"),
                    replace = true,
                    versions = mapOf(99L to 1L),
                ),
            ),
        )

        assertTrue(
            pb.calls.any { it.startsWith("setQueue:p://song/111,p://song/222,p://song/333@0") },
            "应整表应用且定位到当前歌：${pb.calls}",
        )
        assertEquals("setQueue:p://song/111,p://song/222,p://song/333@0", pb.calls.last())
    }

    @Test
    fun `远端REPLACE但当前歌不在队列时只更新认知不打断播放`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(currentTrack = track("999"), isPlaying = true)
        val e = engine(backend, pb)
        forceInRoom(e, backend)
        e.onPlaybackObserved(pb.stateFlow.value)

        e.applySnapshot(
            RoomSnapshot(
                null,
                RoomPlaylist(
                    songIds = listOf("111", "222"),
                    replace = true,
                    versions = mapOf(99L to 1L),
                ),
            ),
        )

        assertTrue(pb.calls.none { it.startsWith("setQueue") }, "不该打断正在听的歌：${pb.calls}")
    }

    @Test
    fun `自己上报的REPLACE被快照回读时不重复应用`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(
            currentTrack = track("111"),
            isPlaying = true,
            queue = listOf(QueueItem("p://song/111", "", "", null, null, 0L)),
        )
        val e = engine(backend, pb)
        forceInRoom(e, backend)
        e.onPlaybackObserved(pb.stateFlow.value)
        val sent = e.lastPlaylistEnqueued ?: error("前置：应已上报")

        // 快照回读自己发的列表（versions 含自己刚发的版本）→ 不应再 setQueue
        e.applySnapshot(
            RoomSnapshot(
                null,
                RoomPlaylist(
                    songIds = sent.songIds,
                    replace = true,
                    versions = mapOf(42L to sent.version),
                ),
            ),
        )

        assertTrue(pb.calls.none { it.startsWith("setQueue") }, "自己的回声不该驱动本机：${pb.calls}")
    }

    @Test
    fun `GOTO的歌在房间队列里时playQueue整条应用保住队列`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(currentTrack = track("111"), isPlaying = true)
        val e = engine(backend, pb)
        forceInRoom(e, backend)
        e.onPlaybackObserved(pb.stateFlow.value) // roomQueue = [111]

        // 先让房间队列变成 [111, 222]
        e.applySnapshot(
            RoomSnapshot(
                null,
                RoomPlaylist(
                    songIds = listOf("111", "222"),
                    replace = true,
                    versions = mapOf(99L to 1L),
                ),
            ),
        )
        // 对方切到 222 → 222 在房间队列里 → 整条 playQueue 定位
        e.applySnapshot(
            RoomSnapshot(command(target = "222", serverSeq = 9_000L), null),
        )

        assertTrue(
            pb.calls.any { it.startsWith("playQueue:p://song/111,p://song/222@1") },
            "应带完整房间队列切歌：${pb.calls}",
        )
    }

    @Test
    fun `退房后队列状态被清理`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(
            currentTrack = track("111"),
            isPlaying = true,
            queue = listOf(QueueItem("p://song/111", "", "", null, null, 0L)),
        )
        val e = engine(backend, pb)
        forceInRoom(e, backend)
        e.onPlaybackObserved(pb.stateFlow.value)
        assertTrue(e.lastPlaylistEnqueued != null, "前置：在房时应有过队列上报")

        e.endRoom()
        pb.stateFlow.value = pb.stateFlow.value.copy(
            queue = pb.stateFlow.value.queue + QueueItem("p://song/222", "", "", null, null, 0L),
        )
        e.onPlaybackObserved(pb.stateFlow.value)

        assertEquals(null, e.lastPlaylistEnqueued, "退房后队列变化不得再上报")
    }

    // ======================== 清理 ========================

    @Test
    fun `退房后本机操作不再上报`() = runBlocking {
        val backend = FakeSyncBackend()
        backend.membershipResult = BackendResult.Success(membership(inRoom = true))
        val pb = FakePlayback()
        pb.stateFlow.value = PlaybackUiState(currentTrack = track("111"), isPlaying = true)
        val e = engine(backend, pb)
        forceInRoom(e, backend)
        e.onPlaybackObserved(pb.stateFlow.value)
        assertTrue(e.lastReportEnqueued != null, "前置：在房时应有上报")

        e.endRoom()
        pb.resume()
        e.onPlaybackObserved(pb.stateFlow.value)

        // teardown 把在途上报作废；退房后的观察不得再入队新指令
        assertEquals(null, e.lastReportEnqueued, "退房后的操作必须留在本机")
    }

    @Test
    fun `被踢出房间后远端快照不再应用`() = runBlocking {
        val backend = FakeSyncBackend()
        val pb = FakePlayback()
        val e = engine(backend, pb)
        forceInRoom(e, backend)

        // 房间已失效：membership 回答不在房
        backend.membershipResult = BackendResult.Success(membership(inRoom = false))
        e.refresh()
        backend.snapshotResult = BackendResult.Success(RoomSnapshot(command(target = "222"), null))
        e.applySnapshot(backend.snapshotResult.getOrNull()!!)

        assertTrue(pb.calls.isEmpty(), "退房后的快照不该驱动本机：${pb.calls}")
    }

    // ======================== 夹具 ========================

    private companion object {
        fun track(resourceId: String): TrackSummary {
            // mediaId 形如 p://song/<resourceId>，与引擎的 bareSongIdOf 对齐
            return trackFromMediaId("p://song/$resourceId")
        }

        fun trackFromMediaId(mediaId: String): TrackSummary = TrackSummary(
            id = mediaId,
            name = "t",
            artist = "",
            album = null,
            coverUrl = null,
            durationMs = 0L,
        )

        fun membership(inRoom: Boolean) = ListenTogetherMembership(
            inRoom = inRoom,
            room = if (inRoom) room() else null,
            connectionStatus = if (inRoom) "NOT_CONNECTED" else null,
        )

        fun room() = ListenTogetherRoom(
            roomId = "room_1",
            creatorId = 42L,
            roomType = "FRIEND",
            createdAtMs = 0L,
            effectiveDurationMs = 1_800_000L,
            waitMs = 120_000L,
            members = emptyList(),
        )
    }
}
