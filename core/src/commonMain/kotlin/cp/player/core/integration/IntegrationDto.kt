package cp.player.core.integration

import cp.player.core.control.LocalServerConfig
import kotlinx.serialization.Serializable

// ============ 契约常量 ============

/** 描述符与 `meta` 里的应用名；集成方据此确认「连上的确实是 CPPlayer」。 */
const val INTEGRATION_APP_NAME = "CPPlayer"

/** 对外契约版本号。破坏性变更才递增，并另开 v2 前缀。 */
const val INTEGRATION_API_VERSION = 1

// 搜索类型沿用音源侧既有的魔数，不要重新编号 —— 它们是线上契约的一部分
const val SEARCH_TYPE_SONG = 1
const val SEARCH_TYPE_ALBUM = 10
const val SEARCH_TYPE_ARTIST = 100
const val SEARCH_TYPE_PLAYLIST = 1000

/** 支持的搜索类型。不在表里的 `type` 一律 400，而不是透传给上游。 */
val SUPPORTED_SEARCH_TYPES: List<Int> =
    listOf(SEARCH_TYPE_SONG, SEARCH_TYPE_ALBUM, SEARCH_TYPE_ARTIST, SEARCH_TYPE_PLAYLIST)

/**
 * 批量端点单次上限。
 *
 * 必须限：批量端点若不限量，就是「一次请求放大成 N 次上游请求」，
 * 会被当成免费的 DoS 放大器。
 */
const val MAX_TRACK_BATCH_SIZE = 200

// ============ 错误码 ============

/**
 * 对外错误码。
 *
 * **稳定可分支**：集成方会写 `if (error.code == "...")`，所以这些字符串是契约，
 * 不是给人读的提示。文案（[ApiErrorBody.message]）才是不保证稳定的那部分。
 */
object ApiErrorCodes {
    const val BAD_REQUEST = "bad_request"
    const val UNAUTHORIZED = "unauthorized"
    const val FACE_DISABLED = "face_disabled"
    const val NOT_FOUND = "not_found"
    const val UNSUPPORTED = "unsupported"
    const val UPSTREAM_FAILED = "upstream_failed"
    const val INTERNAL = "internal_error"
}

// ============ GET /api/v1/meta ============

/**
 * `capabilities` 的取值。
 *
 * 两个条件项刻意如实反映开关状态：`STREAM` 随 [LocalServerConfig.exposeStream]，
 * `PLAYBACK_CONTROL` 随 [LocalServerConfig.allowRemoteControl]。
 * 只报「实现存在」不报「开关状态」，等于让集成方去试错 —— 那正是能力清单要消灭的东西。
 */
object IntegrationCapabilities {
    /** 版本协商与能力清单。 */
    const val META = "meta"

    /** 已加载音源列表。 */
    const val PROVIDERS = "providers"

    /** 搜索。 */
    const val SEARCH = "search"

    /** 单曲详情。 */
    const val TRACK = "track"

    /** 批量曲目详情。 */
    const val TRACK_BATCH = "trackBatch"

    /** 播放状态（只读）。 */
    const val PLAYBACK = "playback"

    /**
     * 播控**写**操作（`/api/v1/playback/{action}`）。
     *
     * 只在 `allowRemoteControl = true` 时出现 —— 与 [STREAM] 同理。
     */
    const val PLAYBACK_CONTROL = "playbackControl"

    /** 播放状态事件流（SSE，`/api/v1/events`）。 */
    const val EVENTS = "events"

    /** 媒体面（`/stream`）可用。 */
    const val STREAM = "stream"

    /**
     * 当前配置下真正可用的能力。
     *
     * ⚠️ [PLAYBACK_CONTROL] 是**第二道**开关：`exposeDataApi` 开着不代表有写能力。
     */
    fun of(config: LocalServerConfig): List<String> = buildList {
        add(META)
        add(PROVIDERS)
        add(SEARCH)
        add(TRACK)
        add(TRACK_BATCH)
        add(PLAYBACK)
        add(EVENTS)
        if (config.exposeStream) add(STREAM)
        if (config.allowRemoteControl) add(PLAYBACK_CONTROL)
    }
}

@Serializable
data class ProviderRefDto(
    val id: String,
    val name: String,
    val version: String,
)

/**
 * @property capabilities 真正可用的能力清单，见 [IntegrationCapabilities]。
 * @property provider 当前活跃音源；没有活跃音源时为 `null`。
 * @property loggedIn 当前活跃音源是否已登录。**只读判断，不暴露任何凭据。**
 */
