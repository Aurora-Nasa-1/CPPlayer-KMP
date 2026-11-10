package cp.player.sdk.example

import cp.player.sdk.*
import kotlinx.serialization.json.*

/**
 * Compiled example: only upstream catalog JSON is parsed here, never CPPlayer's
 * envelopes or v1/v3 fields. The host injects an origin and scoped HTTP service.
 */
class CatalogProvider(private val context: ProviderContext) : MusicProvider {
    private val origin = context.settings.getValue("origin").trimEnd('/')
    override suspend fun capabilities() = Capabilities(listOf(Capability.SEARCH, Capability.GET, Capability.RESOLVE))

    private suspend fun fetch(path: String): JsonElement {
        val response = context.runtime.http(HttpRequest("$origin$path", "GET"))
        if (response.status == 404) throw ProviderException(Failure(FailureCategory.NOT_FOUND, "Track unavailable"))
        if (response.status != 200) throw ProviderException(Failure(FailureCategory.NETWORK, "Catalog unavailable"))
        return Json.parseToJsonElement(response.body.decodeToString())
    }
    private fun track(value: JsonObject) = Track(
        id = value.getValue("id").jsonPrimitive.content,
        title = value.getValue("title").jsonPrimitive.content,
        artists = listOf(Artist("artist-A", value.getValue("artist").jsonPrimitive.content)),
        durationMs = value.getValue("durationMs").jsonPrimitive.long,
    )
    override suspend fun search(request: SearchRequest): TrackPage {
        val catalog = fetch("/catalog.json").jsonArray.map { track(it.jsonObject) }
        val matching = catalog.filter { request.keyword.equals("all", true) || it.title.contains(request.keyword, true) }
        return TrackPage(matching.drop(request.offset).take(request.limit), matching.size.toLong(),
            request.offset.toLong() + request.limit < matching.size)
    }
    override suspend fun get(request: IdRequest): Track {
        return fetch("/catalog.json").jsonArray.map { track(it.jsonObject) }.firstOrNull { it.id == request.id }
            ?: throw ProviderException(Failure(FailureCategory.NOT_FOUND, "Track unavailable"))
    }
    override suspend fun resolve(request: ResolveRequest): MediaResource {
        get(IdRequest(request.id))
        return MediaResource("$origin/tone.wav", "lossless", listOf(Header("X-Catalog-Id", request.id)),
            expiresAtEpochMs = 1_900_000_000_000L, format = "WAV", bitrate = 128_000, sizeBytes = 152_044)
    }
}
