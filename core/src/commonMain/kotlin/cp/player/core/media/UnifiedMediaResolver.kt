package cp.player.core.media

import cp.player.core.BackendResult
import cp.player.core.local.LocalMediaSource
import cp.player.core.music.CPMediaId
import cp.player.core.music.UnifiedMusicSource

/** A stream view over the same data source used by playback and downloads. */
interface UnifiedMediaResolver {
    suspend fun resolveStream(mediaId: String, preferredQuality: String? = null): BackendResult<MediaStream>
    suspend fun getAvailableQualities(mediaId: String): BackendResult<List<MediaQualityOption>>
}

class UnifiedMediaResolverImpl(
    private val localMediaSource: LocalMediaSource,
    private val musicSource: UnifiedMusicSource,
) : UnifiedMediaResolver {
    override suspend fun resolveStream(mediaId: String, preferredQuality: String?): BackendResult<MediaStream> {
        val id = CPMediaId.parseOrNull(mediaId) ?: return BackendResult.Error("Invalid media ID")
        if (id.providerId == "local") {
            val item = localMediaSource.items().value.firstOrNull {
                it.path == id.resourceId &&
                    if (id.resourceType == "video") it.mediaType == MediaType.VIDEO
                    else id.resourceType in setOf("song", "audio") && it.mediaType == MediaType.AUDIO
            } ?: return BackendResult.Error("Local media not found")
            val quality = MediaQualityOption("original", "Original", sizeBytes = item.sizeBytes)
            return BackendResult.Success(MediaStream(
                mediaId, item.path, currentQuality = quality, availableQualities = listOf(quality),
                mediaType = item.mediaType, title = item.title, artist = item.artist, coverUrl = item.coverUri,
                durationMs = item.durationMs,
            ))
        }
        if (id.resourceType in setOf("mv", "video")) {
            val resolution = preferredQuality?.toIntOrNull() ?: if (preferredQuality == null) 1080
                else return BackendResult.Error("Video quality must be a numeric resolution")
            return when (val result = musicSource.resolveVideoResource(mediaId, resolution)) {
                is BackendResult.Success -> {
                    val resource = result.data
                    val quality = MediaQualityOption(
                        resource.actualQuality, resource.actualQuality,
                        bitrate = resource.bitrate?.toLong() ?: 0, sizeBytes = resource.sizeBytes ?: 0,
                    )
                    BackendResult.Success(MediaStream(
                        mediaId, resource.url, headers = resource.headers.associate { it.name to it.value },
                        currentQuality = quality, availableQualities = listOf(quality), mediaType = MediaType.VIDEO,
                    ))
                }
                is BackendResult.Error -> result
                is BackendResult.Unsupported -> result
            }
        }
        if (id.resourceType !in setOf("song", "audio"))
            return BackendResult.Unsupported("Media type is not in the current SDK contract")
        return when (val result = musicSource.getSongUrl(mediaId, preferredQuality ?: "standard")) {
            is BackendResult.Success -> {
                val resource = result.data
                val quality = MediaQualityOption(
                    resource.qualityKey ?: resource.level, resource.qualityLabel ?: resource.level,
                    bitrate = resource.bitrate?.toLong() ?: 0, sizeBytes = resource.sizeBytes ?: 0,
                )
                BackendResult.Success(MediaStream(
                    mediaId, resource.url, headers = resource.headers.orEmpty(),
                    currentQuality = quality, availableQualities = listOf(quality), mediaType = MediaType.AUDIO,
                ))
            }
            is BackendResult.Error -> result
            is BackendResult.Unsupported -> result
        }
    }

    override suspend fun getAvailableQualities(mediaId: String): BackendResult<List<MediaQualityOption>> {
        val id = CPMediaId.parseOrNull(mediaId) ?: return BackendResult.Error("Invalid media ID")
        if (id.providerId != "local")
            return musicSource.getMediaQualityOptions(mediaId)
        return when (val result = resolveStream(mediaId)) {
            is BackendResult.Success -> BackendResult.Success(result.data.availableQualities)
            is BackendResult.Error -> result
            is BackendResult.Unsupported -> result
        }
    }
}
