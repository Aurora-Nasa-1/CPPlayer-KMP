package cp.player.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 信标里用于确认「对面确实是 CPPlayer」的应用名。对不上的一律丢弃。 */
const val SYNC_APP_NAME = "CPPlayer"

/**
 * 同步协议版本。
 *
 * 版本不符时**丢弃并忽略**（不试图兼容）：设备发现阶段两端能做的事很少，
 * 硬着头皮兼容只会让「连上了但行为诡异」比「干脆连不上」更难排查。
 * ⚠️ 目前不兼容只表现为「搜不到对方」，这是个已知的可观测性缺口 ——
 * 见 [SyncDiscovery] 的 KDoc。
 */
const val SYNC_PROTOCOL_VERSION = 1

/**
 * 组播地址与端口。
 *
 * `239.255.x.x` 属于**管理范围（administratively scoped）**组播地址段，
 * 路由器默认不会把它转发到公网 —— 这正是「只在本局域网内被发现」所需要的。
 * 端口刻意选在高位段（38085 由 "CP" 的 ASCII 0x43/0x50 拼出），避开常用服务端口。
 */
const val SYNC_BEACON_GROUP = "239.255.72.80"
const val SYNC_BEACON_PORT = 38085

/** 信标广播间隔。3 秒是「发现够快」与「不刷屏」之间的折中。 */
const val BEACON_INTERVAL_MS = 3_000L

/** 超过这个时间没收到信标就认为对方离线（= 4 个广播周期，容忍偶发丢包）。 */
const val PEER_TIMEOUT_MS = 12_000L

/** 离线后多久从设备表里彻底忘掉（避免列表无限增长）。 */
const val PEER_FORGET_MS = 10 * 60_000L

/**
 * 发现信标。
 *
 * ### ⚠️ 这里**没有**令牌，也**不能**有
 * 信标是**广播**的：同网段任何设备都能收到，包括不怀好意的。
 * 访问令牌、账号 uid、音源凭据一律不进信标。
 * [fingerprint] 只是配对密钥的**短哈希**，用途单一：让对端判断
 * 「这台设备我配过、且密钥还是那个」——它不可逆推出密钥，也不授权任何操作。
 * 真正的同步请求由已有的 `/api/v1/sync/...` 用配对令牌鉴权（见方案 §3.3）。
 *
 * @property streamPort 对端可被访问的端口（复用现有 `LocalServer` 的端口）。
 *   发现只负责回答「谁在、在哪、走哪个端口」，不负责建连。
 * @property sentAt 发送时刻。仅用于诊断与未来的时钟校正，**不作为去重依据**。
 */
@Serializable
data class SyncBeacon(
    val app: String = SYNC_APP_NAME,
    val protocol: Int = SYNC_PROTOCOL_VERSION,
    val deviceId: String,
    val name: String,
    val platform: String,
    val streamPort: Int,
    val appVersion: String = "",
    val fingerprint: String? = null,
    val sentAt: Long,
)

/**
 * 信标的编解码。
 *
 * 解码必须**极度宽容**：这条通道上什么脏数据都可能出现 ——
 * 别的程序占用同一端口、半截 UDP 包、旧版本格式、甚至故意构造的垃圾。
 * 任何解析失败一律返回 `null` 由调用方丢弃，**绝不抛异常**：
 * 一个畸形包不该把发现循环打断。
 */
object Beacons {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(beacon: SyncBeacon): String = json.encodeToString(SyncBeacon.serializer(), beacon)

    /**
     * @return 解析成功且**确实是本应用、协议版本相符**时返回信标；否则 null。
     *
     * 版本与应用的判定刻意放在这里而不是调用方：两处各判一次必然漂移，
     * 而漂移的表现是「明明同一段代码，一边过滤一边不过滤」。
     */
    fun decode(text: String): SyncBeacon? {
        if (text.isBlank()) return null
        val beacon = runCatching {
            json.decodeFromString(SyncBeacon.serializer(), text)
        }.getOrNull() ?: return null
        if (beacon.app != SYNC_APP_NAME) return null
        if (beacon.protocol != SYNC_PROTOCOL_VERSION) return null
        if (beacon.deviceId.isBlank()) return null
        return beacon
    }
}
