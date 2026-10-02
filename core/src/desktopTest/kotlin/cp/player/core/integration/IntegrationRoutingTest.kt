package cp.player.core.integration

import cp.player.core.control.LocalServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 数据面**闸门规则**的单元测试。
 *
 * 这些规则决定「谁能读到音源数据」，所以每一条都要钉住 ——
 * 尤其是「面没开时即使令牌正确也拒绝」和「非回环不允许无令牌」这两条，
 * 它们是默认关闭策略真正生效的前提。
 *
 * 全部是纯函数，不需要起 HTTP 服务。
 */
class IntegrationRoutingTest {

    private fun config(
        bindAddress: String = LocalServerConfig.BIND_LOOPBACK,
        accessToken: String = "",
        exposeDataApi: Boolean = true,
        exposeStream: Boolean = true,
        allowRemoteControl: Boolean = false,
    ) = LocalServerConfig(
        bindAddress = bindAddress,
        accessToken = accessToken,
        exposeDataApi = exposeDataApi,
        exposeStream = exposeStream,
        allowRemoteControl = allowRemoteControl,
    )

    // ============ 判定顺序：面 > 令牌 ============

    @Test
    fun `数据面未开放时即使令牌正确也返回 FACE_DISABLED`() {
        val gate = decideDataApiGate(
            config = config(exposeDataApi = false, accessToken = "secret"),
            bearerToken = "secret",
            queryToken = "secret",
        )
        assertEquals(
            IntegrationGate.FACE_DISABLED,
            gate,
            "面没开时连『令牌对不对』都不该泄漏，必须先判面",
        )
    }

    @Test
    fun `默认配置下数据面就是关闭的`() {
        // 默认值本身就是安全边界的一部分，值得单独钉住
        val default = LocalServerConfig()
        assertEquals(false, default.exposeDataApi, "exposeDataApi 默认必须为 false")
        assertEquals(false, default.allowRemoteControl, "allowRemoteControl 默认必须为 false")
        assertEquals(true, default.exposeStream, "exposeStream 默认保持 true，以兼容既有接收端")
    }

    // ============ 无令牌：回环放行、非回环拒绝 ============

    @Test
    fun `回环且未配置令牌时放行`() {
        val gate = decideDataApiGate(
            config = config(bindAddress = LocalServerConfig.BIND_LOOPBACK, accessToken = ""),
            bearerToken = null,
            queryToken = null,
        )
        assertEquals(IntegrationGate.ALLOW, gate, "本机脚本不该被强制配令牌")
    }

    @Test
    fun `非回环且未配置令牌时拒绝`() {
        val gate = decideDataApiGate(
            config = config(bindAddress = LocalServerConfig.BIND_ALL, accessToken = ""),
            bearerToken = null,
            queryToken = null,
        )
        assertEquals(
            IntegrationGate.UNAUTHORIZED,
            gate,
            "数据面不允许在局域网上裸奔：非回环必须强制令牌，这条不可关闭",
        )
    }

    // ============ 有令牌 ============

    @Test
    fun `配置令牌后 Bearer 与 query 两种携带方式都能通过`() {
        val cfg = config(accessToken = "a1b2c3")

        assertEquals(
            IntegrationGate.ALLOW,
            decideDataApiGate(cfg, bearerToken = "a1b2c3", queryToken = null),
            "Authorization Bearer 应通过",
        )
        assertEquals(
            IntegrationGate.ALLOW,
            decideDataApiGate(cfg, bearerToken = null, queryToken = "a1b2c3"),
            "?token= 兼容路径应通过（浏览器直接拉流用）",
        )
    }

    @Test
    fun `令牌缺失或不匹配时拒绝`() {
        val cfg = config(accessToken = "a1b2c3")

        assertEquals(IntegrationGate.UNAUTHORIZED, decideDataApiGate(cfg, null, null))
        assertEquals(IntegrationGate.UNAUTHORIZED, decideDataApiGate(cfg, "wrong", null))
        assertEquals(IntegrationGate.UNAUTHORIZED, decideDataApiGate(cfg, null, "wrong"))
        assertEquals(IntegrationGate.UNAUTHORIZED, decideDataApiGate(cfg, "a1b2c", null), "前缀不算匹配")
    }

    @Test
    fun `Bearer 存在但错误时不回退到 query 令牌`() {
        val cfg = config(accessToken = "a1b2c3")
        val gate = decideDataApiGate(cfg, bearerToken = "wrong", queryToken = "a1b2c3")
        assertEquals(
            IntegrationGate.UNAUTHORIZED,
            gate,
            "两个凭据冲突时按 fail-closed 处理，不挑一个『看起来对的』放行",
        )
    }

    // ============ Authorization 头解析 ============

