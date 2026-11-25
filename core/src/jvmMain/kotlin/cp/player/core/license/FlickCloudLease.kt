package cp.player.core.license

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64

private val leaseUrl by lazy { Base64.Default.decode(FLICK_VIP_LEASE_URL_B64).decodeToString() }
private val client by lazy {
    HttpClient(OkHttp) {
        followRedirects = false
        engine {
            config {
                followRedirects(false)
                callTimeout(10, TimeUnit.SECONDS)
            }
        }
    }
}

internal actual suspend fun fetchFlickCloudLease(card: String): String? {
    require(leaseUrl.isNotEmpty()) { "此安装包未配置云控授权服务" }
    val response = client.post(leaseUrl) { header(HttpHeaders.Authorization, "Bearer $card") }
    val body = response.bodyAsChannel()
    try {
        if (response.status == HttpStatusCode.Forbidden) return null
        check(response.status == HttpStatusCode.OK)
        val bytes = ByteArray(2049)
        var size = 0
        while (size < bytes.size) {
            val count = body.readAvailable(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        check(size in 1..2048)
        return bytes.decodeToString(endIndex = size, throwOnInvalidSequence = true)
    } finally {
        body.cancel(null)
    }
}

internal actual fun flickCloudCardId(card: String): String =
    MessageDigest.getInstance("SHA-256").digest(card.encodeToByteArray()).take(16)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
