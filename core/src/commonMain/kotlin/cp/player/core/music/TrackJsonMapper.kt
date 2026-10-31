package cp.player.core.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    // 歌手：数组（`ar` / `artists`）优先，其次单对象 `artist`，最后才是 `artist` 字符串。
    // 前两种能拿到 id（可跳主页），最后一种只有名字 —— 见 [TrackSummary.artists]。
    val artistArray = (json["ar"] as? JsonArray) ?: (json["artists"] as? JsonArray)
    val fromArray = artistArray?.mapNotNull(::artistOf).orEmpty()
    val artists = fromArray.ifEmpty { listOfNotNull((json["artist"] as? JsonObject)?.let(::artistOf)) }
    val artistNames = artists.joinToString(" / ") { it.name }
        .ifEmpty { (json["artist"] as? JsonPrimitive)?.contentOrNull ?: "" }

    val albumObj = (json["al"] as? JsonObject) ?: (json["album"] as? JsonObject)
    val albumName = (albumObj?.get("name") as? JsonPrimitive)?.contentOrNull
        ?: (albumObj?.get("title") as? JsonPrimitive)?.contentOrNull
        ?: (json["album"] as? JsonPrimitive)?.contentOrNull

    val rootCover = ((json["coverUrl"] ?: json["cover"] ?: json["picture"] ?: json["picUrl"]) as? JsonPrimitive)?.contentOrNull
    val albumCover = (albumObj?.get("picUrl") as? JsonPrimitive)?.contentOrNull
        ?: (albumObj?.get("coverUrl") as? JsonPrimitive)?.contentOrNull
        ?: (albumObj?.get("cover") as? JsonPrimitive)?.contentOrNull
    val coverUrl = rootCover ?: albumCover

    val durationMs = trackDurationMs(json)

    val trackTitle = (json["title"] as? JsonPrimitive)?.contentOrNull
        ?: (json["name"] as? JsonPrimitive)?.contentOrNull
        ?: ((json["song"] as? JsonPrimitive)?.contentOrNull ?: "")

    val mvId = ((json["mv"] ?: json["mvid"]) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

    return TrackSummary(
        id = id,
        name = trackTitle,
        artist = artistNames,
        album = albumName,
        coverUrl = coverUrl,
        durationMs = durationMs,
        artists = artists,
        mvId = mvId,
    )
}

/**
 * 单个歌手条目 → [ArtistSummary]。
 *
 * 名字为空的条目整条丢弃（拼进 `artist` 只会留下空的 " / "）。
 * **缺 id 的条目保留**：名字仍要出现在歌手串里，只是 `id` 置 `0L` —— 调用方据此
 * 把它渲染成不可点，而不是跳到一个错的人主页（这一点比「少一个名字」重要）。
 */
private fun artistOf(element: JsonElement): ArtistSummary? {
    val obj = element as? JsonObject ?: return null
    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    return ArtistSummary(
        id = (obj["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L } ?: 0L,
        name = name,
        avatarUrl = null,
        resourceId = (obj["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    )
}

/** 上游返回的裸曲目 id：`id` 优先，`songId`/`trackId` 回退。搜索路径用它当 [TrackSummary.id]。 */
internal fun rawTrackId(json: JsonObject): String =
    (json["id"] as? JsonPrimitive)?.contentOrNull
        ?: (json["songId"] as? JsonPrimitive)?.contentOrNull
        ?: (json["trackId"] as? JsonPrimitive)?.contentOrNull
        ?: ""

/** Explicit units take precedence; only the historical ambiguous `duration` uses a heuristic. */
internal fun trackDurationMs(json: JsonObject): Long {
    for (key in listOf("durationMs", "dt")) {
        (json[key] as? JsonPrimitive)?.longOrNull?.let { return it.coerceAtLeast(0L) }
    }
    (json["durationSeconds"] as? JsonPrimitive)?.longOrNull?.let {
        return it.coerceIn(0L, Long.MAX_VALUE / 1000L) * 1000L
    }
    val legacy = (json["duration"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0L) ?: 0L
    return if (legacy in 1..9999) legacy * 1000L else legacy
}
