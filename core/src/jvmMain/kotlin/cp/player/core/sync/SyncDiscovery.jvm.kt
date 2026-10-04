package cp.player.core.sync

import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress

/**
 * 设备身份的平台默认值（Android / Desktop 共用）。
 *
 * `deviceId` 用 UUID：它只在局域网内做去重键，不需要密码学强度，
 * 但必须**足够长且不重复** —— 用「主机名 + 时间戳」在容器/克隆环境里会撞车。
 */
actual fun newDeviceId(): String = runCatching {
    java.util.UUID.randomUUID().toString()
}.getOrDefault("device-${currentTimeMillis()}")

actual fun defaultDeviceName(): String = runCatching {
    InetAddress.getLocalHost().hostName
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "CPPlayer 设备"

/**
 * 平台标识。
 *
 * 用**反射探测 `android.os.Build`** 而不是 expect/actual：`jvmMain` 是
 * Android 与 Desktop 的公共源集，这里写不了 `android.*` 的符号，但 `os.name`
 * 在 Android 上返回的是 `Linux`（不是 `Android`）—— 直接按 `os.name` 判会把
 * 手机标成 linux，设备列表里就分不出手机和电脑了。
 */
actual fun currentPlatformLabel(): String = runCatching {
    Class.forName("android.os.Build")
    "android"
}.getOrElse {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        os.contains("linux") -> "linux"
        else -> "unknown"
    }
}

actual fun createSyncDiscovery(
    identity: DeviceIdentity,
    resolveStreamPort: () -> Int,
    resolveFingerprint: () -> String?,
): SyncDiscovery = MulticastSyncDiscovery(identity, resolveStreamPort, resolveFingerprint)

/**
 * 基于 UDP 组播 + 广播兜底的信标发现。
 *
 * ### 为什么是组播而不是 mDNS
 * mDNS 在 JVM 侧要引 `jmdns`、在 Android 侧是 `NsdManager`，两套 API 不对称，
 * 于是「同一份逻辑」要写两遍、测两遍。而组播用 `java.net.MulticastSocket` 就够了，
 * 两端共用一份实现与同一组地址/端口。
 *
 * ### 为什么还要叠加一条广播通道
 * 组播的送达依赖路径上**每一台**设备正确处理 IGMP：家用路由器的 snooping
 * 实现不把组播从有线口桥到 Wi-Fi 口（或反之）是「同一网段却互相看不见」的
 * 高频原因，而用户无法从设备列表里区分「对方没在发」和「包被路由器吃了」。
 * 广播不需要任何组表项，穿这类路由器的成功率高得多。因此每个周期同时向
 * 组播组与受限广播地址各发一份；绑定端口的 socket 天然收得到广播，接收侧
 * 不需要任何额外处理。多出来的流量是每个周期两个 ~200 字节的包，可忽略。
 *
 * ### 几个必须这么写的点
 * 1. **`MulticastSocket(null)` + 先 `reuseAddress` 再 `bind`**：同一台机器上跑两个
 *    实例（开发时最常见的场景）必须能绑同一个端口，否则第二个起不来。
 *    注意 `MulticastSocket(port)` 构造器会**立即绑定**，来不及设 reuseAddress。
 * 2. **逐网卡 join 与 send**：只 join 默认网卡的话，多网卡机器（WiFi + 有线 + 虚拟网卡）
 *    上会「明明同一局域网却搜不到」。发送同理 —— 不指定出口网卡，包可能从错误的网卡出去。
 * 3. **TTL = 1**：信标只在本网段有效，不该被路由转发出去。
 * 4. **接收循环靠 `soTimeout` 唤醒**，不是靠阻塞 `receive`：没有超时的阻塞收包
 *    会让 `stop()` 无法及时生效（取消协程也解不开阻塞的 socket 读）。
 * 5. **单个网卡的操作失败不终止整轮**：某块虚拟网卡 join 失败（VPN、Docker）
 *    是常态，让它把整个发现打断就等于「因为一块用不上的网卡，功能整个不可用」。
 */
