package cp.player.core.listentogether

import cp.player.core.BackendResult
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.abs

/** 心跳要上报的播放状态。 */
data class HeartbeatInfo(
    val songId: String,
    val isPlaying: Boolean,
    val progressMs: Long,
)

/**
 * UI 渲染状态（不可变）。
 *
 * @property connectionStatus 实测恒为 `NOT_CONNECTED`（IM 长连接态）。**不是错误**，
 *   见 [ListenTogetherMembership] 的说明 —— UI 不该把它渲染成红色。
 * @property notice 一次性提示（成功类）。消费后由 [ListenTogetherEngine.consumeNotice] 清掉。
 * @property error 一次性错误。同上。
 */
data class ListenTogetherState(
    val supported: Boolean = true,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val inRoom: Boolean = false,
    val room: ListenTogetherRoom? = null,
    val connectionStatus: String? = null,
    val myUserId: Long = 0L,
    val notice: String? = null,
    val error: String? = null,
) {
    /** 邀请链接；不在房间时为 null。 */
    fun shareUrl(): String? {
        val r = room ?: return null
        if (!inRoom || myUserId == 0L) return null
        return ListenTogetherInvite.buildShareUrl(r.roomId, myUserId)
    }

    fun isOwner(): Boolean = room?.isOwner(myUserId) == true
}

/**
 * 「一起听」引擎 —— 房间生命周期、邀请闭环、以及**指令同步**（P2）。
 *
 * ### 为什么把轮询放在引擎里而不是 UI 的 ScreenModel
 * 「在房」是**跨页面**的状态：用户进了房间之后会去听歌、翻歌单，房间页可能早就出栈了，
 * 但心跳与指令轮询必须继续（否则会被判定离开、也收不到对方的操作）。
 * 放在 ScreenModel 里会随页面销毁而停掉 —— 表现为「一离开房间页就掉线」。
 * 引擎挂在应用级 scope 上。
 *
 * ### 两条实测约束（直接决定了本类的写法）
 * 1. **`accept` 在已进房时无条件返回成功**（不校验 roomId/inviterId）。所以 [join]
 *    **必须先查 [refresh]** 确认不在房间，否则会把「其实没加入」当成加入成功。
 * 2. **`accept` 失败只有笼统的 `code=488`**，无法区分「房间不存在」与「邀请不是给你的」。
 *    所以错误文案只能是中性的 [INVALID_INVITE]，**不许编造更具体的原因**。
 *
 * ### 节奏来自服务端，不是猜的
 * 心跳回包带 `timeSpan`（实测 30 秒），轮询房间状态用更短的固定间隔即可。
 *
 * ### 指令同步（本类最重要的职责）
 * 上游**不推送**（实时通道是云信 IM，本实现不接），但会把指令**持久化**并在
 * `sync/playlist/get` 里原样回吐（方案 §9.3 实测）——所以同步 = 双向轮询：
 *
 * - **读侧**：轮询 [ListenTogetherBackend.snapshot] → `serverSeq` 判新 + `clientSeq`
 *   回声抑制 → [applyCommand] 应用到本机播放（切歌 / 播放态 / 进度对齐）。
 * - **写侧**：观察 [PlaybackController.state]，与「房间应处状态」[roomSync] 对比，
 *   差异即本机操作 → 上报 [ListenTogetherBackend.reportPlayCommand]。
 *
 * 「房间应处状态」[roomSync] 是整个状态机的枢纽：远端指令应用时、本机指令上报时
 * 都会把它重置为新的目标 —— 两边的落地（本机播放器到达新状态、快照回读自己的指令）
 * 与它一致时就什么也不做。这同时解决了**回声抑制**与**加载中误判**（切歌加载时
 * `isPlaying=false` 不是「用户暂停」，见 [applyGuardUntilMs]）。
 */
