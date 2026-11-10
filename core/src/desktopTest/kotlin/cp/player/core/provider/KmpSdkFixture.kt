package cp.player.core.provider

import cp.player.core.local.*
import cp.player.core.media.LocalMediaItem
import cp.player.core.util.SettingsStorage
import cp.player.sdk.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

internal class SdkMemorySettings : SettingsStorage {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun getString(key: String, default: String?) = values[key] ?: default
    override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    override fun remove(key: String) { values.remove(key) }
    override fun contains(key: String) = values.containsKey(key)
    override fun clear() = values.clear()
}

internal class SdkEmptyLocal : LocalMediaSource {
    override suspend fun scan(): Flow<ScanProgress> = emptyFlow()
    override suspend fun importFolder(uri: String) = 0
    override fun removeItem(item: LocalMediaItem) {}
    override fun items() = MutableStateFlow<List<LocalMediaItem>>(emptyList())
    override val favorites = LocalFavorites("")
    override val scanSettings = LocalScanSettingsStore("")
    override val isScanningFlow = MutableStateFlow(false)
    override fun addExternalItems(items: List<LocalMediaItem>) {}
}

/** Real loopback HTTP and PCM audio. It is not a public music-service E2E. */
internal class KmpSdkFixture : AutoCloseable {
    val hangEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
    val tracks = listOf("001", "UUID-A", "A", "a").map {
        Track(it, "Catalog $it", listOf(Artist("artist-A", "Test artist")), 9_500)
    }
    val wav = ByteBuffer.allocate(152_044).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(152_036); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(152_000)
        repeat(76_000) { putShort((sin(it * 2 * PI * 440 / 8_000) * 8192).toInt().toShort()) }
    }.array()
    private val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        routing {
            get("/catalog.json") {
                call.respondText(tracks.joinToString(",", "[", "]") {
                    """{"id":"${it.id}","title":"${it.title}","artist":"Test artist","durationMs":9500}"""
                }, ContentType.Application.Json)
            }
            get("/tone.wav") { call.respondBytes(wav, ContentType.parse("audio/wav")) }
            get("/catalog/search") {
                val q = call.request.queryParameters["q"].orEmpty()
                if (q == "__hang_probe__") { hangEntered.complete(Unit); kotlinx.coroutines.delay(10_000) }
                val offset = call.request.queryParameters["offset"]?.toInt() ?: 0
                val limit = call.request.queryParameters["limit"]?.toInt() ?: 30
                val found = tracks.filter { q.equals("all", true) || it.title.contains(q, true) }
                val page = TrackPage(found.drop(offset).take(limit), found.size.toLong(), offset.toLong() + limit < found.size)
                call.respondBytes(WireWriter().also(page::write).finish())
            }
            get("/catalog/track") {
                val track = tracks.find { it.id == call.request.queryParameters["id"] }
                if (track == null) call.respondText("Missing", status = HttpStatusCode.NotFound)
                else call.respondBytes(WireWriter().also(track::write).finish())
            }
            get("/catalog/resolve") {
                val id = call.request.queryParameters["id"]
                if (tracks.none { it.id == id }) call.respondText("Missing", status = HttpStatusCode.NotFound)
                else {
                    val resource = MediaResource("$origin/tone.wav", "lossless", listOf(Header("X-Catalog-Id", id!!)),
                        1_900_000_000_000L, "WAV", 128_000, wav.size.toLong())
                    call.respondBytes(WireWriter().also(resource::write).finish())
                }
            }
        }
    }.start(wait = false)
    val origin: String = runBlocking { "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}" }
    override fun close() { server.stop(0, 1_000) }
}
