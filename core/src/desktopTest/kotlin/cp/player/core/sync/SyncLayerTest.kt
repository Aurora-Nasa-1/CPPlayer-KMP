package cp.player.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 设备发现层的**协议契约**测试。
 *
 * 只测纯逻辑（信标编解码、设备表维护）—— 组播收发是网络 IO，单测里测它
 * 只能测到「代码能跑」，测不到「两台机器真能互相看见」，那部分只能上双端实测。
 *
 * 这些口径钉住三件最容易回归的事：
 * 1. **信标是广播的** ⇒ 任何脏数据都要能被安全丢弃，绝不能让一个畸形包打断发现循环；
 * 2. **设备表按 deviceId 去重** ⇒ 同一台机器改名后仍是同一条，而不是变成两条；
 * 3. **在线与否是时间的函数** ⇒ 超时边界（恰好 = timeout 算在线）必须钉住。
 */
class SyncLayerTest {

    private fun beacon(
        deviceId: String = "dev-1",
        name: String = "桌面机",
        platform: String = "windows",
        streamPort: Int = 8080,
        app: String = SYNC_APP_NAME,
        protocol: Int = SYNC_PROTOCOL_VERSION,
        fingerprint: String? = "ab12cd34",
        appVersion: String = "1.0.0",
    ) = SyncBeacon(
        app = app,
        protocol = protocol,
        deviceId = deviceId,
        name = name,
        platform = platform,
        streamPort = streamPort,
        appVersion = appVersion,
        fingerprint = fingerprint,
        sentAt = 1_000_000L,
    )

    // ============ 信标编解码 ============

