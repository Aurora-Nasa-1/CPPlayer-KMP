package cp.player.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `login/status` 解析的契约测试。
 *
 * 钉住两件最容易回退的事：
 * 1. NCM 的响应把 `code` 包在 `data` 里（顶层没有），业务码解析必须能下沉一层；
 * 2. **未登录时 code 也是 200** ⇒ 「是否已登录」只能看 uid，不能看 code。
 */
class LoginStatusTest {

    private fun json(raw: String): JsonElement = Json.parseToJsonElement(raw)

    // ---------- NCM 实际形状 ----------

    @Test
    fun `NCM 形状 - 已登录从 account 取 id`() {
        val body = json(
            """{"data":{"code":200,"account":{"id":123456},"profile":{"userId":123456,"nickname":"User"}}}"""
        )
        assertEquals(123456L, extractUidFromLoginStatus(body))
        assertTrue(isLoggedInStatus(body))
        assertEquals(200, resolveLoginStatusCode(body as kotlinx.serialization.json.JsonObject))
    }

    @Test
    fun `NCM 形状 - 缺 account 时回退 profile_userId`() {
        // NCM 的 profile 里是 userId 而不是 id，漏了这个回退就会取不到 uid
        val body = json("""{"data":{"code":200,"profile":{"userId":456,"nickname":"User"}}}""")
        assertEquals(456L, extractUidFromLoginStatus(body))
        assertTrue(isLoggedInStatus(body))
    }

    @Test
    fun `account_id 为 JSON null 时回退 profile_userId`() {
        val body = json("""{"data":{"code":200,"account":{"id":null},"profile":{"userId":321}}}""")
        assertEquals(321L, extractUidFromLoginStatus(body))
    }

    @Test
    fun `NCM 形状 - 未登录 code 仍是 200 但没有 uid`() {
        val body = json("""{"data":{"code":200,"account":null,"profile":null}}""")
        assertNull(extractUidFromLoginStatus(body), "未登录不应解析出 uid")
        assertFalse(isLoggedInStatus(body), "code=200 不代表已登录，必须看 uid")
        // 但业务码侧仍是成功：这次查询本身没失败
        assertEquals(200, resolveLoginStatusCode(body as kotlinx.serialization.json.JsonObject))
    }

    // ---------- 兼容形状 ----------

    @Test
    fun `平铺形状 - 字段直接在根层`() {
        val body = json("""{"code":200,"account":{"id":789},"profile":{"userId":789}}""")
        assertEquals(789L, extractUidFromLoginStatus(body))
        assertTrue(isLoggedInStatus(body))
        assertEquals(200, resolveLoginStatusCode(body as kotlinx.serialization.json.JsonObject))
    }

    @Test
    fun `非对象响应一律返回 null`() {
        assertNull(extractUidFromLoginStatus(null))
        assertNull(extractUidFromLoginStatus(json("""[]""")))
        assertNull(extractUidFromLoginStatus(json(""""abc"""")))
        assertFalse(isLoggedInStatus(json("""{"data":[]}""")))
    }

    // ---------- 业务码解析 ----------

    @Test
    fun `顶层 code 优先于 data_code`() {
        val body = json("""{"code":0,"data":{"code":200}}""")
        assertEquals(0, resolveLoginStatusCode(body as kotlinx.serialization.json.JsonObject))
    }

    @Test
    fun `顶层无 code 时下沉到 data_code`() {
        val body = json("""{"data":{"code":301,"account":null}}""")
        assertEquals(301, resolveLoginStatusCode(body as kotlinx.serialization.json.JsonObject))
    }

    @Test
    fun `两处都没有 code 时返回 null`() {
        val body = json("""{"data":{"account":null,"profile":null}}""")
        assertNull(resolveLoginStatusCode(body as kotlinx.serialization.json.JsonObject))
    }

    // ---------- data 解包 ----------

    @Test
    fun `unwrap 有 data 用 data 无 data 用根对象`() {
        assertEquals(
            "1",
            (unwrapLoginStatusData(json("""{"data":{"code":1}}"""))?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content,
        )
        assertEquals(
            "1",
            (unwrapLoginStatusData(json("""{"code":1}"""))?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content,
        )
        assertNull(unwrapLoginStatusData(json("""[]""")))
    }
}
