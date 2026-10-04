package cp.player.core.sync

import cp.player.core.insights.PlayRecord
import kotlinx.serialization.Serializable

/**
 * 局域网同步的协议与合并规则。
 *
 * ### 为什么先做听歌记录，而不是歌单/收藏
 * 听歌记录是 **append-only** 的事件流：合并规则就是「按 id 取并集」，
 * 与顺序无关、天然幂等 —— 这正好满足「无论谁新谁旧、或者交替使用都要同步」：
 * 两台设备各自积累自己的记录，交换后都收敛到同一份超集，**不存在冲突**。
 * 收藏与歌单是「可变状态」，合并要谈 LWW 与删除语义，那是下一步（方案 §3.5）。
 *
 * ### 为什么传输是独立的轻量 HTTP 面，而不是挂进现有 `LocalServer`
 * `LocalServer` 是**对外契约面**（`/stream` + `/api/v1/...` 给第三方集成），
 * 它的开关、端口、绑定地址、文档都是「给别人用」的语义。
 * 设备间同步是**私有面**，只在本应用的两个实例之间发生 —— 两者变更节奏
 * 完全不同，混在一起会让「关掉对外集成」误伤同步、或反之。
 * 因此同步用自己的端口与自己的开关，互不影响。
 */

/** 同步 HTTP 服务端口。TCP，固定值：两端都必须知道对方在哪儿听。 */
const val SYNC_HTTP_PORT = 38086

/** 同步路由前缀。 */
const val SYNC_HTTP_PREFIX = "/api/v1/sync"

/** 拉取对端全部听歌记录。 */
const val SYNC_ROUTE_RECORDS = "$SYNC_HTTP_PREFIX/records"

/** 无缝转移播放：把「正在播什么、播到哪了」推给目标设备，由它接手。 */
const val SYNC_ROUTE_HANDOFF = "$SYNC_HTTP_PREFIX/handoff"

/** 单次交换的记录数上限。超限的**丢弃并少收**，而不是撑爆内存 —— 这是对畸形/恶意请求的唯一防线。 */
const val SYNC_MAX_RECORDS = 20_000

/**
 * 一次交换的快照。
 *
 * ⚠️ 这里**只有听歌记录**：不含账号 uid、不含音源 cookie、不含收藏与歌单。
 * 同步面是**未认证**的（见下），能被拿走的东西必须刻意收窄到「拿了也无大碍」的程度。
 */
@Serializable
data class SyncSnapshot(
    val deviceId: String,
    val name: String = "",
    val records: List<PlayRecord> = emptyList(),
)

/**
 * 记录合并（纯函数）。
 *
 * 按 [PlayRecord.id] 取**并集**：两边都有的（同一次收听在两端各存了一份）保留一份；
 * 只有一边有的并入。结果按 `startedAt` 升序 —— 与本地采集的排序一致，调用方不必再排。
 *
 * 幂等与交换律都成立：`merge(a,b) == merge(b,a)`，`merge(merge(a,b),b) == merge(a,b)`。
 * 正因为如此，「谁先谁后同步」「交替使用」才会收敛到同一份，而不需要时钟或版本向量。
 */
object SyncMerge {

    fun merge(mine: List<PlayRecord>, theirs: List<PlayRecord>): List<PlayRecord> {
        if (theirs.isEmpty()) return mine.sortedBy { it.startedAt }
        if (mine.isEmpty()) return theirs.sortedBy { it.startedAt }
        val seen = HashSet<String>(mine.size + theirs.size)
        return (mine + theirs)
            .filter { seen.add(it.id) }
            .sortedBy { it.startedAt }
    }

    /** 从对端快照里挑出本机还没有的记录（同步落盘时只追加这些，避免重复写盘）。 */
    fun missing(mine: List<PlayRecord>, theirs: List<PlayRecord>): List<PlayRecord> {
        val ids = HashSet<String>(mine.size)
        mine.forEach { ids.add(it.id) }
        return theirs.filter { ids.add(it.id) }
    }

    /**
     * 入站记录的**入参校验**。
     *
     * 同步面在 v1 是未认证的（开关默认关，开启即暴露），所以这里是唯一的防线：
     * 字段越界、id 为空、时间戳离谱的记录一律丢弃 —— 宁可少同步几条，
     * 也不要让一个畸形请求把日历墙撑出一条 9999 小时的柱子。
     *
     * @return 通过校验的记录（逐条判定，坏的那几条丢掉，其余照收）。
     */
    fun sanitize(records: List<PlayRecord>, now: Long): List<PlayRecord> = records.asSequence()
        .filter { it.id.isNotBlank() && it.id.length <= 128 }
        .filter { it.mediaId.isNotBlank() && it.mediaId.length <= 256 }
        .filter { it.playedMs in 0..(8 * 3_600_000L) }
        .filter { it.durationMs in 0..(2 * 3_600_000L) }
        .filter { it.startedAt in (now - TEN_YEARS_MS)..(now + ONE_DAY_MS) }
        .take(SYNC_MAX_RECORDS)
        .toList()

