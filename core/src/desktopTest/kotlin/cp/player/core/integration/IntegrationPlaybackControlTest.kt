package cp.player.core.integration

import cp.player.core.BackendResult
import cp.player.core.control.LocalServerConfig
import cp.player.core.music.MusicResult
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.SearchResult
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.playback.PlaybackUiState
import cp.player.core.testing.ManualDispatcher
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 播控**写**路径的测试 —— 这是整个数据面风险最高的部分。
 *
 * ### 为什么写路径比读路径危险
 * 读路径最多是把数据读错；写路径会**改变用户机器上的播放状态**，而且出错方式极其难查：
 * `MusicBackend.backendScope` 是 `Dispatchers.Main`，Ktor 请求跑在 CIO 线程上。
 * 在 CIO 线程直接调 `playbackController.pause()` 会与 UI 抢状态，
 * 症状是**偶发跳歌 / 队列错乱**，且几乎不可能复现。
 *
 * 所以这里钉两件事，都是「不测就一定会回归」的：
 * 1. **动作必须派发到控制线程**（而不是在调用方线程上同步执行）；
 * 2. **响应必须等动作完成**（而不是 `launch` 出去即发即忘，那样集成方拿到的状态是旧的）。
 *
 * ### 为什么不用 `Dispatchers.Main`
 * `core` 的 desktopTest 类路径上没有 `kotlinx-coroutines-swing`，
 * `Dispatchers.Main` 在测试里根本不可用。替代品是真实线程 + [ManualDispatcher]，
 * 两者分别覆盖上面两条性质。
 */
class IntegrationPlaybackControlTest {

    // ============ 开关 ============

    @Test
    fun `未开放远程播控时返回 403 且完全不触碰控制器`() = runBlocking {
        val state = MutableStateFlow(PlaybackUiState())
        val control = RecordingControl(state)
        val service = service(control = control, allowRemoteControl = false, state = state)

        val result = service.playbackAction(PlaybackAction.PAUSE)

        val failure = result as IntegrationResult.Failure
        assertEquals(FailureKind.FACE_DISABLED, failure.kind, "远程播控关着必须是 403，不能是 400/401")
        assertEquals(403, failure.kind.httpStatus)

        // 关键：不是「拒绝了但动作已经发生」。开关关闭时控制器必须**一次都没被调用**。
        assertEquals(emptyList(), control.calls, "被拒绝的请求绝不能已经改了播放状态")
    }

    @Test
    fun `开关打开后可以播控`() = runBlocking {
        val state = MutableStateFlow(PlaybackUiState(isPlaying = true))
        val control = RecordingControl(state)
        val service = service(control = control, allowRemoteControl = true, state = state)

        val result = service.playbackAction(PlaybackAction.PAUSE)

        assertTrue(result is IntegrationResult.Ok, "开关打开时应成功：$result")
        assertEquals(listOf(PlaybackAction.PAUSE), control.calls)
    }

    // ============ 动作映射 ============

    @Test
    fun `每个受支持的动作都恰好映射到一个控制器调用`() = runBlocking {
        // 遍历 SUPPORTED 而不是手写四遍：这样「往 SUPPORTED 里加了动作却忘了加 when 分支」
        // 会立刻在这里失败，而不是等到线上返回 400 才发现。
        for (action in PlaybackAction.SUPPORTED) {
            val state = MutableStateFlow(PlaybackUiState())
            val control = RecordingControl(state)
            val service = service(control = control, state = state)

            val result = service.playbackAction(action)

            assertTrue(result is IntegrationResult.Ok, "动作 '$action' 应当被接受，实际：$result")
            assertEquals(
                listOf(action),
                control.calls,
                "动作 '$action' 应当只触发一次对应的控制器调用",
            )
        }
    }

    @Test
    fun `未知动作返回 400 并列出支持的动作`() = runBlocking {
        val state = MutableStateFlow(PlaybackUiState())
        val control = RecordingControl(state)
        val service = service(control = control, state = state)

        val failure = service.playbackAction("seek") as IntegrationResult.Failure

        assertEquals(FailureKind.BAD_REQUEST, failure.kind)
        // 报错必须能指导行动：只说「未知动作」的话，集成方还得去翻文档才知道能用什么
        for (action in PlaybackAction.SUPPORTED) {
            assertTrue(failure.message.contains(action), "报错应列出支持的动作 $action：${failure.message}")
        }
        assertEquals(emptyList(), control.calls)
    }

