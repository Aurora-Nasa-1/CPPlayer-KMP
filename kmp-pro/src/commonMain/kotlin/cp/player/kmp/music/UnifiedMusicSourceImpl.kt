package cp.player.kmp.music

import cp.player.kmp.BackendResult
import cp.player.kmp.api.MusicApiService
import cp.player.kmp.local.LocalMusicSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class UnifiedMusicSourceImpl(
    private val musicApiService: MusicApiService,
    private val localMusicSource: LocalMusicSource
) : UnifiedMusicSource {

    override suspend fun getTrackDetail(mediaId: String): MusicResult<TrackSummary> {
        val id = CPMediaId.parse(mediaId)
        if (id.providerId == "local") {
            val list = localMusicSource.cached()
            val song = list.find { it.path == id.resourceId }
            return if (song != null) {
                BackendResult.Success(
                    TrackSummary(
                        id = mediaId,
                        name = song.title,
                        artist = song.artist ?: "Unknown",
                        album = song.album,
                        coverUrl = null,
                        durationMs = song.durationMs
                    )
                )
            } else {
                BackendResult.Error("Local song not found")
            }
        }
        return try {
            val json = musicApiService.getSongDetail(listOf(id.resourceId))
            val songs = (json as? JsonObject)?.get("songs")?.jsonArray
            if (songs != null && songs.isNotEmpty()) {
                val track = songs[0].jsonObject
                BackendResult.Success(track.toTrackSummary(mediaId))
            } else {
                BackendResult.Error("Song not found: $mediaId")
            }
        } catch (e: Exception) {
            BackendResult.Error("Failed to get track detail: ${e.message}", cause = e)
        }
    }

    override suspend fun getSongUrl(mediaId: String, level: String): MusicResult<SongUrl> {
        val id = CPMediaId.parse(mediaId)
        if (id.providerId == "local") {
            return BackendResult.Success(SongUrl(id.resourceId, level, null, null))
        }
        return try {
            val json = musicApiService.getSongUrl(id.resourceId, level)
            val url = extractUrl(json)
            if (url != null && url.startsWith("http")) {
                val sizeBytes = ((json as? JsonObject)?.get("size") as? JsonPrimitive)?.longOrNull
                val cookie = ((json as? JsonObject)?.get("cookie") as? JsonPrimitive)?.contentOrNull
                BackendResult.Success(SongUrl(url, level, sizeBytes, null, cookie))
            } else {
                BackendResult.Error("No valid URL returned for $mediaId")
            }
        } catch (e: Exception) {
            BackendResult.Error("Failed to get song URL: ${e.message}", cause = e)
        }
    }

    override suspend fun search(keywords: String, type: Int, providers: List<String>?): MusicResult<SearchResult> {
        return MusicSourceFromApi.search(musicApiService, keywords, type)
    }

    override suspend fun getUserPlaylists(providerId: String, uid: Long): MusicResult<List<PlaylistSummary>> {
        return MusicSourceFromApi.getUserPlaylists(musicApiService, uid)
    }

    // ======================== 内部解析 ========================

    private fun JsonObject.toTrackSummary(mediaId: String): TrackSummary {
        val artists = this["ar"]?.jsonArray ?: this["artists"]?.jsonArray
        val artistNames = artists?.joinToString(" / ") {
            ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
        } ?: ((this["artist"] as? JsonPrimitive)?.contentOrNull ?: "")
        val albumObj = (this["al"] as? JsonObject) ?: (this["album"] as? JsonObject)
        return TrackSummary(
            id = mediaId,
            name = (this["name"] as? JsonPrimitive)?.contentOrNull ?: "",
            artist = artistNames,
            album = (albumObj?.get("name") as? JsonPrimitive)?.contentOrNull,
            coverUrl = (albumObj?.get("picUrl") as? JsonPrimitive)?.contentOrNull,
            durationMs = ((this["dt"] ?: this["duration"]) as? JsonPrimitive)?.longOrNull ?: 0L
        )
    }

    private fun extractUrl(body: kotlinx.serialization.json.JsonElement): String? {
        if (body !is JsonObject) return null
        (body["redirectUrl"] as? JsonPrimitive)?.contentOrNull?.let { if (it.startsWith("http")) return it }
        return findUrlRecursive(body)
    }

    private fun findUrlRecursive(element: kotlinx.serialization.json.JsonElement?): String? {
        if (element == null) return null
        when (element) {
            is JsonPrimitive -> {
                val s = element.contentOrNull ?: return null
                if (s.startsWith("http") && s.length > 12 && !s.contains("null")) return s
            }
            is JsonObject -> {
                for (key in listOf("url", "picUrl", "coverImgUrl", "avatarUrl")) {
                    val p = element[key]
                    if (p is JsonPrimitive) {
                        val s = p.contentOrNull
                        if (s != null && s.startsWith("http") && s.length > 12 && !s.contains("null")) return s
                    }
                }
                for ((_, v) in element) { findUrlRecursive(v)?.let { return it } }
            }
            is JsonArray -> {
                for (e in element) { findUrlRecursive(e)?.let { return it } }
            }
        }
        return null
    }
}
