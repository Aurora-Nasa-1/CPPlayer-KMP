package cp.player.core.provider.typed

import cp.player.sdk.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal actual fun createSdkRuntime(allowedOrigins: Set<String>): ManagedProviderRuntime =
    ScopedProviderRuntime(allowedOrigins)

internal fun parseSdkAllowedOrigin(value: String): String {
    val uri = URI(value)
    require(uri.isAbsolute && uri.rawAuthority != null) { "Invalid SDK origin" }
    require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
        "SDK origin must not contain a path, query, or fragment"
    }
    return canonicalSdkOrigin(Url(value))
}

internal fun canonicalSdkOrigin(url: Url): String {
    validateHttpUrl(url.toString())
    // Plain HTTP is accepted only on loopback for reproducible local examples.
    require(url.protocol.name == "https" || url.host in setOf("localhost", "127.0.0.1", "::1"))
    return "${url.protocol.name}://${url.host}:${url.port}"
}

/** An account owns one client and one ephemeral store; neither is global. */
internal class ScopedProviderRuntime(allowedOrigins: Set<String>) : ManagedProviderRuntime {
    private val origins = allowedOrigins.map(::parseSdkAllowedOrigin).toSet()
    private val closed = AtomicBoolean(false)
    private val storage = mutableMapOf<String, ByteArray>()
    private val client = HttpClient {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
    }
    override suspend fun http(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        check(!closed.get())
        require(canonicalSdkOrigin(Url(request.url)) in origins) { "HTTP origin not permitted" }
        require(request.method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"))
        require(request.body?.let { it.size <= MAX_BYTES } != false)
        require(request.headers.size <= 64)
        request.headers.forEach {
            require(it.name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && it.value.length <= 8192)
            require(it.value.none { c -> c == '\r' || c == '\n' || c == '\u0000' })
            require(it.name.lowercase() !in setOf("host", "content-length", "transfer-encoding", "connection"))
        }
        withTimeout(10_000) {
            val response = client.request(request.url) {
                method = HttpMethod.parse(request.method)
                request.headers.forEach { headers.append(it.name, it.value) }
                request.body?.let { setBody(it.copyOf()) }
            }
            val channel = response.bodyAsChannel()
            try {
                val bytes = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = channel.readAvailable(chunk, 0, chunk.size)
                    if (count < 0) break
                    require(count <= MAX_BYTES - bytes.size()) { "HTTP body limit" }
                    bytes.write(chunk, 0, count)
                }
                val headers = response.headers.entries().flatMap { (name, values) -> values.map { Header(name, it) } }
                require(headers.size <= 500 && headers.sumOf { it.name.length + it.value.length } <= 64 * 1024)
                HttpResponse(response.status.value, headers, bytes.toByteArray())
            } finally { channel.cancel(null) }
        }
    }
    override suspend fun sha256(bytes: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        check(!closed.get()); require(bytes.size <= MAX_BYTES)
        MessageDigest.getInstance("SHA-256").digest(bytes)
    }
    override suspend fun read(key: String): ByteArray? = synchronized(storage) {
        check(!closed.get()); checkKey(key); storage[key]?.copyOf()
    }
    override suspend fun write(key: String, value: ByteArray?) {
        synchronized(storage) {
            check(!closed.get()); checkKey(key)
            require((value?.size ?: 0) <= 1024 * 1024)
            require(storage.values.sumOf { it.size } - (storage[key]?.size ?: 0) + (value?.size ?: 0) <= 1024 * 1024)
            if (value == null) storage.remove(key) else storage[key] = value.copyOf()
        }
    }
    private fun checkKey(key: String) { require(key.isNotBlank() && key.length <= 256) }
    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            client.close()
            synchronized(storage) { storage.values.forEach { it.fill(0) }; storage.clear() }
        }
    }
}
