package cp.player.core.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Only playback fields are URLs; covers and verification links are never audio. */
internal fun playbackUrlOf(response: JsonElement?): String? {
    val root = response as? JsonObject ?: return null
    val code = ((root["code"] ?: root["status"]) as? JsonPrimitive)?.intOrNull
    if (code != null && !ApiResponseCodes.isSuccess(code)) return null
    fun from(element: JsonElement?): String? = when (element) {
        is JsonObject -> {
            val itemCode = ((element["code"] ?: element["status"]) as? JsonPrimitive)?.intOrNull
            if (itemCode != null && !ApiResponseCodes.isSuccess(itemCode)) null
            else listOf("redirectUrl", "url").firstNotNullOfOrNull { key ->
                (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf {
                    it.startsWith("https://") || it.startsWith("http://")
                }
            }
        }
        is JsonArray -> element.firstNotNullOfOrNull { from(it) }
        else -> null
    }
    return from(root) ?: from(root["data"])
}

/** Absolute expiry is epoch milliseconds; legacy `expi` is a relative seconds TTL. */
internal fun playbackExpiresAt(response: JsonElement?, receivedAt: Long): Long? {
    val root = response as? JsonObject ?: return null
    val data = root["data"]
    val item = (data as? JsonObject) ?: (data as? JsonArray)?.firstOrNull() as? JsonObject
    for (obj in listOfNotNull(item, root)) {
        (obj["expireAt"] as? JsonPrimitive)?.longOrNull?.let { return it }
    }
    for (obj in listOfNotNull(item, root)) {
        (obj["expi"] as? JsonPrimitive)?.longOrNull?.let { seconds ->
            return receivedAt + seconds.coerceIn(0L, (Long.MAX_VALUE - receivedAt) / 1000L) * 1000L
        }
    }
    return null
}
