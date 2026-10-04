package cp.player.core.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * 局域设备发现。
 *
 * ### 职责边界
 * 只回答一个问题：**「现在同一网段里有哪些 CPPlayer，它们在哪、走哪个端口」**。
 * 它不建连、不鉴权、不传数据 —— 传输复用已有的 `LocalServer`（Ktor），
 * 鉴权走配对令牌（见方案 §3.4）。把「发现」和「传输」分开，是因为两者
 * 的失败模式完全不同：发现失败是「搜不到设备」，传输失败是「连上了但报错」，
 * 混在一起会让这两句诊断都说不清楚。
 *
 * ### 已知的可观测性缺口
 * 协议版本不符、被防火墙拦掉、Wi-Fi 省电丢包 —— 这三件事在实现里都表现为
 * **「列表就是空的」**，用户无从区分。当前只把「能否开始监听」的失败放进
 * [lastError]；把上面三种情况也区分开需要另做工作（方案 §8.5 已记为待办）。
 *
 * 所有 `StateFlow` 都是只读视图，实现内部自行维护。
 */
interface SyncDiscovery {

    /** 已知设备（最新的排在最前，**不含本机**）。含刚掉线的，用 [PeerState.isOnline] 区分。 */
    val peers: StateFlow<List<PeerState>>

    /** 是否已成功开始监听。 */
    val running: StateFlow<Boolean>

    /** 最近一次启动失败的原因；null 表示正常。 */
    val lastError: StateFlow<String?>

    /** 幂等：已在运行则什么都不做。 */
    fun start()

    /** 停止监听并释放端口。**必须幂等且不抛** —— 它挂在退出/重配路径上。 */
    fun stop()
}

/**
 * 平台工厂。
 *
 * Android 与 Desktop 共用一份实现（`core/src/jvmMain` 是两者的公共源集），
 * 因为 `java.net.MulticastSocket` 两端都可用、组播地址与端口也完全一致。
 * 唯一需要平台配合的是 Android 的 **MulticastLock** —— 不做它，熄屏后
 * 系统会丢弃组播包。那部分不在本函数里，而是由「激进保活」设置项
 * （`cp.player.app.platform.applyAggressiveStandby`）负责，刻意保持解耦：
 * 发现层不该知道是哪个平台在替它保 Wi-Fi。
 *
 * ⚠️ [resolveFingerprint] 必须留在**参数表最后**：它是主 lambda 型参数，
 * 在它后面加参数会让既有调用点静默改绑。
 *
 * @param resolveStreamPort 对端找到本机后应该访问哪个端口。做成 lambda 而不是值，
 *   是因为端口可能在运行中变化（用户改配置、端口被占后自动换），
 *   在构造时读一次会让信标广播一个已经失效的端口。
 * @param resolveFingerprint 配对密钥的短哈希；未配对时返回 null。
 */
expect fun createSyncDiscovery(
    identity: DeviceIdentity,
    resolveStreamPort: () -> Int,
    resolveFingerprint: () -> String? = { null },
): SyncDiscovery
