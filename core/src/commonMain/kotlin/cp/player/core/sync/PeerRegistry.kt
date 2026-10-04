package cp.player.core.sync

/**
 * 设备表里的一台对端设备。
 *
 * `lastSeenAt` 而不是布尔 `online`：在线与否是**时间的函数**，不是设备自身的属性 ——
 * 存布尔就必须有人在设备静默消失时负责把它翻回去，而那个「有人」在进程被挂起、
 * 网络切换、对端崩溃这三条路径上全都不可靠。存时间戳，由读取方按 [PEER_TIMEOUT_MS]
 * 现算，就没有「状态没被更新」这个失败模式。
 */
data class PeerState(
    val deviceId: String,
    val name: String,
    val platform: String,
    /** 信标来源地址（UDP 包里的实际源地址，比对方自报的地址可信）。 */
    val address: String,
    /** 对端可被访问的端口。 */
    val streamPort: Int,
    val lastSeenAt: Long,
    val fingerprint: String? = null,
    val appVersion: String = "",
) {
    val displayName: String get() = name.ifBlank { platform }

    /** 是否仍在超时窗口内。 */
    fun isOnline(now: Long, timeoutMs: Long = PEER_TIMEOUT_MS): Boolean =
        now - lastSeenAt <= timeoutMs
}

/**
 * 设备表的**纯函数**维护逻辑。
 *
 * 全部做成纯函数（`List<PeerState>` 进、`List<PeerState>` 出）而不是可变容器：
 * 发现层每秒都会收到若干信标，可变状态一旦与协程调度交错，
 * 「设备表被旧快照整体覆盖」这类问题极难复现（本仓的最近播放补齐就踩过同一个坑）。
 */
object Peers {

    /**
     * 插入或更新一台设备。
     *
     * 语义：
     * - 按 [PeerState.deviceId] 去重（同一台机器改名后仍是同一条，不会变成两条）；
     * - 更新后移到**列表最前**（最近出现的排在前面，设备列表不需要另外排序）；
     * - 对端自报的名字/平台以最新信标为准，但 [PeerState.address] 用**实际来源地址**。
     *
     * @param sourceAddress UDP 包里读到的源地址。**不采用信标里自报的地址**：
     *   自报地址在多网卡机器上经常是错的那块网卡（甚至是被 NAT 掉的地址），
     *   而源地址是内核告诉我们的、一定可达。
     */
    fun upsert(
        peers: List<PeerState>,
        beacon: SyncBeacon,
        sourceAddress: String,
        now: Long,
    ): List<PeerState> {
        val updated = PeerState(
            deviceId = beacon.deviceId,
            name = beacon.name,
            platform = beacon.platform,
            address = sourceAddress,
            streamPort = beacon.streamPort,
            lastSeenAt = now,
            fingerprint = beacon.fingerprint,
            appVersion = beacon.appVersion,
        )
        return buildList(peers.size + 1) {
            add(updated)
            peers.forEach { if (it.deviceId != beacon.deviceId) add(it) }
        }
    }

    /** 忘掉一台设备（用户手动移除，或配对解除时）。 */
    fun remove(peers: List<PeerState>, deviceId: String): List<PeerState> =
        peers.filter { it.deviceId != deviceId }

    /**
     * 丢弃**长时间**没出现过的设备（[PEER_FORGET_MS]，远大于离线超时）。
     *
     * 与「离线」是两件事：离线只是灰掉显示（用户可能只是切了网络，几秒后又回来），
     * 遗忘是把条目删掉。用同一个阈值就会导致「设备闪一下就消失」，列表看起来在抖。
     */
    fun prune(peers: List<PeerState>, now: Long, forgetMs: Long = PEER_FORGET_MS): List<PeerState> =
        peers.filter { now - it.lastSeenAt <= forgetMs }

    /** 当前在线设备数。 */
    fun onlineCount(peers: List<PeerState>, now: Long, timeoutMs: Long = PEER_TIMEOUT_MS): Int =
        peers.count { it.isOnline(now, timeoutMs) }

    /** 当前在线的设备（按最近出现排序，与 [upsert] 维护的顺序一致）。 */
    fun online(peers: List<PeerState>, now: Long, timeoutMs: Long = PEER_TIMEOUT_MS): List<PeerState> =
        peers.filter { it.isOnline(now, timeoutMs) }
}
