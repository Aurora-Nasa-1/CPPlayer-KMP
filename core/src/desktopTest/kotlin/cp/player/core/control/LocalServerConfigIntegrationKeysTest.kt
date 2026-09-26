package cp.player.core.control

import cp.player.core.util.SettingsStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 集成相关配置键的持久化测试。
 *
 * 重点不是「读写能往返」（那太显然），而是**向后兼容**与**默认值的安全性**：
 *
 * - 老配置文件里没有这三个键 ⇒ 必须回退默认值，不能因为「读不出来」而放开数据面；
 * - 手工编辑配置写成 `TRUE` / `yes` 这类非严格布尔串 ⇒ 同样回退默认值（`toBooleanStrictOrNull`），
 *   而不是被解析成 true。
 *
 * 这两条合起来保证：**任何解析失败都不会扩大攻击面**。
 */
class LocalServerConfigIntegrationKeysTest {

    private class FakeSettings : SettingsStorage {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() = map.clear()
    }

    @Test
    fun `三个新键读写往返`() {
        val settings = FakeSettings()
        val config = LocalServerConfig(
            exposeStream = false,
            exposeDataApi = true,
            allowRemoteControl = true,
        )

        LocalServerConfigStore.write(settings, config)
        val read = LocalServerConfigStore.read(settings)

        assertFalse(read.exposeStream)
        assertTrue(read.exposeDataApi)
        assertTrue(read.allowRemoteControl)
    }

    @Test
    fun `老配置文件没有新键时回退到安全默认值`() {
        val settings = FakeSettings()
        // 模拟升级前写下的配置：只有旧键
        settings.putString(LocalServerConfig.KEY_ENABLED, "true")
        settings.putString(LocalServerConfig.KEY_OUTPUT_MODE, OutputMode.SPEAKER.name)
        settings.putString(LocalServerConfig.KEY_BIND, LocalServerConfig.BIND_LOOPBACK)
        settings.putString(LocalServerConfig.KEY_STREAM_PORT, "8080")
        settings.putString(LocalServerConfig.KEY_TOKEN, "legacy-token")
        settings.putString(LocalServerConfig.KEY_PUSH_ENABLED, "true")
        settings.putString(LocalServerConfig.KEY_RECEIVER_URL, "http://127.0.0.1:8420")

        val read = LocalServerConfigStore.read(settings)

        // 旧字段照常读出，行为不变
        assertTrue(read.enabled)
        assertEquals("legacy-token", read.accessToken)
        // 新字段回退默认：数据面与远程播控保持关闭
        assertTrue(read.exposeStream, "媒体面默认开，保持既有接收端行为")
        assertFalse(read.exposeDataApi, "读不出新键时必须保持数据面关闭")
        assertFalse(read.allowRemoteControl, "读不出新键时必须保持远程播控关闭")
    }

    @Test
    fun `非严格布尔串不会被解析成 true`() {
        val settings = FakeSettings()
        settings.putString(LocalServerConfig.KEY_EXPOSE_DATA_API, "TRUE")
        settings.putString(LocalServerConfig.KEY_ALLOW_REMOTE_CONTROL, "yes")

        val read = LocalServerConfigStore.read(settings)

        assertFalse(read.exposeDataApi, "`TRUE` 不是严格布尔串，应回退默认（关闭）")
        assertFalse(read.allowRemoteControl, "`yes` 不是严格布尔串，应回退默认（关闭）")
    }

    @Test
    fun `绑定期字段不含路由级开关`() {
        // applyOutputConfig 靠字段比较决定是否重建服务；
        // 这里钉住「路由级开关不是绑定期字段」的意图：
        // 改动它们不应让 LocalServerConfig 在端口相关字段上产生差异。
        val a = LocalServerConfig(enabled = true, exposeDataApi = false, exposeStream = true)
        val b = a.copy(exposeDataApi = true, exposeStream = false)

        assertEquals(a.enabled, b.enabled)
        assertEquals(a.bindAddress, b.bindAddress)
        assertEquals(a.streamPort, b.streamPort)
        assertEquals(a.accessToken, b.accessToken)
    }

    @Test
    fun `boundToLoopback 判定`() {
        assertTrue(LocalServerConfig(bindAddress = LocalServerConfig.BIND_LOOPBACK).boundToLoopback)
        assertFalse(LocalServerConfig(bindAddress = LocalServerConfig.BIND_ALL).boundToLoopback)
    }

    @Test
    fun `baseUrl 不含路径且 streamUrl 由它拼出`() {
        // 描述符里的 baseUrl 字段必须能直接拼出数据面端点。集成方不该为了拿到基地址
        // 去做字符串截断 —— 那是「文档说 baseUrl，实现却给 /stream 地址」这类漂移的温床。
        val cfg = LocalServerConfig(bindAddress = LocalServerConfig.BIND_LOOPBACK, streamPort = 8080)

        assertEquals("http://127.0.0.1:8080", cfg.baseUrl())
        assertEquals("${cfg.baseUrl()}/stream", cfg.streamUrl())
        assertFalse(
            cfg.baseUrl().endsWith("/stream"),
            "baseUrl 不能带路径，否则拼 /api/v1 会得到 /stream/api/v1",
        )
        // 绑定 0.0.0.0 时要换成可达地址再下发（0.0.0.0 集成方连不上）
        assertEquals("http://192.168.1.5:8080", cfg.baseUrl("192.168.1.5"))
    }
}