class ListenTogetherEngine(
    private val backend: ListenTogetherBackend,
    private val scope: CoroutineScope,
    private val myUserId: () -> Long,
    private val heartbeatInfo: () -> HeartbeatInfo? = { null },
    /**
     * 播放控制器。为 null 时引擎只跑房间生命周期 + 心跳（旧调用点 / 纯生命周期测试），
     * 指令同步整体禁用 —— 比塞一个假实现进来诚实。
     */
    private val playback: PlaybackController? = null,
    /**
     * mediaId → 房间协议的裸 songId。返回 null 表示「这首不归当前音源管」（跨音源
     * 的 id 塞进房间对端解析不了，宁可不同步）。同步三侧（心跳 / 上报 / 应用）统一走它。
     */
    private val bareSongIdOf: ((mediaId: String) -> String?)? = null,
    /** 裸 songId → mediaId（应用远端切歌时构造播放目标）。null 时远端切歌无法应用。 */
    private val mediaIdForSong: ((songId: String) -> String)? = null,
) {

    private val _state = MutableStateFlow(ListenTogetherState())
    val state: StateFlow<ListenTogetherState> = _state.asStateFlow()

    private var loop: Job? = null
    private var observerJob: Job? = null
    private var reportJob: Job? = null

    /**
     * 跟随模式。进房即开：本机操作广播给房间，远端指令驱动本机。
     * 关掉后只维持心跳与在房状态，播放完全回到本机自主（退房会复位为 true）。
     */
    @Volatile var followEnabled: Boolean = true

    // ==================== 同步层状态（全部随退房清理，见 [teardownSync]） ====================

    /** 服务端序号水位：小于等于它的指令都是旧的，不应用。 */
    @Volatile private var lastSeenServerSeq: Long = 0L

    /** 本机已发出的最大 clientSeq；回读指令 clientSeq 落在 (0, 它] 里就是自己的回声。 */
    @Volatile private var lastSentClientSeq: Long = 0L

    /**
     * 本机 clientSeq 计数器。起点随机 —— 同账号两台设备的序号会交错，固定起点必撞。
     * 只在观察协程（单协程）里递增，`@Volatile` 足够；teardown 并发复位的最坏后果
     * 只是序号回退一轮（抑制区间错一条，无实害）。
     */
    @Volatile private var clientSeq: Long = initialClientSeq()

    /**
     * 「房间此刻应处的状态」+ 进度外推锚点。null = 还没有任何已知状态
     * （进房后第一次快照 / 第一次本机观察会初始化它）。
     */
    @Volatile private var roomSync: RoomSync? = null

    /** 上一次本机播放观察值 —— seek 跳变检测的基准（每次观察都更新，与 roomSync 职责不同）。 */
    @Volatile private var lastObserved: Observed? = null

    /**
     * 远端切歌后的待办进度对齐：`playAt`/`play` 是异步加载，加载完成前 seek 会被覆盖，
     * 所以记下来等本机曲目到位后立刻补 seek（[onPlaybackObserved] 消费）。
     */
    @Volatile private var pendingSeek: PendingSeek? = null

    /**
     * 应用远端指令后的落地窗口（毫秒时刻）。窗口内本机 `isPlaying` 抖动是加载造成的，
     * **不是用户操作** —— 若不做这个窗口，B 端切歌加载中会被误判成暂停并广播出去，
     * 把 A 端正在播的歌暂停掉。
     */
    @Volatile private var applyGuardUntilMs: Long = 0L

    /** 上报队列：CONFLATED —— 快速连续操作只报最新一条，网络不排队。 */
    private val reportChannel = Channel<ReportCmd>(Channel.CONFLATED)

    private data class RoomSync(
        val songId: String,
        val isPlaying: Boolean,
        val progressMs: Long,
        val anchorWallMs: Long,
    ) {
        /** 按锚点外推「此刻进度」。暂停时进度冻结。 */
        fun expectedAt(nowMs: Long): Long =
            progressMs + if (isPlaying) (nowMs - anchorWallMs).coerceAtLeast(0L) else 0L
    }

    private data class Observed(
        val songId: String,
        val isPlaying: Boolean,
        val positionMs: Long,
        val wallMs: Long,
        val isBuffering: Boolean,
    )

    private data class PendingSeek(val songId: String, val progressMs: Long)

    internal data class ReportCmd(
        val commandType: String,
        val isPlaying: Boolean,
        val progressMs: Long,
        val formerSongId: String?,
        val targetSongId: String?,
        val clientSeq: Long,
    )

    /**
     * 最近一条进入发送队列的指令。真实发送是异步的（reportJob 消费 channel），
     * 这个字段提供**同步可见**的断言/诊断点 —— 退房时随在途上报一起作废。
     */
    @Volatile internal var lastReportEnqueued: ReportCmd? = null

    init {
        _state.value = _state.value.copy(supported = backend.isSupported())
    }

    /** 开始维持会话（登录后 / 应用启动时调用一次）。 */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            // 先把「在房」拉回来：应用重启后用户可能在房间里，但本地没有任何记录。
            refreshInternal()
            var tick = 0L
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                tick += POLL_INTERVAL_MS
                if (!_state.value.inRoom) {
                    // 不在房间时降频：只为发现「被邀请/被拉进房」，不值得每秒打接口。
                    if (tick % IDLE_POLL_MULTIPLIER == 0L) refreshInternal()
                    continue
                }
                refreshInternal()
                pollSnapshot()
                driftCorrect()
                if (tick % HEARTBEAT_INTERVAL_MS == 0L) heartbeat()
            }
        }
        val pb = playback ?: return
        if (observerJob?.isActive != true) {
            observerJob = scope.launch {
                pb.state.collect { onPlaybackObserved(it) }
            }
        }
        if (reportJob?.isActive != true) {
            reportJob = scope.launch {
                for (r in reportChannel) {
                    // 发送前再取一次房间号：排队期间可能已经退房
                    val roomId = _state.value.room?.roomId ?: continue
                    backend.reportPlayCommand(
                        roomId = roomId,
                        commandType = r.commandType,
                        playStatus = if (r.isPlaying) PLAY_STATUS_PLAY else PLAY_STATUS_PAUSE,
                        progressMs = r.progressMs,
                        formerSongId = r.formerSongId.orEmpty(),
                        targetSongId = r.targetSongId.orEmpty(),
                        clientSeq = r.clientSeq,
                    )
                    // 上报失败静默：下一次本机操作会带上最新状态重报，
                    // 这里弹错误只会把一次网络抖动放大成 UI 噪音。
                }
            }
        }
    }

    fun stop() {
        loop?.cancel(); loop = null
        observerJob?.cancel(); observerJob = null
        reportJob?.cancel(); reportJob = null
    }

    /** 手动刷新（进入房间页时调用，让页面立刻有数据而不是等一个轮询周期）。 */
    suspend fun refresh() = refreshInternal()

    /**
     * 建房。
     *
     * ⚠️ **已在房间时不建**：一个账号同时只能在一个房间里，建房会**顶掉**已有房间，
     * 而 `end` 只能关不能复活 —— 一次手滑可能清掉别人正在意的房间。
     * 这与实测的「建房会顶掉当前房间」一致，所以这里做了硬拦截而不是交给 UI 提示。
     */
    suspend fun createRoom() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, notice = null)

        if (_state.value.inRoom) {
            _state.value = _state.value.copy(busy = false, error = ALREADY_IN_ROOM)
            return
        }
        // 本地认为不在房间，但服务端可能不一致（例如另一台设备建的房）——再核一次。
        val fresh = backend.membership()
        if (fresh is BackendResult.Success && fresh.data.inRoom) {
            applyMembership(fresh.data)
            _state.value = _state.value.copy(busy = false, error = ALREADY_IN_ROOM)
            return
        }

        when (val r = backend.createRoom()) {
            is BackendResult.Success -> {
                // 用建房响应里的 roomInfo 直接落地，省一次往返。
                _state.value = _state.value.copy(
                    busy = false,
                    inRoom = true,
                    room = r.data,
                    myUserId = myUserId(),
                    notice = "房间已创建，把邀请发给对方吧",
                )
                // 同步状态必须从零开始：新房间的历史指令不属于这里。
                teardownSync()
            }

            is BackendResult.Error -> fail(r.message)
            is BackendResult.Unsupported -> unsupported()
        }
    }

    /** 结束房间。房主与成员都可调用（服务端会按权限处理）。 */
    suspend fun endRoom() {
        val roomId = _state.value.room?.roomId
        if (roomId.isNullOrEmpty()) {
            // 没有房间可关：直接把本地状态归零，避免页面卡在「正在退出」。
            _state.value = _state.value.copy(inRoom = false, room = null, connectionStatus = null)
            teardownSync()
            return
        }
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, notice = null)

        when (val r = backend.end(roomId)) {
            is BackendResult.Success -> {
                _state.value = _state.value.copy(
                    busy = false, inRoom = false, room = null, connectionStatus = null, notice = "已退出房间",
                )
                teardownSync()
            }

            is BackendResult.Error -> fail(r.message)
            is BackendResult.Unsupported -> unsupported()
        }
    }

    /**
     * 接受邀请。
     *
     * 走 [Joinability] 先在本地把能判死的判死（缺参数 / 音源不一致），
     * 而不是把一个注定失败的请求打出去再拿 `488` 猜原因。
     */
    suspend fun join(roomId: String, inviterId: Long) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, notice = null)

        when (val j = joinability(roomId, inviterId)) {
            is Joinability.Malformed -> {
                _state.value = _state.value.copy(busy = false, error = j.reason)
                return
            }

            is Joinability.ProviderMismatch -> {
                _state.value = _state.value.copy(busy = false, error = providerMismatchMessage(j))
                return
            }

            Joinability.Joinable -> Unit
        }

        // ⚠️ 顺序关键：accept 在已进房时**无条件成功**，所以必须先确认不在房间，
        // 否则这里会把「其实没加入」当成加入成功（见类 KDoc 约束 1）。
        val fresh = backend.membership()
        if (fresh is BackendResult.Success && fresh.data.inRoom) {
            applyMembership(fresh.data)
            _state.value = _state.value.copy(busy = false, error = ALREADY_IN_ROOM)
            return
        }

        when (val r = backend.accept(roomId, inviterId)) {
            is BackendResult.Success -> {
                // accept 成功只代表服务端接受了请求；房间详情仍要重新拉一次才算数。
                val after = backend.membership()
                if (after is BackendResult.Success && after.data.inRoom) {
                    applyMembership(after.data)
                    // 进房立刻拉一次快照：不等下一个轮询周期就对齐房间的播放状态
                    //（房主正在播的歌、进度、播放态一次到位）。
                    after.data.room?.roomId?.let { rid ->
                        val snap = backend.snapshot(rid)
                        if (snap is BackendResult.Success) applySnapshot(snap.data)
                    }
                    _state.value = _state.value.copy(busy = false, notice = "已加入房间")
                } else {
                    _state.value = _state.value.copy(busy = false, error = INVALID_INVITE)
                }
            }

            is BackendResult.Error -> _state.value = _state.value.copy(
                busy = false,
                // 实测 488 无法归因（§9.5），统一给中性文案，不要编造具体原因。
                error = if (r.code == INVITE_REJECTED_CODE) INVALID_INVITE else r.message,
            )

            is BackendResult.Unsupported -> unsupported()
        }
    }

    /**
     * 从一段文本里解析邀请并加入。
     *
     * @return 解析失败（文本里没有成对的 roomId + inviterId）时返回 false。
     */
    suspend fun joinFromText(text: String): Boolean {
        val params = ListenTogetherInvite.parse(text)
        if (params == null) {
            _state.value = _state.value.copy(error = "没找到有效的邀请信息")
            return false
        }
        join(params.roomId, params.inviterId)
        return true
    }

    /** 房间属于哪个音源。实测协议里没有这个字段，只有拿到 roomId 之后由本机推断。 */
    fun joinability(roomId: String, inviterId: Long): Joinability = when {
        roomId.isBlank() -> Joinability.Malformed("邀请里缺少房间号")
        inviterId <= 0L -> Joinability.Malformed("邀请里缺少邀请人")
        else -> Joinability.Joinable
    }

    fun consumeNotice() {
        if (_state.value.notice != null) _state.value = _state.value.copy(notice = null)
    }

    fun consumeError() {
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
    }

    // ======================== 内部：房间状态 ========================

    private suspend fun refreshInternal() {
        when (val r = backend.membership()) {
            is BackendResult.Success -> applyMembership(r.data)
            is BackendResult.Unsupported -> unsupported()
            // 轮询失败**不清空房间状态**：网络抖一下就把用户「踢出房间」是不可接受的。
            // 只在从未拿到过房间时记一次错误。
            is BackendResult.Error -> {
                if (_state.value.room == null) {
                    _state.value = _state.value.copy(loading = false, error = r.message)
                }
            }
        }
    }

    private suspend fun heartbeat() {
        val info = heartbeatInfo() ?: return
        val roomId = _state.value.room?.roomId ?: return
        backend.heartbeat(
            roomId = roomId,
            songId = info.songId,
            playStatus = if (info.isPlaying) PLAY_STATUS_PLAY else PLAY_STATUS_PAUSE,
            progressMs = info.progressMs,
        )
    }

    private fun applyMembership(m: ListenTogetherMembership) {
        val wasInRoom = _state.value.inRoom
        _state.value = _state.value.copy(
            loading = false,
            supported = backend.isSupported(),
            inRoom = m.inRoom,
            room = m.room,
            connectionStatus = m.connectionStatus,
            myUserId = myUserId(),
        )
        // 被踢 / 房主关房 / 到期：同步状态必须整体复位（方案 §3.3(d)：
        // 「退出了一起听，点下一首还在给别人发指令」就是漏了这里的清理）。
        if (wasInRoom && !m.inRoom) teardownSync()
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(busy = false, error = message)
    }

    private fun unsupported() {
        _state.value = _state.value.copy(
            busy = false,
            loading = false,
            supported = false,
            inRoom = false,
            room = null,
            error = NeteaseListenTogetherBackend.UNSUPPORTED_MESSAGE,
        )
    }

    private fun providerMismatchMessage(m: Joinability.ProviderMismatch): String =
        "该房间属于「${m.roomProviderId}」音源，当前音源是「${m.currentProviderId}」。\n" +
            "房间里的曲目无法用当前音源解析，请先切换音源再加入。"

    // ======================== 内部：指令同步（P2） ========================

    /** 拉一次房间快照并应用。轮询循环每 tick 调一次。 */
    private suspend fun pollSnapshot() {
        val roomId = _state.value.room?.roomId ?: return
        when (val s = backend.snapshot(roomId)) {
            is BackendResult.Success -> applySnapshot(s.data)
            // 快照失败不清在房状态、不上报错误：下个周期再试，
            // 一次网络抖动不该打断正在播放的音乐。
            else -> Unit
        }
    }

    /**
     * 应用一份房间快照。回声抑制 + 序号判新的唯一入口。
     *
     * 判据（方案 §3.3(b)，实测校准）：
     * - `serverSeq`（服务端毫秒时间戳，单调递增）判「有没有新指令」；
     * - `userId == 我 && clientSeq ∈ (0, lastSent]` 判「这是我发的回声」——
     *   **单靠 clientSeq 不行**：同账号另一台设备的序号与本机交错，
     *   但它的 clientSeq 不在本机已发区间里，会正确地被应用。
     */
    internal suspend fun applySnapshot(snap: RoomSnapshot) {
        // 纵深防御：生产路径（轮询循环 / join）都在房时才调这里，但退房与快照之间存在
        // 竞态窗口 —— 退房后到达的快照绝不允许再驱动本机播放。
        if (!_state.value.inRoom) return
        val cmd = snap.command ?: return
        val uid = myUserId()
        if (uid != 0L && cmd.userId == uid && cmd.clientSeq in 1..lastSentClientSeq) {
            if (cmd.serverSeq > lastSeenServerSeq) lastSeenServerSeq = cmd.serverSeq
            return
        }
        if (cmd.serverSeq <= lastSeenServerSeq) return
        lastSeenServerSeq = cmd.serverSeq
        applyCommand(cmd)
    }

    /** 把一条远端指令落成本机播放动作。 */
    private suspend fun applyCommand(cmd: RoomPlaybackCommand) {
        val pb = playback ?: return
        // 上游将来新增的指令类型语义不明，按旧语义硬套会乱切歌——保守跳过。
        if (cmd.commandType == LtCommandType.UNKNOWN) return

        val now = currentTimeMillis()
        val targetPlaying = cmd.playStatus != LtCommandType.PAUSE
        val targetSong = cmd.targetSongId

        if (targetSong != null && targetSong != localSongId(pb.state.value)) {
            // 远端切歌：优先在本地队列里定位（保留队列上下文与顺序），
            // 找不到再单曲替换队列 —— 新进房者没有房主的队列，这是唯一的兜底播放路径。
            val idx = pb.state.value.queue.indexOfFirst { bareSongIdOf?.invoke(it.mediaId) == targetSong }
            val mediaId = mediaIdForSong?.invoke(targetSong)
            if (idx >= 0) {
                pb.playAt(idx)
            } else if (mediaId != null) {
                pb.play(mediaId)
            } else {
                return // 连 mediaId 都构造不出来（音源未接线），不动本机
            }
            roomSync = RoomSync(targetSong, targetPlaying, cmd.progressMs, now)
            lastObserved = null
            // GOTO+PAUSE：切歌完成后立即暂停。加载会自动开播，这里先尽力按住。
            if (!targetPlaying) pb.pause()
            pendingSeek = PendingSeek(targetSong, cmd.progressMs)
            applyGuardUntilMs = now + APPLY_GUARD_MS
            return
        }

        // 同歌：对齐播放态 + 进度
        val st = pb.state.value
        if (!targetPlaying && st.isPlaying) pb.pause()
        if (targetPlaying && !st.isPlaying) pb.resume()
        val song = targetSong ?: roomSync?.songId ?: return
        if (!st.isBuffering && abs(st.positionMs - cmd.progressMs) > APPLY_SEEK_THRESHOLD_MS) {
            pb.seekTo(cmd.progressMs)
        }
        roomSync = RoomSync(song, targetPlaying, cmd.progressMs, now)
        lastObserved = null
        applyGuardUntilMs = now + APPLY_GUARD_MS
    }

    /**
     * 进度漂移校正：本机与房间锚点偏差超阈值才 seek（方案 §3.3(c)：
     * 不做阈值 → 每轮都 seek（持续打嗝）；不做外推 → 本来同步也被反复 seek）。
     *
     * 覆盖两类漂移：buffer 停走恢复后的落后、长时间播放的时钟速率差。
     */
    private suspend fun driftCorrect() {
        val pb = playback ?: return
        if (!_state.value.inRoom || !followEnabled) return
        val anchor = roomSync ?: return
        val st = pb.state.value
        if (st.isBuffering) return
        val mySong = localSongId(st) ?: return
        if (mySong != anchor.songId) return
        val now = currentTimeMillis()
        val expected = anchor.expectedAt(now)
        if (abs(st.positionMs - expected) > APPLY_SEEK_THRESHOLD_MS) {
            // seek 前先把锚点移到目标：observe 看到这次跳变时与锚点一致，不会误报成用户 seek
            roomSync = anchor.copy(progressMs = expected, anchorWallMs = now)
            pb.seekTo(expected)
        }
    }

    /**
     * 本机播放状态观察入口（[start] 里 collect [PlaybackController.state]）。
     *
     * 与 [roomSync] 对比，差异即「本机操作」→ 上报；一致则什么也不做。
     * 这套「差异即操作」的判定天然覆盖了远端指令的落地：应用远端指令时
     * [roomSync] 已被置为目标态，本机随后到达目标态 → 无差异 → 不回报。
     */
    internal fun onPlaybackObserved(st: PlaybackUiState) {
        val pb = playback ?: return
        if (!_state.value.inRoom || !followEnabled) {
            lastObserved = null
            return
        }
        val mySong = localSongId(st)
        if (mySong == null) {
            // 音源不一致 / 没在播：状态不可同步，清观察基准（不同音源的 id 塞进房间会毒害对端）
            lastObserved = null
            return
        }
        val now = currentTimeMillis()
        val last = lastObserved
        lastObserved = Observed(mySong, st.isPlaying, st.positionMs, now, st.isBuffering)

        // 远端切歌的进度落地：加载完成、本机曲目刚到位时立刻补 seek，不等 5s 的漂移校正
        pendingSeek?.let { p ->
            if (p.songId == mySong) {
                pendingSeek = null
                if (!st.isBuffering && abs(st.positionMs - p.progressMs) > APPLY_SEEK_THRESHOLD_MS) {
                    roomSync = roomSync?.copy(progressMs = p.progressMs, anchorWallMs = now)
                    pb.seekTo(p.progressMs)
                }
            }
        }

        val anchor = roomSync
        if (anchor == null) {
            // 房间还没有任何已知播放状态（典型：建房者正在听歌）。
            // 把本机状态作为房间的初始状态广播出去 —— 否则没人上报，房间永远没有 playCommand。
            if (st.currentTrack == null) return
            roomSync = RoomSync(mySong, st.isPlaying, st.positionMs, now)
            enqueueReport(
                commandType = if (st.isPlaying) CMD_PLAY else CMD_PAUSE,
                isPlaying = st.isPlaying,
                progressMs = st.positionMs,
                formerSongId = null,
                targetSongId = mySong,
            )
            return
        }
        // 远端指令落地窗口内不上报：加载抖动不是用户操作（见 [applyGuardUntilMs]）
        if (now < applyGuardUntilMs) return

        when {
            mySong != anchor.songId -> {
                roomSync = RoomSync(mySong, st.isPlaying, st.positionMs, now)
                enqueueReport(CMD_GOTO, st.isPlaying, st.positionMs, anchor.songId, mySong)
            }

            st.isPlaying != anchor.isPlaying -> {
                roomSync = RoomSync(mySong, st.isPlaying, st.positionMs, now)
                enqueueReport(
                    if (st.isPlaying) CMD_PLAY else CMD_PAUSE,
                    st.isPlaying, st.positionMs, mySong, mySong,
                )
            }

            else -> {
                // seek 检测需要**双重跳变**：
                // ① 相对上次观察的位移 ≠ 墙钟位移（位置跳了）；
                // ② 相对房间锚点的期望位置也跳了 —— 排除引擎自己 seek 的落地（那不是用户操作）。
                val roomExpected = anchor.expectedAt(now)
                val jumpFromLast = if (last != null && last.songId == mySong &&
                    !st.isBuffering && !last.isBuffering
                ) {
                    val wallDelta = now - last.wallMs
                    val posDelta = st.positionMs - last.positionMs
                    abs(posDelta - if (st.isPlaying) wallDelta else 0L)
                } else {
                    0L
                }
                val jumpFromRoom = abs(st.positionMs - roomExpected)
                if (jumpFromLast > REPORT_SEEK_JUMP_MS && jumpFromRoom > REPORT_SEEK_JUMP_MS) {
                    roomSync = RoomSync(mySong, st.isPlaying, st.positionMs, now)
                    enqueueReport(CMD_PROGRESS, st.isPlaying, st.positionMs, mySong, mySong)
                } else if (jumpFromRoom <= REPORT_SEEK_JUMP_MS) {
                    // 正常播放：把锚点贴回本机实际，吸收缓冲/时钟漂移，避免误差累积成误报
                    roomSync = RoomSync(mySong, st.isPlaying, st.positionMs, now)
                }
            }
        }
    }

    /** 退房 / 被踢 / 换房时**统一**清理同步层状态（方案 §3.3(d) 的硬要求）。 */
    private fun teardownSync() {
        lastSeenServerSeq = 0L
        lastSentClientSeq = 0L
        clientSeq = initialClientSeq()
        roomSync = null
        lastObserved = null
        pendingSeek = null
        applyGuardUntilMs = 0L
        lastReportEnqueued = null
        reportChannel.tryReceive() // CONFLATED 只有一条在途：清掉，退房后不能再发指令
        followEnabled = true
    }

    private fun localSongId(st: PlaybackUiState): String? =
        st.currentTrack?.let { t -> bareSongIdOf?.invoke(t.id) }

    private fun enqueueReport(
        commandType: String,
        isPlaying: Boolean,
        progressMs: Long,
        formerSongId: String?,
        targetSongId: String?,
    ) {
        val seq = ++clientSeq
        lastSentClientSeq = seq
        val cmd = ReportCmd(commandType, isPlaying, progressMs, formerSongId, targetSongId, seq)
        lastReportEnqueued = cmd
        reportChannel.trySend(cmd)
    }

    /**
     * clientSeq 随机起点：同账号在两台设备进同一房间时，两边的序号都从小数字开始
     * 必然撞号（对端的 seq=1 会被本机误判成自己的回声）。随机化后撞号概率可忽略。
     */
    private fun initialClientSeq(): Long = (currentTimeMillis() % 1_000_000L) + 1L

    companion object {
        /** 房间状态轮询间隔。远端变化的观测延迟下限就是它。 */
        const val POLL_INTERVAL_MS = 5_000L

        /**
         * 心跳间隔。实测服务端在心跳回包里给 `timeSpan: 30` —— 以服务端为准，
         * 别自己拍一个更小的值（更密只会更容易被风控）。
         */
        const val HEARTBEAT_INTERVAL_MS = 30_000L

        /** 不在房间时的轮询降频倍数。 */
        const val IDLE_POLL_MULTIPLIER = 3L

        /**
         * 应用远端进度的 seek 阈值。方案 §3.3(c)：偏差大于它才 seek；
         * 阈值内不动 —— 外推过的锚点与本机的微小差异不该变成反复打嗝。
         */
        const val APPLY_SEEK_THRESHOLD_MS = 2_000L

        /**
         * 本机 seek 的上报判定：位置跳变超过它才认定是用户拖动。
         * 大于 [APPLY_SEEK_THRESHOLD_MS]（否则自己的漂移校正会被当成 seek 上报）。
         */
        const val REPORT_SEEK_JUMP_MS = 2_500L

        /**
         * 应用远端切歌后的落地窗口：加载中 `isPlaying=false` 不是「用户暂停」，
         * 窗口内的状态变化不上报。窗口比典型加载时长略长，宁可漏报一次操作
         * （下次操作会带上最新状态），也不能把对方的歌暂停掉。
         */
        const val APPLY_GUARD_MS = 8_000L

        /** 指令类型。实测**大小写不敏感**，统一大写（服务端自己推导时也产出大写）。 */
        const val CMD_GOTO = "GOTO"
        const val CMD_PLAY = "PLAY"
        const val CMD_PAUSE = "PAUSE"
        const val CMD_PROGRESS = "PROGRESS"

        const val ALREADY_IN_ROOM =
            "你已在一个一起听房间里。一个账号同时只能在一个房间，请先退出当前房间再建房或加入。"

        /**
         * `accept` 失败的中性文案。
         *
         * 实测 `488` 对「房间不存在」与「邀请不是给你的」返回**完全相同**的响应，
         * 所以这里**刻意不指明**具体是哪种 —— 编一个具体原因等于骗用户。
         */
        const val INVALID_INVITE = "邀请无效或已过期（房间可能已结束或被取消）"

        /** 实测 `accept` 对无效邀请返回的上游 code。 */
        const val INVITE_REJECTED_CODE = 488

        const val PLAY_STATUS_PLAY = "PLAY"
        const val PLAY_STATUS_PAUSE = "PAUSE"
    }
}