@Serializable
data class MetaDto(
    val app: String,
    val apiVersion: Int,
    val capabilities: List<String>,
    val provider: ProviderRefDto?,
    val loggedIn: Boolean,
)

// ============ GET /api/v1/providers ============

/**
 * 已加载音源。
 *
 * @property type 实现类型（`JNI` / `BINARY` / `WEBSOCKET` / `HTTP`）。
 * @property active 是否为当前活跃音源；**最多一个**为 true。
 */
@Serializable
data class ProviderDto(
    val id: String,
    val name: String,
    val version: String,
    val type: String,
    val active: Boolean,
)

@Serializable
data class ProvidersDto(
    val providers: List<ProviderDto>,
)

// ============ 曲目 / 歌单 / 歌手 ============

/**
 * 对外曲目。
 *
 * @property streamUrl **本机** `/stream` 地址（含令牌），绝不能是上游签名 URL。
 *   没有可播 id 时为 `null` —— 给一个必然 404 的地址比给 null 更糟。
 */
@Serializable
data class TrackDto(
    val mediaId: String,
    val name: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val durationMs: Long,
    val streamUrl: String?,
)

@Serializable
data class PlaylistDto(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val creatorName: String?,
)

@Serializable
data class ArtistDto(
    val id: Long,
    val name: String,
    val avatarUrl: String?,
)

// ============ POST /api/v1/search ============

/**
 * 搜索请求。
 *
 * 可选字段**必须** `= null`（不能是 `-1` / `""` 之类的哨兵）：
 * 「客户端没传」与「客户端传了默认值」是两回事，哨兵会把前者变成后者。
 */
@Serializable
data class SearchRequestDto(
    val keywords: String,
    val type: Int? = null,
    val limit: Int? = null,
)

@Serializable
data class SearchResponseDto(
    val songs: List<TrackDto>,
    val playlists: List<PlaylistDto>,
    val artists: List<ArtistDto>,
)

// ============ POST /api/v1/tracks/batch ============

@Serializable
data class TrackBatchRequestDto(
    val mediaIds: List<String>,
)

/**
 * 批量结果的一项。
 *
 * `track` 与 `error` **互斥且可为 null**：单项失败不整体失败，
 * 集成方按位置取值，所以顺序必须与请求一致。
 */
@Serializable
data class TrackBatchItemDto(
    val mediaId: String,
    val track: TrackDto?,
    val error: String?,
)

// ============ GET /api/v1/playback ============

/**
 * 播放状态只读快照。
 *
 * 队列**内容**不下发，只有 [queueLength] —— 队列是播放状态不是音源数据，
 * 需要内容时用 `tracks/batch` 按 `mediaId` 取。
 */
@Serializable
data class PlaybackDto(
    val isPlaying: Boolean,
    val positionMs: Long,
    val currentIndex: Int,
    val qualityLevel: String?,
    val currentTrack: TrackDto?,
    val queueLength: Int,
)

// ============ 播控动作 ============

/**
 * 播控动作名。
 *
 * 用字符串而不是枚举：动作名是**线上契约**（出现在 URL 路径里），
 * 枚举的 `name` 一旦重命名就会静默改变 URL，而字符串常量可以钉在测试里。
 */
object PlaybackAction {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val NEXT = "next"
    const val PREVIOUS = "previous"

    /** 可用动作。错误信息会把它列出来。 */
    val SUPPORTED: List<String> = listOf(PLAY, PAUSE, NEXT, PREVIOUS)

    /**
     * **刻意不做**的动作及原因。
     *
     * 这些名字看起来「该有」，但落到内核上要么没有对应操作，要么只有破坏性操作。
     * 收到时返回 400 并把原因说出来，比静默退化成一个不相干的动作好。
     */
    val DELIBERATELY_ABSENT: Map<String, String> = mapOf(
        "stop" to "CPPlayer 的内核没有非破坏性的停止（只有 pause 与会清空队列的 clearQueue），" +
            "所以 stop 刻意不实现。需要停止播放请用 pause。",
    )
}

// ============ 错误响应 ============

/** 错误体。**code 稳定可分支，message 只是给人读的，不保证稳定。 */
@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
)

/**
 * 错误响应。错误统一包在 `error` 里，与成功响应形状不同 ——
 * 集成方靠「有没有 error」分支，而不是靠状态码猜。
 */
@Serializable
data class ApiErrorDto(
    val error: ApiErrorBody,
)