    @Test
    fun `stop 被刻意排除且报错说明了原因`() = runBlocking {
        val state = MutableStateFlow(PlaybackUiState())
        val control = RecordingControl(state)
        val service = service(control = control, state = state)

        val failure = service.playbackAction("stop") as IntegrationResult.Failure

        assertEquals(FailureKind.BAD_REQUEST, failure.kind)
        // 计划文档里写过 stop。照着旧文档写的集成方不该只得到「未知动作」这种无法行动的消息，
        // 而要被告知「没有非破坏性的停止」以及该改用 pause。
        assertTrue(
            failure.message.contains(PlaybackAction.PAUSE),
            "stop 的报错应指出该改用 pause：${failure.message}",
        )
        assertEquals(emptyList(), control.calls, "stop 绝不能落到 clearQueue（会销毁用户队列）")
    }

    @Test
    fun `动作名大小写敏感`() = runBlocking {
        val state = MutableStateFlow(PlaybackUiState())
        val control = RecordingControl(state)
        val service = service(control = control, state = state)

        // 路径参数是线上契约，宽松匹配会让「契约里写的是什么」变得含糊
        val failure = service.playbackAction("PAUSE") as IntegrationResult.Failure

        assertEquals(FailureKind.BAD_REQUEST, failure.kind)
        assertEquals(emptyList(), control.calls)
    }

    // ============ 线程规则（本文件存在的理由） ============

