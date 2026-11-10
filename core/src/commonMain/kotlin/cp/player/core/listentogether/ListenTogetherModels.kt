package cp.player.core.listentogether

/**
 * 「一起听」领域模型（纯数据，不依赖任何音源实现）。
 *
 * ### 为什么这些字段都是可空的
 * 上游响应里大量字段在「双人房 / 陌生匹配房」两种场景下取值完全不同
 * （实测：`waitMs` 只在好友邀请时有意义，`unlockChatNeededMs` 只在陌生房出现）。
 * **模型里保留 null 而不塞默认值** —— 塞 0/空串会把「上游没给」伪装成「上游给了 0」，
 * 下游就再也分不清了。这与 `IntegrationService.UPSTREAM_NOT_RETURNED` 是同一条纪律。
 *
 * ### 为什么不用 @Serializable 直接反序列化
 * 上游响应的层级是 `{code, data:{...}}` 且字段名与语义不完全对应（例如
 * `playStatus` 与 `commandType` 是两套枚举，实测还大小写不敏感）。
 * 直接绑 `@Serializable` 会把**上游的偶然命名**固化进领域模型——上游改字段名就全线崩。
 * 解析方只对**稳定字段**做显式取值，取不到就是 null。
 *
 * ### 解析代码不在这里
 * 本文件刻意只放数据类。JSON 取值器与 `listentogether/status`、`sync/playlist/get`
 * 的解析原本也在这里，2026-11-10 随 JSON 兼容层一并清退（调用方改为从强类型返回值
 * 直接构造模型），已无任何调用方，故删除。
 */

/** 一起听房间（只读视图）。 */
data class ListenTogetherRoom(
    val roomId: String,
    val creatorId: Long,
    /** 实测取值 `FRIEND`（好友房）；陌生匹配房另有取值。 */
    val roomType: String?,
    val createdAtMs: Long?,
    /**
     * 房间有效期，毫秒。实测 `1800000`（30 分钟）。
     *
     * ⚠️ 这是**服务端**的有效期，到点房间会失效。UI 必须据此提前提示，
     * 否则用户会遇到「听着听着突然掉线」且不知道为什么。
     */
    val effectiveDurationMs: Long?,
    /** 邀请等待时长，毫秒。实测 `120000`。 */
    val waitMs: Long?,
    val members: List<ListenTogetherMember>,
) {
    /** 当前账号是否房主。房主才能 `end`。 */
    fun isOwner(uid: Long): Boolean = uid != 0L && uid == creatorId
}

/** 房间成员。 */
data class ListenTogetherMember(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String?,
)

/**
 * 当前账号的「在房」状态。
 *
 * @property connectionStatus 实测 `NOT_CONNECTED`。
 *
 * ⚠️ **这不是错误状态**：它表示「未接入网易云 IM 长连接」。
 * 纯 HTTP 客户端（本实现）**永远**是 `NOT_CONNECTED`（实测：发完心跳仍然如此），
 * 因为官方 App 的实时通道是云信 IM（`roomInfo.chatRoomId`）与声网 RTC
 * （`roomInfo.agoraChannelId`），本方案不接这两者。
 *
 * 关键：**`NOT_CONNECTED` 不影响读写房间状态**——`sync/playlist/get` 照样能读到
 * 别人上报的指令（见方案 §9.3）。所以 UI 上不要把它渲染成红色错误，
 * 只需说明「实时推送不可用，进度按 N 秒轮询同步」。
 */
data class ListenTogetherMembership(
    val inRoom: Boolean,
    val room: ListenTogetherRoom?,
    val connectionStatus: String?,
)

/** 播放指令类型。实测**大小写不敏感**（上报 `Play` 原样回读），统一用大写比较。 */
enum class LtCommandType {
    PLAY,
    PAUSE,
    PROGRESS,
    GOTO,
    UNKNOWN,
    ;

    companion object {
        fun from(raw: String?): LtCommandType = when (raw?.uppercase()) {
            "PLAY" -> PLAY
            "PAUSE" -> PAUSE
            "PROGRESS" -> PROGRESS
            "GOTO" -> GOTO
            else -> UNKNOWN
        }
    }
}

/**
 * 房间当前的播放指令（从 `sync/playlist/get` 读回）。
 *
 * @property progressMs 毫秒。实测回读为**数字**（`45000`），即使上报时传的是字符串。
 * @property clientSeq **客户端**给的序号，原样回读 ⇒ 只能用来识别「这是我发的」。
 * @property serverSeq **服务端**分配的序号（实测为毫秒时间戳，单调递增）⇒
 *   **判断有没有新指令必须用它**。单靠 clientSeq 会错——别人的序号与本机交错。
 */
data class RoomPlaybackCommand(
    val commandType: LtCommandType,
    val playStatus: LtCommandType,
    val progressMs: Long,
    val targetSongId: String?,
    val formerSongId: String?,
    val userId: Long,
    val clientSeq: Long,
    val serverSeq: Long,
)

/** 房间同步队列。 */
data class RoomPlaylist(
    val songIds: List<String>,
    val replace: Boolean,
    /** userId → version，用于冲突判定。 */
    val versions: Map<Long, Long>,
)

/**
 * 房间状态快照 —— 引擎的输入。
 *
 * @property command 为 null 表示房间还没有人上报过播放状态。
 */
data class RoomSnapshot(
    val command: RoomPlaybackCommand?,
    val playlist: RoomPlaylist?,
)