    private const val TEN_YEARS_MS = 10L * 365 * 24 * 3_600_000
    private const val ONE_DAY_MS = 24L * 3_600_000
}

/**
 * 队列里的一首（转移用的瘦身版，与 `playback.QueueItem` 字段对齐但独立定义 ——
 * QueueItem 不是 @Serializable，且同步协议不该依赖播放器内部类型）。
 */
@Serializable
data class HandoffTrack(
    val mediaId: String,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
)

/**
 * 无缝转移播放的请求。
 *
 * ⚠️ 载荷刻意**极简**：只有「放哪首、从哪秒开始」+ 可选的整条队列。
 * 不带音量、不带账号 —— 队列只给 mediaId 列表与当前下标，目标端用
 * `playQueue` 原地重建（后台解析 URL，不阻塞应答）；账号凭据**永远**不进网卡。
 *
 * ### 时序纪律（这是本功能唯一一条铁律）
 * 源端发出请求后**继续播放**，直到收到 `accepted=true` 才暂停自己；
 * 目标端把「真的出声了」作为应答的前提 —— 先停再起会留下一段谁都不响的空窗，
 * 那 0.5 秒的静音就是「无缝」与「卡了一下」的全部区别。
 */
@Serializable
data class HandoffRequest(
    /** 发起方的设备 id（仅用于目标端展示「谁推过来的」，不作鉴权）。 */
    val fromDeviceId: String,
    val fromName: String = "",
    /** 目标端要播的曲目 id（含音源 scheme，如 `ncm://song/123`）。 */
    val mediaId: String,
    val trackName: String = "",
    val artist: String = "",
    /** 源端捕获请求时的进度。目标端接手后会有一点回退（握手耗时内源端还在走），v1 接受 ≤2s。 */
    val positionMs: Long = 0L,
    /** 源端当时是否在播 —— 决定目标端接手后是播还是停在那个位置。 */
    val wasPlaying: Boolean = true,
    val sentAt: Long = 0L,
    /**
     * 整条播放队列（含当前这首）；空 = 只转移当前一首。
     * 队列转移是体验主线（「下一首」在目标端还能用），单首是降级路径。
     */
    val queue: List<HandoffTrack> = emptyList(),
    /** 当前曲目在 [queue] 里的下标；-1 = 无效（目标端忽略队列）。 */
    val queueIndex: Int = -1,
)

/** 转移请求的应答。`accepted=true` 的唯一含义是「目标端已经在放了」。 */
@Serializable
data class HandoffResult(
    val accepted: Boolean = false,
    val message: String? = null,
)

/**
 * 转移请求的入参校验（纯函数，可单测）。
 *
 * 与 [SyncMerge.sanitize] 同一立场：同步面未认证，这里就是防线 ——
 * 畸形请求拒绝掉，而不是让 `play("")` 之类的值一路打进播放引擎。
 */
object HandoffGuard {

    /**
     * 校验并规整请求；不合格返回 null（调用方应回 400）。
     * 合格的返回**规整后**的副本：进度被 clamp 进合法区间，其余原样。
     *
     * 队列的校验策略与单字段不同：**坏条目剔除、整条超限降级为单首**，
     * 而不是整个请求拒绝 —— 队列是体验增强，不该因为一条脏数据让转移整个失败。
     */
    fun sanitized(request: HandoffRequest): HandoffRequest? {
        if (request.mediaId.isBlank() || request.mediaId.length > MAX_MEDIA_ID) return null
        if (request.fromDeviceId.isBlank() || request.fromDeviceId.length > MAX_DEVICE_ID) return null
        if (request.trackName.length > MAX_TEXT || request.artist.length > MAX_TEXT) return null
        val queue = request.queue.asSequence()
            .filter { it.mediaId.isNotBlank() && it.mediaId.length <= MAX_MEDIA_ID }
            .take(MAX_QUEUE)
            .toList()
        // 下标**按当前曲目的 mediaId 重查**，而不是沿用源端的数字 ——
        // 剔除脏条目后原下标会指向别的歌；mediaId 是唯一可靠的身份。
        val index = queue.indexOfFirst { it.mediaId == request.mediaId }.takeIf { it >= 0 } ?: -1
        return request.copy(
            positionMs = request.positionMs.coerceIn(0L, MAX_POSITION_MS),
            queue = queue,
            queueIndex = index,
        )
    }

    private const val MAX_MEDIA_ID = 256
    private const val MAX_DEVICE_ID = 64
    private const val MAX_TEXT = 256

    /** 单首曲目不可能有两小时；越界只可能是构造出来的。 */
    private const val MAX_POSITION_MS = 2L * 3_600_000

    /** 队列上限。个人播放器的队列到不了这个量级；超出按截断处理。 */
    private const val MAX_QUEUE = 500
}