    @Test
    fun `写操作在控制线程上执行而不是调用方线程`() {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, CONTROL_THREAD) }
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            val state = MutableStateFlow(PlaybackUiState())
            val control = RecordingControl(state)
            val service = service(control = control, dispatcher = dispatcher, state = state)

            val callerThread = Thread.currentThread().name
            runBlocking { service.playbackAction(PlaybackAction.NEXT) }

            assertEquals(listOf(PlaybackAction.NEXT), control.calls)
            // 用 startsWith 而不是相等：协程运行时会把线程改名成
            // `<原名> @coroutine#N`（协程调试支持），相等比较会在升级协程库时莫名其妙地碎。
            // 断言仍然有效 —— 它排除的正是「跑在调用方线程上」。
            assertTrue(
                control.threads.single().startsWith(CONTROL_THREAD),
                "动作必须跑在控制线程上（实际 ${control.threads.single()}）。" +
                    "若这里失败，说明 withContext(controlDispatcher) 被去掉了 —— " +
                    "在生产里就是「Ktor 线程直接改播放状态」，症状是偶发跳歌且难以复现。",
            )
            assertNotEquals(callerThread, control.threads.single(), "控制线程不能就是调用方线程，否则这条断言没有意义")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `响应要等动作完成而不是即发即忘`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val control = object : IntegrationPlaybackControl {
            override suspend fun play() = Unit
            override suspend fun pause() {
                entered.complete(Unit)
                gate.await()
            }

            override suspend fun next() = Unit
            override suspend fun previous() = Unit
        }
        val service = service(control = control)

        var returned = false
        val request = launch {
            service.playbackAction(PlaybackAction.PAUSE)
            returned = true
        }

        // 确定性等待「动作已开始」，不用 yield() 猜调度顺序
        entered.await()

        // 动作还卡在 gate 上，所以调用方**必须**还没返回。
        // 若实现改成 `launch { … }`（即发即忘），这里早就返回了，
        // 而集成方拿到的状态快照会是动作之前的 —— 那正是要防的 bug。
        assertFalse(returned, "HTTP 响应不能先于动作完成；否则返回的状态快照是动作之前的")

        gate.complete(Unit)
        request.join()
        assertTrue(returned, "动作完成后调用方应当返回")
    }

    // ============ 响应内容 ============

    @Test
    fun `返回的是动作之后的状态快照`() = runBlocking {
        val state = MutableStateFlow(PlaybackUiState(isPlaying = true))
        val control = RecordingControl(state)
        val service = service(control = control, state = state)

        val ok = service.playbackAction(PlaybackAction.PAUSE) as IntegrationResult.Ok
        assertFalse(ok.value.isPlaying, "pause 之后返回的快照应当是暂停态")

        val resumed = service.playbackAction(PlaybackAction.PLAY) as IntegrationResult.Ok
        assertTrue(resumed.value.isPlaying, "play 之后返回的快照应当是播放态")
    }

    @Test
    fun `手动调度器下动作确实进了控制线程队列`() = runBlocking {
        // 与「真实线程」那条互补：那条证明跑在别的线程上，这条证明**不是在调用方同步跑掉的**。
        val dispatcher = ManualDispatcher()
        val state = MutableStateFlow(PlaybackUiState())
        val control = RecordingControl(state)
        val service = service(control = control, dispatcher = dispatcher, state = state)

        val request = launch { service.playbackAction(PlaybackAction.PAUSE) }
        // 把请求协程推进到 withContext 处（此时它已把任务入队到 ManualDispatcher）。
        // **有界**自旋：无界的话一旦实现变了，测试会挂住而不是失败 —— 挂住比失败更难诊断。
        var spins = 0
        while (dispatcher.pending == 0 && spins++ < 1_000) kotlinx.coroutines.yield()

        assertEquals(emptyList(), control.calls, "任务应还在控制线程队列里，不该已经执行")
        assertTrue(dispatcher.pending > 0, "动作应当被派发到控制线程的队列")

        dispatcher.drain()
        request.join()
        assertEquals(listOf(PlaybackAction.PAUSE), control.calls)
    }

    // ============ 脚手架 ============

    private fun service(
        control: IntegrationPlaybackControl,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        allowRemoteControl: Boolean = true,
        state: MutableStateFlow<PlaybackUiState> = MutableStateFlow(PlaybackUiState()),
    ) = IntegrationService(
        source = { NoopSource },
        playbackState = { state },
        playbackControl = { control },
        controlDispatcher = dispatcher,
        availableProviders = { emptyList() },
        activeProviderId = { null },
        loggedIn = { false },
        config = {
            LocalServerConfig(
                streamPort = 8080,
                accessToken = "tok",
                allowRemoteControl = allowRemoteControl,
            )
        },
    )

    /**
     * 记录调用与执行线程的播控替身。
     *
     * 它同时改 [state]，因为生产里的 `PlaybackController` 就是这么做的 ——
     * 「动作之后返回的状态快照」这条断言才有意义。一个不改状态的替身会让那条测试
     * 变成空转（永远断言成功）。
     */
    private class RecordingControl(private val state: MutableStateFlow<PlaybackUiState>) :
        IntegrationPlaybackControl {

        val calls = mutableListOf<String>()
        val threads = mutableListOf<String>()

        override suspend fun play() = record(PlaybackAction.PLAY) {
            state.value = state.value.copy(isPlaying = true)
        }

        override suspend fun pause() = record(PlaybackAction.PAUSE) {
            state.value = state.value.copy(isPlaying = false)
        }

        override suspend fun next() = record(PlaybackAction.NEXT) { }

        override suspend fun previous() = record(PlaybackAction.PREVIOUS) { }

        private fun record(action: String, apply: () -> Unit) {
            calls += action
            threads += Thread.currentThread().name
            apply()
        }
    }

    /** 播控测试不需要上游；真被调到说明测试写错了。 */
    private object NoopSource : UnifiedMusicSource {
        override suspend fun getTrackDetail(mediaId: String): MusicResult<TrackSummary> =
            BackendResult.Unsupported("noop")

        override suspend fun getTrackDetails(mediaIds: List<String>): MusicResult<List<TrackSummary>> =
            BackendResult.Unsupported("noop")

        override suspend fun getSongUrl(mediaId: String, level: String): MusicResult<SongUrl> =
            BackendResult.Unsupported("noop")

        override suspend fun search(
            keywords: String,
            type: Int,
            providers: List<String>?,
        ): MusicResult<SearchResult> = BackendResult.Unsupported("noop")

        override suspend fun getUserPlaylists(providerId: String, uid: Long): MusicResult<List<PlaylistSummary>> =
            BackendResult.Unsupported("noop")
    }

    private companion object {
        const val CONTROL_THREAD = "cp-control-thread"
    }
}
