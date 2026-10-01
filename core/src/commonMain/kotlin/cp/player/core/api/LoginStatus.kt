package cp.player.core.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `login/status` 响应的解析（**唯一入口**）。
 *
 * ### 为什么单列一个文件
 * NCM（NeteaseCloudMusicApi）的 `login/status` 与其它端点的**响应形状不同**：
 * 顶层没有 `code`，业务码和用户信息一起被包在 `data` 里 ——
 *
 * ```json
 * { "data": { "code": 200, "account": { "id": 123 }, "profile": { "userId": 123 } } }
 * ```
 *
 * 这带来两个必须写下来的坑：
 * 1. **顶层取 `code` 永远拿到 null**。按「拿不到 code 即失败」的通用规则会把一次
 *    成功的查询判成失败（健康监控记 ERROR、`MISSING_CODE` 误报）。
 * 2. **未登录时同样是 `code: 200`**，只是 `account` / `profile` 为 `null`。
 *    ⇒ **不能用 code 判断「是否已登录」**，必须看有没有 uid。
 *
 * 这段「解包 data → 取 uid」的逻辑此前在 `MusicApiServiceImpl`、`PlaybackControllerImpl`
 * 和 app 层各写过一份，且对 `profile.userId` 的回退处理不一致 —— NCM 的 profile 里
 * 用户 id 字段名是 `userId` 而不是 `id`，只读 `id` 的那份在部分账号上会取不到。
 * 现全部收敛到本文件。
 *
 * 兼容性：部分 Provider 把字段平铺在根层（`{code, account, profile}`），
 * 少部分甚至把 uid 直接放在 `data` 下，这里都按「先 data 后根层」的顺序兜住。
 */

/**
 * 解包 `login/status` 响应的 `data` 层。
 *
 * @return `data` 对象；没有 `data` 包裹时返回根对象本身；非对象响应返回 null。
 */
fun unwrapLoginStatusData(root: JsonElement?): JsonObject? {
    val obj = root as? JsonObject ?: return null
    return (obj["data"] as? JsonObject) ?: obj
}

/**
 * 提取当前登录用户的 uid。
 *
 * 优先 `account.id`，取不到（缺失或为 JSON null）时回退 `profile.userId`。
 *
 * @return uid；未登录或响应异常时返回 null
 */
fun extractUidFromLoginStatus(status: JsonElement?): Long? {
    val data = unwrapLoginStatusData(status) ?: return null
    val account = data["account"] as? JsonObject
    val profile = (data["profile"] as? JsonObject) ?: account
    return (account?.get("id") as? JsonPrimitive)?.longOrNull
        ?: (profile?.get("userId") as? JsonPrimitive)?.longOrNull
}

/**
 * `login/status` 是否代表「已登录」。
 *
 * ⚠️ 判据是**有没有 uid**，不是 code —— 见文件头第 2 条坑。
 */
fun isLoggedInStatus(status: JsonElement?): Boolean = extractUidFromLoginStatus(status) != null

/**
 * 取 `login/status` 的业务码：优先顶层 `code`，缺失时下沉到 `data.code`（NCM 形状）。
 *
 * 只用于 `login/status`。其余端点一律读顶层 `code`，不要拿这个函数去做通用解析 ——
 * 「顶层没有 code 就往下钻」在别的端点上可能把 `data` 里的业务字段误当成响应码。
 */
fun resolveLoginStatusCode(json: JsonObject): Int? =
    (json["code"] as? JsonPrimitive)?.intOrNull
        ?: ((json["data"] as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
