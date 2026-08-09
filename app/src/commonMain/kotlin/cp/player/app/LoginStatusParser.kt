package cp.player.app

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 从 login/status 响应中提取当前登录用户的 uid（统一入口）。
 *
 * 兼容点：
 * - 部分 Provider 以 `{code, data:{account, profile}}` 包裹返回，部分将字段平铺在根层；
 *   存在 data 对象层时先解包，否则直接在根层查找。
 * - 优先取 `account.id`，取不到（缺失或值为 JSON null）时回退 `profile.userId`
 *   （NCM 的 profile 用户 id 字段名为 userId 而非 id）。
 *
 * @return uid；未登录或响应异常时返回 null
 */
fun extractUidFromLoginStatus(status: JsonElement?): Long? {
    if (status == null) return null
    val data = unwrapLoginStatusData(status) ?: return null
    val account = data["account"] as? JsonObject
    val profile = (data["profile"] as? JsonObject) ?: account
    val uid = (account?.get("id") as? JsonPrimitive)?.longOrNull
        ?: (profile?.get("userId") as? JsonPrimitive)?.longOrNull
    return uid
}

/** 解包 login/status 响应的 data 层（无包裹时返回根对象本身）；非对象响应返回 null。 */
fun unwrapLoginStatusData(root: JsonElement): JsonObject? {
    val obj = root as? JsonObject ?: return null
    return (obj["data"] as? JsonObject) ?: obj
}
