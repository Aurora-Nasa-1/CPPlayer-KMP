package cp.player.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「期望字段」契约的回归测试。
 *
 * 钉住两件在诊断页真实刷出过误报的事（诊断页文案形如
 * `期望字段: data, 实际字段: [code, msg]`）：
 *
 * 1. `pl/count` 的未读数在**根层 `msg`**，不是 `data`；
 * 2. `login/status` 有**两种形状** —— NeteaseCloudMusicApi(Node) 包一层 `data`，
 *    而本仓库的 `netease-module-rust` 原样透传（`code`/`account`/`profile` 平铺在根层）。
 *
 * 表本身在 [ApiFieldContract]，判定逻辑由 `MusicApiServiceImpl.validateResponse` 复用。
 */
class ApiFieldContractTest {

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    private fun satisfied(method: String, raw: String) =
        ApiFieldContract.isExpectedFieldSatisfied(method, json(raw))

    // ---------- pl/count ----------

    @Test
    fun `pl_count 未读数在根层 msg - 不再被判缺字段`() {
        assertEquals("msg", ApiFieldContract.expectedFieldOf(MusicApiMethod.MESSAGE_UNREAD_COUNT))
        assertTrue(satisfied(MusicApiMethod.MESSAGE_UNREAD_COUNT, """{"code":200,"msg":5}"""))
    }

    @Test
    fun `pl_count 包一层 data 的 Provider 同样通过`() {
        assertTrue(satisfied(MusicApiMethod.MESSAGE_UNREAD_COUNT, """{"code":200,"data":{"msg":5}}"""))
    }

    @Test
    fun `pl_count 连 msg 都没有才算缺字段`() {
        assertFalse(satisfied(MusicApiMethod.MESSAGE_UNREAD_COUNT, """{"code":200}"""))
    }

    // ---------- login/status ----------

    @Test
    fun `login_status NCM_Node 的 data 包裹形状通过`() {
        val body = """{"data":{"code":200,"account":{"id":1},"profile":{"userId":1}}}"""
        assertEquals("data", ApiFieldContract.expectedFieldOf(MusicApiMethod.AUTH_LOGIN_STATUS))
        assertTrue(satisfied(MusicApiMethod.AUTH_LOGIN_STATUS, body))
    }

    @Test
    fun `login_status 平铺形状通过 - 曾被误报缺少 data`() {
        val body = """{"code":200,"account":{"id":1},"profile":{"userId":1,"nickname":"U"}}"""
        assertTrue(satisfied(MusicApiMethod.AUTH_LOGIN_STATUS, body))
    }

    @Test
    fun `login_status 未登录（字段为 JSON null）也是合法响应`() {
        assertTrue(satisfied(MusicApiMethod.AUTH_LOGIN_STATUS, """{"code":200,"account":null,"profile":null}"""))
    }

    @Test
    fun `login_status 只剩 code 才算缺字段`() {
        assertFalse(satisfied(MusicApiMethod.AUTH_LOGIN_STATUS, """{"code":200}"""))
    }

    // ---------- 表的自洽 ----------

    @Test
    fun `等价字段的键必须也在主表里`() {
        for ((method, aliases) in ApiFieldContract.ALIASES) {
            assertTrue(
                ApiFieldContract.EXPECTED_FIELDS.containsKey(method),
                "ALIASES 里的 $method 不在 EXPECTED_FIELDS 中 ⇒ 主字段那一关走不到，别名永远不生效",
            )
            assertTrue(aliases.isNotEmpty(), "$method 的别名表不该为空")
        }
    }

    @Test
    fun `通用回退表里不放具体业务字段`() {
        // `profile` / `account` 之流只对 login 类端点成立，放进全局表会让别的端点蒙混过关。
        for (field in ApiFieldContract.FALLBACK_FIELDS) {
            assertTrue(
                field in setOf("data", "result", "playlist", "songs", "albums", "artists", "comments", "msgs", "hotData", "list"),
                "$field 像是具体业务字段，应写进 ALIASES 而不是 FALLBACK_FIELDS",
            )
        }
    }

    @Test
    fun `未声明期望字段的方法一律算通过`() {
        // 调用方必须先看 expectedFieldOf —— 没有声明就没有「缺字段」这回事。
        assertNull(ApiFieldContract.expectedFieldOf("unknown/endpoint"))
        assertTrue(ApiFieldContract.isExpectedFieldSatisfied("unknown/endpoint", json("""{"code":200}""")))
    }
}
