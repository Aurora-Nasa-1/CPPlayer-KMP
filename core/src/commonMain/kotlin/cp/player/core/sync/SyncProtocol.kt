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
