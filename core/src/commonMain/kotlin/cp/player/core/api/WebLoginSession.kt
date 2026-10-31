package cp.player.core.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class WebLoginSession(val url: String, val sessionId: String?)

fun parseWebLoginSession(response: JsonElement?): WebLoginSession? {
    val root = response as? JsonObject ?: return null
    if (!ApiResponseCodes.isSuccess(resolveLoginStatusCode(root))) return null
    val data = (root["data"] as? JsonObject) ?: root
    val url = extractVerifyUrl(root) ?: return null
    return WebLoginSession(url, (data["sessionId"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank))
}

enum class WebLoginState { PENDING, AUTHORIZED, EXPIRED, DENIED, FAILED }

fun parseWebLoginState(response: JsonElement?): WebLoginState {
    val root = response as? JsonObject ?: return WebLoginState.FAILED
    if (!ApiResponseCodes.isSuccess(resolveLoginStatusCode(root))) return WebLoginState.FAILED
    val data = (root["data"] as? JsonObject) ?: root
    return when ((data["status"] as? JsonPrimitive)?.contentOrNull) {
        "pending" -> WebLoginState.PENDING
        "authorized" -> WebLoginState.AUTHORIZED
        "expired" -> WebLoginState.EXPIRED
        "denied" -> WebLoginState.DENIED
        else -> WebLoginState.FAILED
    }
}