    @Test
    fun `Bearer 头解析`() {
        assertEquals("abc", parseBearerToken("Bearer abc"))
        assertEquals("abc", parseBearerToken("bearer abc"), "方案名大小写不敏感")
        assertEquals("abc", parseBearerToken("  Bearer   abc  "), "前后空白应被裁掉")
        assertEquals("a b", parseBearerToken("Bearer a b"), "令牌内部空白保留（由比对判定对错）")

        assertNull(parseBearerToken(null))
        assertNull(parseBearerToken(""))
        assertNull(parseBearerToken("abc"), "没有方案名")
        assertNull(parseBearerToken("Basic dXNlcjpwYXNz"), "非 Bearer 方案")
        assertNull(parseBearerToken("Bearer"), "只有方案名没有令牌")
        assertNull(parseBearerToken("Bearer    "), "方案名后只有空白")
    }

    // ============ capabilities ============

    @Test
    fun `capabilities 只声明真正可用的能力并反映开关`() {
        val dataFace = listOf("meta", "providers", "search", "track", "trackBatch", "playback", "events")

        assertEquals(dataFace + "stream", IntegrationCapabilities.of(config(exposeStream = true)))
        assertEquals(
            dataFace,
            IntegrationCapabilities.of(config(exposeStream = false)),
            "媒体面关掉后不该再声明 stream",
        )
        // 播控是**第二道**开关：数据面开着也可能没有写能力
        assertEquals(
            dataFace + listOf("stream", "playbackControl"),
            IntegrationCapabilities.of(config(exposeStream = true, allowRemoteControl = true)),
            "开放远程播控后应声明 playbackControl",
        )
        assertEquals(
            dataFace + "stream",
            IntegrationCapabilities.of(config(exposeStream = true, allowRemoteControl = false)),
            "没开放远程播控就不该声明 playbackControl —— 能力清单必须反映当前配置",
        )
    }

    // ============ 路由表 ============

    @Test
    fun `路由表与对外文档中的路径一致`() {
        // 这些字符串是**已发布的契约**（docs/dev/INTEGRATION_API.md）。
        // 改它们等于改对外接口，必须同时改文档 —— 这个测试就是那道提醒。
        assertEquals("/api/v1/meta", IntegrationRoutes.META)
        assertEquals("/api/v1/providers", IntegrationRoutes.PROVIDERS)
        assertEquals("/api/v1/search", IntegrationRoutes.SEARCH)
        assertEquals("/api/v1/tracks/{mediaId}", IntegrationRoutes.TRACK)
        assertEquals("/api/v1/tracks/batch", IntegrationRoutes.TRACK_BATCH)
        assertEquals("/api/v1/playback", IntegrationRoutes.PLAYBACK)
        assertEquals("/api/v1/playback/{action}", IntegrationRoutes.PLAYBACK_ACTION)
        assertEquals("/api/v1/events", IntegrationRoutes.EVENTS)
    }

    // ============ 线上契约的序列化形态 ============

    @Test
    fun `meta 序列化后每个字段都出现`() {
        val json = IntegrationJson.encodeToString(
            MetaDto.serializer(),
            MetaDto(
                app = INTEGRATION_APP_NAME,
                apiVersion = INTEGRATION_API_VERSION,
                capabilities = listOf("meta", "providers"),
                provider = ProviderRefDto("netease", "NeteaseCloudMusicApi", "1.2.0"),
                loggedIn = true,
            ),
        )

        // encodeDefaults = true 的意义就在这里：值恰好等于默认值也不能被静默丢掉
        assertTrue(json.contains("\"app\":\"CPPlayer\""), "缺 app: $json")
        assertTrue(json.contains("\"apiVersion\":1"), "缺 apiVersion: $json")
        assertTrue(json.contains("\"capabilities\":[\"meta\",\"providers\"]"), "缺 capabilities: $json")
        assertTrue(json.contains("\"provider\":{"), "缺 provider: $json")
        assertTrue(json.contains("\"loggedIn\":true"), "缺 loggedIn: $json")
    }

    @Test
    fun `没有活跃音源时 provider 为 null 且字段仍然出现`() {
        val json = IntegrationJson.encodeToString(
            MetaDto.serializer(),
            MetaDto(
                app = INTEGRATION_APP_NAME,
                apiVersion = INTEGRATION_API_VERSION,
                capabilities = listOf("meta", "providers"),
                provider = null,
                loggedIn = false,
            ),
        )
        assertTrue(json.contains("\"provider\":null"), "显式 null 便于集成方发现字段: $json")
    }

    @Test
    fun `错误响应形态稳定`() {
        val json = IntegrationJson.encodeToString(
            ApiErrorDto.serializer(),
            ApiErrorDto(ApiErrorBody(ApiErrorCodes.FACE_DISABLED, "数据面未开放")),
        )
        assertTrue(json.contains("\"error\":{"), "错误必须包在 error 里: $json")
        assertTrue(json.contains("\"code\":\"face_disabled\""), "错误码应稳定可分支: $json")
    }
}
