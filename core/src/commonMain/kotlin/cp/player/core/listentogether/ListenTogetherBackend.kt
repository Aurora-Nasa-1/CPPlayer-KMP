package cp.player.core.listentogether

import cp.player.core.BackendResult

/**
 * 「一起听」后端抽象 —— **每个音源各自实现**。
 *
 * ### 这层抽象解决什么，不解决什么
 * **解决**：同一套 UI、状态机与同步引擎，可以服务多个音源各自的房间协议。
 * **不解决**：跨音源互通。房间里的曲目 id 是提供方音源的 id
 * （`CPMediaId` = `{providerId}://{resourceType}/{resourceId}`），
 * 网易云的房间 id 对咪咕账号毫无意义。**不同音源的房间不能互相加入**，
 * 这一点在 `Joinability` 里如实表达，而不是静默降级。
 *
 * ### 为什么把 `supported` 放在后端而不是让调用方 try
 * 音源能力缺失（换音源才有救）与临时故障（可重试）是**两种不同的处置**，
 * 让它们共用一条 Error 通道会让 UI 把「这个音源做不到」渲染成「出错了，请重试」——
 * 用户重试到天荒地老也没用。所以能力判定是**独立的一次询问**，
 * 与 `BackendProvider.apiMap` 的 `"unsupported"` 是同一套语义。
 */
interface ListenTogetherBackend {

    /** 实现该后端的音源 id（如 `cp_api`），用于判断房间与本机音源是否一致。 */
    val providerId: String

    /**
     * 该音源是否支持一起听。
     *
     * 返回 false 时，UI 应当**把入口置灰并说明原因**，而不是让用户点进去再报错。
     */
    fun isSupported(): Boolean

    /** 当前账号的在房状态（含房间详情）。这是房间详情的**唯一**来源。 */
    suspend fun membership(): BackendResult<ListenTogetherMembership>

    /**
     * 创建房间。
     *
     * ⚠️ 调用方**必须先** [membership] 确认 `inRoom == false`：
     * 一个账号同时只能在一个房间里，建房会**顶掉**已有房间，
     * 而 [end] 只能关不能复活 —— 一次手滑可能清掉别人正在意的房间。
     */
    suspend fun createRoom(): BackendResult<ListenTogetherRoom>

    /** 接受邀请。`roomId` 与 `inviterId` 必须成对（来自 [ListenTogetherInvite.parse]）。 */
    suspend fun accept(roomId: String, inviterId: Long): BackendResult<Unit>

    /** 结束房间。只能关不能复活。 */
    suspend fun end(roomId: String): BackendResult<Unit>

    /**
     * 读回房间快照（远端指令 + 队列）。
     *
     * 这是**唯一的读侧通道** —— 上游不推送，远端状态全靠轮询这里拿。
     */
    suspend fun snapshot(roomId: String): BackendResult<RoomSnapshot>

    /** 心跳，维持「在房」状态。间隔取服务端回包里的 `timeSpan`（实测 30 秒）。 */
    suspend fun heartbeat(
        roomId: String,
        songId: String,
        playStatus: String,
        progressMs: Long,
    ): BackendResult<Unit>

    /**
     * 上报播放指令。
     *
     * 实测：上报的内容会被 [snapshot] 原样读回，这是双向同步成立的依据。
     *
     * @param clientSeq 本机单调递增序号。只用于**识别自己发的指令**（回声抑制），
     *   不要用它判断新旧——别人的序号会与本机交错。
     */
    suspend fun reportPlayCommand(
        roomId: String,
        commandType: String,
        playStatus: String,
        progressMs: Long,
        formerSongId: String?,
        targetSongId: String?,
        clientSeq: Long,
    ): BackendResult<Unit>

    /** 上报同步队列（整表替换）。 */
    suspend fun reportPlaylist(
        roomId: String,
        userId: Long,
        version: Long,
        songIds: List<String>,
    ): BackendResult<Unit>
}

/**
 * 房间加入判定 —— 在**发起加入之前**问一次，而不是等 `accept` 失败再去猜原因。
 *
 * 上游 `accept` 失败只给 `{"code":500,"msg":"API error (code=488): Unknown error"}`，
 * 用户看不懂，我们也没法区分「房间不存在」「不是给你的邀请」「音源不对」。
 * 所以本机能在本地判死的，一律在本地判死。
 */
sealed interface Joinability {

    /** 可以加入。 */
    data object Joinable : Joinability

    /**
     * 房间属于别的音源。
     *
     * 这不是错误而是**物理隔离**：曲目 id 是 provider 作用域的，
     * 就算强行进房也解析不出任何一首歌。所以明确拒绝，并引导用户切音源。
     */
    data class ProviderMismatch(val roomProviderId: String, val currentProviderId: String) : Joinability

    /** 参数不完整（缺 roomId 或 inviterId）。 */
    data class Malformed(val reason: String) : Joinability
}
