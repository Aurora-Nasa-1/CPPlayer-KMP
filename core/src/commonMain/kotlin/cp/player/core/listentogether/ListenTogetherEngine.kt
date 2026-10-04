package cp.player.core.listentogether

import cp.player.core.BackendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 * 「一起听」引擎 —— 房间生命周期、邀请闭环、以及维持「在房」的轮询与心跳。
 *
 * ### 为什么把轮询放在引擎里而不是 UI 的 ScreenModel
 * 「在房」是**跨页面**的状态：用户进了房间之后会去听歌、翻歌单，房间页可能早就出栈了，
 * 但心跳必须继续（否则会被判定离开）。放在 ScreenModel 里会随页面销毁而停掉 ——
 * 表现为「一离开房间页就掉线」。引擎挂在应用级 scope 上。
 *
 * ### 两条实测约束（直接决定了本类的写法）
 * 1. **`accept` 在已进房时无条件返回成功**（不校验 roomId/inviterId）。所以 [join]
 *    **必须先查 [refresh]** 确认不在房间，否则会把「其实没加入」当成加入成功。
 * 2. **`accept` 失败只有笼统的 `code=488`**，无法区分「房间不存在」与「邀请不是给你的」。
 *    所以错误文案只能是中性的 [INVALID_INVITE]，**不许编造更具体的原因**。
 *
 * ### 节奏来自服务端，不是猜的
 * 心跳回包带 `timeSpan`（实测 30 秒），轮询房间状态用更短的固定间隔即可。
 */
class ListenTogetherEngine(
    private val backend: ListenTogetherBackend,
    private val scope: CoroutineScope,
    private val myUserId: () -> Long,
    private val heartbeatInfo: () -> HeartbeatInfo? = { null },
) {

    private val _state = MutableStateFlow(ListenTogetherState())
    val state: StateFlow<ListenTogetherState> = _state.asStateFlow()

    private var loop: Job? = null

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
                if (tick % HEARTBEAT_INTERVAL_MS == 0L) heartbeat()
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
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
            return
        }
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, notice = null)

        when (val r = backend.end(roomId)) {
            is BackendResult.Success -> _state.value = _state.value.copy(
                busy = false, inRoom = false, room = null, connectionStatus = null, notice = "已退出房间",
            )

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

    // ======================== 内部 ========================

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
        _state.value = _state.value.copy(
            loading = false,
            supported = backend.isSupported(),
            inRoom = m.inRoom,
            room = m.room,
            connectionStatus = m.connectionStatus,
            myUserId = myUserId(),
        )
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
