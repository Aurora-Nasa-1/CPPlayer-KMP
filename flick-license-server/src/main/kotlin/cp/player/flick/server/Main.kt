package cp.player.flick.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

private const val LEASE_SECONDS = 180L
private val cloudCard = Regex("CPVC1\\.[A-Za-z0-9_-]{43}")

fun main() {
    val cards = Path.of(requiredEnv("FLICK_CLOUD_CARD_DIR"))
    require(Files.isDirectory(cards)) { "FLICK_CLOUD_CARD_DIR must exist" }
    val pem = Files.readString(Path.of(requiredEnv("FLICK_ISSUER_KEY_FILE")))
    val der = Base64.getDecoder().decode(
        pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
            .filterNot(Char::isWhitespace),
    )
    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    require(key is RSAPrivateKey && key.modulus.bitLength() in 2048..8192)
    val port = System.getenv("FLICK_CLOUD_PORT")?.toInt() ?: 8787

    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        routing {
            post("/v1/flick/lease") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val header = call.request.headers[HttpHeaders.Authorization]
                val card = header?.takeIf { it.startsWith("Bearer ") }?.substringAfter("Bearer ")
                val digest = card?.takeIf(cloudCard::matches)?.let {
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(it.encodeToByteArray()))
                }
                val cardExpiry = if (digest == null) null else withContext(Dispatchers.IO) {
                    val record = cards.resolve(digest)
                    try {
                        Files.readString(record).trim().toLongOrNull()
                    } catch (_: java.nio.file.NoSuchFileException) {
                        null
                    }
                }
                val now = Instant.now().epochSecond
                if (digest == null || cardExpiry == null || cardExpiry <= now) {
                    call.respondText("", status = HttpStatusCode.Forbidden)
                    return@post
                }
                val expiry = minOf(cardExpiry, now + LEASE_SECONDS)
                val payload = "cp.player/flick-vip/v2\n${digest.take(32)}\ncloud\n$expiry".encodeToByteArray()
                val signature = Signature.getInstance("SHA256withRSA").run {
                    initSign(key)
                    update(payload)
                    sign()
                }
                val base64 = Base64.getUrlEncoder()
                call.respondText(
                    "CPV1.${base64.encodeToString(payload)}.${base64.encodeToString(signature)}",
                    ContentType.Text.Plain,
                )
            }
        }
    }.start(wait = true)
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf(String::isNotBlank) ?: error("Missing $name")