internal class MulticastSyncDiscovery(
    private val identity: DeviceIdentity,
    private val resolveStreamPort: () -> Int,
    private val resolveFingerprint: () -> String?,
) : SyncDiscovery {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _peers = MutableStateFlow<List<PeerState>>(emptyList())
    override val peers: StateFlow<List<PeerState>> = _peers.asStateFlow()

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _stats = MutableStateFlow(DiscoveryStats())
    override val stats: StateFlow<DiscoveryStats> = _stats.asStateFlow()

    private var job: Job? = null

    override fun start() {
        if (job != null) return
        job = scope.launch { runLoop() }
    }

    override fun stop() {
        job?.cancel()
        job = null
        // 协程取消后 socket 由 finally 关闭；这里只负责把状态摆正，
        // 不重复关 socket（重复 close 无害，但会让「谁负责释放」变得含糊）。
        _running.value = false
    }

    private suspend fun runLoop() {
        var socket: MulticastSocket? = null
        val interfaces = multicastInterfaces()
        try {
            val group = InetAddress.getByName(SYNC_BEACON_GROUP)
            val broadcast = InetAddress.getByName(SYNC_BROADCAST_ADDRESS)
            val groupAddress: SocketAddress = InetSocketAddress(group, SYNC_BEACON_PORT)

            socket = MulticastSocket(null as SocketAddress?).apply {
                // ⚠️ 顺序不能反：reuseAddress 必须在 bind 之前。
                reuseAddress = true
                bind(InetSocketAddress(SYNC_BEACON_PORT))
                timeToLive = 1
                // 800ms：纯为了让取消/停止能及时生效，不是业务需要。
                soTimeout = 800
            }
            val bound = socket

            var joined = 0
            interfaces.forEach { nif ->
                val ok = runCatching { bound.joinGroup(groupAddress, nif) }.isSuccess
                if (ok) joined++
            }
            if (joined == 0 && interfaces.isNotEmpty()) {
                _lastError.value = "没有可用网卡能加入组播组，设备发现不可用"
                return
            }

            _lastError.value = null
            _running.value = true

            val buffer = ByteArray(MAX_BEACON_BYTES)
            var lastSendAt = 0L
            while (currentCoroutineContext().isActive) {
                val now = currentTimeMillis()
                if (now - lastSendAt >= BEACON_INTERVAL_MS) {
                    sendBeacon(bound, group, broadcast, interfaces)
                    lastSendAt = now
                }

                val packet = DatagramPacket(buffer, buffer.size)
                // SocketTimeoutException 是正常节奏的一部分（见 soTimeout），
                // 一律吞掉；socket 被 stop() 关掉时的异常同样不该冒出来。
                val received = runCatching { bound.receive(packet) }.isSuccess
                if (received) handlePacket(packet)

                // 定期遗忘长期未见的设备：不做的话设备表会随「历史上出现过的机器数」增长。
                _peers.value = Peers.prune(_peers.value, currentTimeMillis())
            }
        } catch (t: Throwable) {
            // 端口被占（另一实例没设 reuseAddress）是最常见的一种，如实报出来。
            _lastError.value = t.message ?: t.javaClass.simpleName
        } finally {
            _running.value = false
            runCatching { socket?.close() }
        }
    }

    private fun sendBeacon(
        socket: MulticastSocket,
        group: InetAddress,
        broadcast: InetAddress,
        interfaces: List<NetworkInterface>,
    ) {
        val beacon = SyncBeacon(
            deviceId = identity.deviceId,
            name = identity.name,
            platform = identity.platform,
            streamPort = runCatching { resolveStreamPort() }.getOrDefault(0),
            appVersion = identity.appVersion,
            fingerprint = runCatching { resolveFingerprint() }.getOrNull(),
            sentAt = currentTimeMillis(),
        )
        val bytes = Beacons.encode(beacon).toByteArray(Charsets.UTF_8)
        var sent = false

        fun deliver(target: InetAddress) {
            runCatching {
                socket.send(DatagramPacket(bytes, bytes.size, target, SYNC_BEACON_PORT))
                sent = true
            }
        }

        if (interfaces.isEmpty()) {
            deliver(group)
            deliver(broadcast)
        } else {
            interfaces.forEach { nif ->
                runCatching { socket.networkInterface = nif }
                deliver(group)
                deliver(broadcast)
            }
        }
        // 按周期计一次而不是按包计 —— 用户关心的是「发了几轮」，不是网卡数 × 2。
        if (sent) _stats.value = _stats.value.copy(sent = _stats.value.sent + 1)
    }

    private fun handlePacket(packet: DatagramPacket) {
        _stats.value = _stats.value.copy(
            received = _stats.value.received + 1,
            lastRecvAt = currentTimeMillis(),
        )
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val beacon = Beacons.decode(text)
        if (beacon == null) {
            // 收到了但不是本应用 / 版本不符 / 纯垃圾 —— 这正是「搜不到人但不是防火墙」的信号。
            _stats.value = _stats.value.copy(invalid = _stats.value.invalid + 1)
            return
        }
        // 过滤自己：组播默认会把本机发的包回环回来（这一点对开发时「一台机器跑两个实例」
        // 的验证是好事，所以不关掉 loopback，改为按 deviceId 过滤）。
        if (beacon.deviceId == identity.deviceId) return

        val address = packet.address?.hostAddress ?: return
        _peers.value = Peers.upsert(_peers.value, beacon, address, currentTimeMillis())
    }

    private fun multicastInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { nif ->
            runCatching { nif.isUp && !nif.isLoopback && nif.supportsMulticast() }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())

    private companion object {
        /** 信标体积上限。留足余量：字段以后可能增加，而超长包会被截断成畸形数据。 */
        const val MAX_BEACON_BYTES = 2048
    }
}
