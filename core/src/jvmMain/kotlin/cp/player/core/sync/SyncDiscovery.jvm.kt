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
 * 基于 UDP 组播的信标发现。
 *
 * ### 为什么是组播而不是 mDNS
 * mDNS 在 JVM 侧要引 `jmdns`、在 Android 侧是 `NsdManager`，两套 API 不对称，
 * 于是「同一份逻辑」要写两遍、测两遍。而组播用 `java.net.MulticastSocket` 就够了，
 * 两端共用一份实现与同一组地址/端口。
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
                    sendBeacon(bound, group, interfaces)
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

    private fun sendBeacon(socket: MulticastSocket, group: InetAddress, interfaces: List<NetworkInterface>) {
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

        // 逐网卡发送：不指定出口网卡时，多网卡机器可能从错误的网卡发出去。
        // 一块网卡失败不影响其余（虚拟网卡/VPN 失败是常态）。
        if (interfaces.isEmpty()) {
            runCatching { socket.send(DatagramPacket(bytes, bytes.size, group, SYNC_BEACON_PORT)) }
            return
        }
        interfaces.forEach { nif ->
            runCatching {
                socket.networkInterface = nif
                socket.send(DatagramPacket(bytes, bytes.size, group, SYNC_BEACON_PORT))
            }
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val beacon = Beacons.decode(text) ?: return
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
