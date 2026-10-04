package cp.player.core.listentogether

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
 * 这里只对**稳定字段**做显式取值，取不到就是 null。
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

// ======================== 解析 ========================

/**
 * 解析 `listentogether/status` 的响应。
 *
 * 实测原文：`{"code":200,"data":{"anotherDeviceInfo":null,"anotherFollowStatus":false,
 * "inRoom":false,"roomInfo":null,"status":null},"message":""}`
 */
fun parseMembership(root: JsonElement): ListenTogetherMembership {
    val data = root.obj("data") ?: return ListenTogetherMembership(false, null, null)
    return ListenTogetherMembership(
        inRoom = data.bool("inRoom") ?: false,
        room = data.obj("roomInfo")?.let(::parseRoom),
        connectionStatus = data.str("status"),
    )
}

/** 解析 `roomInfo`（实测结构见方案 §9.2）。 */
fun parseRoom(o: JsonObject): ListenTogetherRoom = ListenTogetherRoom(
    roomId = o.str("roomId").orEmpty(),
    creatorId = o.long("creatorId") ?: 0L,
    roomType = o.str("roomType"),
    createdAtMs = o.long("roomCreateTime"),
    effectiveDurationMs = o.long("effectiveDurationMs"),
    waitMs = o.long("waitMs"),
    members = o["roomUsers"]?.asArray()?.mapNotNull { e ->
        val m = e.obj() ?: return@mapNotNull null
        val uid = m.long("userId") ?: return@mapNotNull null
        ListenTogetherMember(
            userId = uid,
            nickname = m.str("nickname").orEmpty(),
            avatarUrl = m.str("avatarUrl"),
        )
    }.orEmpty(),
)

/**
 * 解析 `listentogether/sync/playlist/get` 的响应。
 *
 * 实测：上报过队列之后，`data` 里会同时带 `playCommand` 与 `playlist`；
 * 从没上报过时 `data` 是空对象 `{}` ⇒ 两个字段都为 null。
 */
fun parseSnapshot(root: JsonElement): RoomSnapshot {
    val data = root.obj("data") ?: return RoomSnapshot(null, null)
    return RoomSnapshot(
        command = data.obj("playCommand")?.let(::parseCommand),
        playlist = data.obj("playlist")?.let(::parsePlaylist),
    )
}

private fun parseCommand(o: JsonObject): RoomPlaybackCommand = RoomPlaybackCommand(
    commandType = LtCommandType.from(o.str("commandType")),
    playStatus = LtCommandType.from(o.str("playStatus")),
    progressMs = o.long("progress") ?: 0L,
    targetSongId = o.str("targetSongId")?.takeIf { it.isNotEmpty() },
    formerSongId = o.str("formerSongId")?.takeIf { it.isNotEmpty() },
    userId = o.long("userId") ?: 0L,
    clientSeq = o.long("clientSeq") ?: 0L,
    serverSeq = o.long("serverSeq") ?: 0L,
)

private fun parsePlaylist(o: JsonObject): RoomPlaylist = RoomPlaylist(
    songIds = o.obj("displayList")?.get("result")?.asArray()
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty(),
    replace = o.bool("replace") ?: false,
    versions = o["version"]?.asArray()?.mapNotNull { e ->
        val v = e.obj() ?: return@mapNotNull null
        val uid = v.long("userId") ?: return@mapNotNull null
        uid to (v.long("version") ?: 0L)
    }?.toMap().orEmpty(),
)

// ======================== 安全取值 ========================
// 上游任何一层都可能是 null 或类型漂移（实测 progress 上报字符串、回读数字），
// 所以一律走这几个不抛异常的取值器，而不是 `!!` 或强转。

private fun JsonElement.obj(): JsonObject? = runCatching { jsonObject }.getOrNull()
internal fun JsonElement.obj(key: String): JsonObject? =
    runCatching { (this as? JsonObject)?.get(key)?.jsonObject }.getOrNull()
internal fun JsonObject.str(key: String): String? =
    runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()

internal fun JsonObject.bool(key: String): Boolean? =
    runCatching { get(key)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull()

/** 同时接受数字与字符串形态（实测 `progress` 上报字符串、回读数字）。 */
internal fun JsonObject.long(key: String): Long? =
    runCatching { get(key)?.jsonPrimitive?.longOrNull }.getOrNull()
        ?: runCatching { get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull() }.getOrNull()

private fun JsonElement.asArray(): List<JsonElement>? = runCatching { jsonArray }.getOrNull()?.toList()
