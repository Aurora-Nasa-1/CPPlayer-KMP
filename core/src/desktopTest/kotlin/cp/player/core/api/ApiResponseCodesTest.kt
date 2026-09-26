package cp.player.core.api

import cp.player.core.BackendResult
import cp.player.core.music.MusicSourceFromApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 上游「成功码」判定的契约测试。
 *
 * 这张码表原本在**两处各写了一份**（`MusicApiServiceImpl.callApi` 内联 + `MusicSourceFromApi.isSuccess`），
 * 两处必须同时改才一致，漏一处就出现「健康监控认为成功、业务层认为失败」的错位且无编译期提示。
 * 现在统一到 [ApiResponseCodes]，本测试同时钉住**码表内容**与**业务层确实走同一张表**。
 */
class ApiResponseCodesTest {

    @Test
    fun `成功码表与既有行为一致`() {
        // 这几个码是既有行为的事实来源（网易云风格 200 / 部分 Provider 用 0 / 创建类 201 / 重定向 301）
        assertEquals(setOf(0, 200, 201, 301), ApiResponseCodes.SUCCESS)
        ApiResponseCodes.SUCCESS.forEach {
            assertTrue(ApiResponseCodes.isSuccess(it), "code=$it 应为成功")
        }
    }

    @Test
    fun `拿不到 code 或非成功码一律不算成功`() {
        assertFalse(ApiResponseCodes.isSuccess(null), "响应体里没有 code 字段时无法判断，应算失败")

        listOf(-1, 302, 400, 401, 403, 404, 500, 502).forEach {
            assertFalse(ApiResponseCodes.isSuccess(it), "code=$it 不应算成功")
        }
    }

    @Test
    fun `扫码中间态单独成表且不属于成功码`() {
        assertEquals(setOf(801, 802, 803), ApiResponseCodes.QR_PENDING)
        ApiResponseCodes.QR_PENDING.forEach {
            assertFalse(
                ApiResponseCodes.isSuccess(it),
                "扫码中间态 code=$it 不是成功，不能混进通用成功码",
            )
        }
    }

    @Test
    fun `业务层解析确实与码表共用同一份判定`() {
        assertTrue(MusicSourceFromApi.parseSearchSongs(searchJson(200)) is BackendResult.Success)
        assertTrue(MusicSourceFromApi.parseSearchSongs(searchJson(0)) is BackendResult.Success)
        // 301 曾经是「只在一处写」的那个码，专门钉一下
        assertTrue(MusicSourceFromApi.parseSearchSongs(searchJson(301)) is BackendResult.Success)
    }

    @Test
    fun `不支持码与故障码被区分开`() {
        // -1 / 501 / 404 是「该音源不支持此功能」，UI 应提示功能缺失而非报错
        assertTrue(MusicSourceFromApi.parseSearchSongs(searchJson(-1)) is BackendResult.Unsupported)

        // 其它非成功码是故障
        assertTrue(MusicSourceFromApi.parseSearchSongs(searchJson(500)) is BackendResult.Error)

        // 完全没有 code / status 字段：无法判断，当失败处理（与既有行为一致）
        val noCode = Json.parseToJsonElement("""{"result":{"songs":[]}}""")
        assertTrue(MusicSourceFromApi.parseSearchSongs(noCode) is BackendResult.Error)
    }

    @Test
    fun `status 字段可作为 code 的回退`() {
        val statusOnly = Json.parseToJsonElement("""{"status":200,"result":{"songs":[]}}""")
        assertTrue(MusicSourceFromApi.parseSearchSongs(statusOnly) is BackendResult.Success)
    }

    private fun searchJson(code: Int): JsonElement =
        Json.parseToJsonElement("""{"code":$code,"result":{"songs":[]}}""")
}