    @Test
    fun `信标编解码能完整还原`() {
        val original = beacon()
        val decoded = Beacons.decode(Beacons.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `编解码对未知字段保持向前兼容`() {
        val withExtra = """{"app":"CPPlayer","protocol":1,"deviceId":"d1","name":"n",""" +
            """"platform":"windows","streamPort":8080,"sentAt":1,"futureField":"x"}"""
        val decoded = Beacons.decode(withExtra)
        assertEquals("d1", decoded?.deviceId)
    }

    @Test
    fun `垃圾数据一律安全丢弃而不是抛异常`() {
        // 这条通道上什么都会出现：别的程序占端口、半截 UDP 包、故意构造的垃圾。
        listOf(
            "",
            "   ",
            "not json at all",
            "{\"truncated\":",
            "[]",
            "null",
            "12345",
        ).forEach { raw ->
            assertNull(Beacons.decode(raw), "以下输入应被丢弃：$raw")
        }
    }

    @Test
    fun `非本应用的信标丢弃`() {
        assertNull(Beacons.decode(Beacons.encode(beacon(app = "其他软件"))))
    }

    @Test
    fun `协议版本不符的信标丢弃`() {
        // 旧版本 / 新版本之间没有兼容路径，宁可「搜不到」也不要「连上了但行为诡异」。
        assertNull(Beacons.decode(Beacons.encode(beacon(protocol = SYNC_PROTOCOL_VERSION + 1))))
        assertNull(Beacons.decode(Beacons.encode(beacon(protocol = 0))))
    }

    @Test
    fun `deviceId 为空的信标丢弃`() {
        assertNull(Beacons.decode(Beacons.encode(beacon(deviceId = ""))))
    }

    // ============ 设备表 ============

    @Test
    fun `新设备插入并排在最前`() {
        val list = Peers.upsert(emptyList(), beacon(deviceId = "a"), "192.168.1.10", now = 100)
        assertEquals(listOf("a"), list.map { it.deviceId })
        assertEquals("192.168.1.10", list[0].address)
        assertEquals(100L, list[0].lastSeenAt)
    }

    @Test
    fun `同一台机器改名后仍是同一条而不是两条`() {
        var list = Peers.upsert(emptyList(), beacon(deviceId = "a", name = "旧名"), "192.168.1.10", 100)
        list = Peers.upsert(list, beacon(deviceId = "a", name = "新名"), "192.168.1.10", 200)
        assertEquals(1, list.size, "按 deviceId 去重后不该出现两条")
        assertEquals("新名", list[0].name)
        assertEquals(200L, list[0].lastSeenAt)
    }

    @Test
    fun `更新已存在设备会把它移到最前且不挤掉其他设备`() {
        var list = Peers.upsert(emptyList(), beacon(deviceId = "a", name = "A"), "1.1.1.1", 100)
        list = Peers.upsert(list, beacon(deviceId = "b", name = "B"), "1.1.1.2", 200)
        list = Peers.upsert(list, beacon(deviceId = "a", name = "A"), "1.1.1.1", 300)
        assertEquals(listOf("a", "b"), list.map { it.deviceId })
    }

    @Test
    fun `多台设备按最近出现排序`() {
        var list = Peers.upsert(emptyList(), beacon(deviceId = "a"), "1.1.1.1", 100)
        list = Peers.upsert(list, beacon(deviceId = "b"), "1.1.1.2", 200)
        list = Peers.upsert(list, beacon(deviceId = "c"), "1.1.1.3", 300)
        assertEquals(listOf("c", "b", "a"), list.map { it.deviceId })
    }

    @Test
    fun `忘记一台设备`() {
        var list = Peers.upsert(emptyList(), beacon(deviceId = "a"), "1.1.1.1", 100)
        list = Peers.upsert(list, beacon(deviceId = "b"), "1.1.1.2", 200)
        list = Peers.remove(list, "a")
        assertEquals(listOf("b"), list.map { it.deviceId })
    }

    @Test
    fun `遗忘只清掉长期未见的设备`() {
        var list = Peers.upsert(emptyList(), beacon(deviceId = "a"), "1.1.1.1", 100)
        list = Peers.upsert(list, beacon(deviceId = "b"), "1.1.1.2", 200)
        // now = 200 + PEER_FORGET_MS：b 仍新鲜，a 已超期
        list = Peers.prune(list, now = 200 + PEER_FORGET_MS)
        assertEquals(listOf("b"), list.map { it.deviceId }, "a 已超期应被忘掉")
    }

    @Test
    fun `在线判定的超时边界`() {
        val list = Peers.upsert(emptyList(), beacon(deviceId = "a"), "1.1.1.1", now = 1_000)
        // 恰好等于超时阈值 ⇒ 在线（<=）；再多 1ms ⇒ 离线。
        assertEquals(true, list[0].isOnline(now = 1_000 + PEER_TIMEOUT_MS))
        assertEquals(false, list[0].isOnline(now = 1_000 + PEER_TIMEOUT_MS + 1))
    }

    @Test
    fun `在线计数与在线列表一致`() {
        var list = Peers.upsert(emptyList(), beacon(deviceId = "a"), "1.1.1.1", 1_000)
        list = Peers.upsert(list, beacon(deviceId = "b"), "1.1.1.2", 12_000)
        val now = 13_001L
        // a：13001 - 1000 = 12001 > 超时 ⇒ 离线；b：13001 - 12000 = 1001 ⇒ 在线
        assertEquals(listOf("b"), Peers.online(list, now).map { it.deviceId })
        assertEquals(1, Peers.onlineCount(list, now))
    }

    @Test
    fun `断言设备表条目携带配对指纹`() {
        // fingerprint 不参与本层逻辑，但它要让配对层能读 —— 漏传会导致
        // 「明明配对过，设备列表却不显示已配对状态」。
        val list = Peers.upsert(emptyList(), beacon(deviceId = "a", fingerprint = "deadbeef"), "1.1.1.1", 100)
        assertEquals("deadbeef", list[0].fingerprint)
        val none = Peers.upsert(emptyList(), beacon(deviceId = "b", fingerprint = null), "1.1.1.2", 100)
        assertNull(none[0].fingerprint)
    }

    @Test
    fun `空设备表的各种操作都不炸`() {
        assertTrue(Peers.prune(emptyList(), now = 0).isEmpty())
        assertEquals(0, Peers.onlineCount(emptyList(), now = 0))
        assertTrue(Peers.online(emptyList(), now = 0).isEmpty())
        assertTrue(Peers.remove(emptyList(), "x").isEmpty())
        assertNotEquals(0, Peers.upsert(emptyList(), beacon(), "1.1.1.1", 100).size)
    }

    // ============ 无缝转移（HandoffGuard + JSON 往返） ============

    private fun handoffReq(
        mediaId: String = "ncm://song/1",
        fromDeviceId: String = "dev-1234",
        positionMs: Long = 65_000,
        trackName: String = "歌名",
        artist: String = "歌手",
    ) = HandoffRequest(
        fromDeviceId = fromDeviceId,
        fromName = "我的手机",
        mediaId = mediaId,
        trackName = trackName,
        artist = artist,
        positionMs = positionMs,
        wasPlaying = true,
        sentAt = 1_000_000L,
    )

    @Test
    fun `转移请求合法时原样通过且进度被保留`() {
        val s = HandoffGuard.sanitized(handoffReq())
        assertNotNull(s)
        assertEquals(65_000L, s.positionMs)
        assertEquals("ncm://song/1", s.mediaId)
    }

    @Test
    fun `转移请求媒体id为空或超长时拒绝`() {
        assertNull(HandoffGuard.sanitized(handoffReq(mediaId = "")))
        assertNull(HandoffGuard.sanitized(handoffReq(mediaId = " ".repeat(8))))
        assertNull(HandoffGuard.sanitized(handoffReq(mediaId = "x".repeat(300))))
        assertNull(HandoffGuard.sanitized(handoffReq(fromDeviceId = "")))
    }

    @Test
    fun `转移请求进度越界被clamp而不是拒绝`() {
        // 用户把进度条拖到头是正常操作，负数/超大值 clamp 回区间即可，
        // 拒绝会让合法的「从头听」转移莫名失败。
        assertEquals(0L, HandoffGuard.sanitized(handoffReq(positionMs = -5))!!.positionMs)
        assertEquals(
            2L * 3_600_000,
            HandoffGuard.sanitized(handoffReq(positionMs = Long.MAX_VALUE))!!.positionMs,
        )
    }

    @Test
    fun `转移请求JSON往返保真`() {
        val json = Json.encodeToString(HandoffRequest.serializer(), handoffReq())
        val back = Json.decodeFromString(HandoffRequest.serializer(), json)
        assertEquals(handoffReq(), back)
        // 应答的默认值也要能编码（对端旧版本不认识新字段时 ignoreUnknownKeys 兜底）
        val ok = Json.encodeToString(HandoffResult.serializer(), HandoffResult(accepted = true))
        assertTrue(Json.decodeFromString(HandoffResult.serializer(), ok).accepted)
    }

    @Test
    fun `转移请求垃圾JSON与多余字段安全处理`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(HandoffRequest.serializer(), "not json at all")
        }
        // ignoreUnknownKeys：新协议字段在旧对端上不炸
        val lenient = Json { ignoreUnknownKeys = true }
        val back = lenient.decodeFromString(
            HandoffRequest.serializer(),
            """{"fromDeviceId":"d1","mediaId":"ncm://song/2","futureField":123}""",
        )
        assertEquals("ncm://song/2", back.mediaId)
    }
}
