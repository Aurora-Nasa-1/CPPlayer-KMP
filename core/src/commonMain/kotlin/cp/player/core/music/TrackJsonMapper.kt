package cp.player.core.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 曲目 JSON → [TrackSummary] 的**唯一**映射。
 *
 * 两条解析路径 —— [MusicSourceFromApi]（搜索 / 日推 / 云盘）与
 * [UnifiedMusicSourceImpl]（曲目详情）—— 曾各有一份 `toTrackSummary` 且已分叉：
 * 同一份上游 JSON 在两条路径上解析出不同字段，而没有任何东西提示它们不一致。
 * `UnifiedMusicSourceContractTest` 钉住两条路径的结果必须相同，前提是它们走同一份映射。
 *
 * 键名兼容两套上游习惯：网易云的 `ar` / `al` / `dt`，通用风格的 `artists` / `album` / `duration`；
 * `id`/`songId`、`name`/`song` 是回退关系。
 *
 * @param id 写进 [TrackSummary.id] 的值。**这是两条路径唯一允许不同的字段**：
 *   统一音源用调用方给的带命名空间 mediaId，搜索路径用上游返回的裸 id（见 [rawTrackId]）。
 */
internal fun trackSummaryOf(json: JsonObject, id: String): TrackSummary {
    val artists = (json["ar"] as? JsonArray) ?: (json["artists"] as? JsonArray)
    val artistNames = artists?.joinToString(" / ") {
        ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
    } ?: ((json["artist"] as? JsonPrimitive)?.contentOrNull ?: "")
    val albumObj = (json["al"] as? JsonObject) ?: (json["album"] as? JsonObject)
    return TrackSummary(
        id = id,
        name = (json["name"] as? JsonPrimitive)?.contentOrNull
            ?: ((json["song"] as? JsonPrimitive)?.contentOrNull ?: ""),
        artist = artistNames,
        album = (albumObj?.get("name") as? JsonPrimitive)?.contentOrNull,
        coverUrl = (albumObj?.get("picUrl") as? JsonPrimitive)?.contentOrNull,
        durationMs = ((json["dt"] ?: json["duration"]) as? JsonPrimitive)?.longOrNull ?: 0L,
    )
}

/** 上游返回的裸曲目 id：`id` 优先，`songId` 回退。搜索路径用它当 [TrackSummary.id]。 */
internal fun rawTrackId(json: JsonObject): String =
    (json["id"] as? JsonPrimitive)?.contentOrNull
        ?: (json["songId"] as? JsonPrimitive)?.contentOrNull
        ?: ""
