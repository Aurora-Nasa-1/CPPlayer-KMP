package cp.player.core.provider.typed

import cp.player.core.provider.HostTrafficPolicy
import cp.player.core.util.DesktopDataDir
import cp.player.core.util.PlatformInfo
import cp.player.core.util.currentTimeMillis
import cp.player.sdk.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal actual fun createSdkRuntime(allowedOrigins: Set<String>): ManagedProviderRuntime =
    ScopedProviderRuntime(allowedOrigins)

internal actual fun createScopedSdkRuntime(allowedOrigins: Set<String>, cacheScopeKey: String): ManagedProviderRuntime =
    ScopedProviderRuntime(allowedOrigins, cacheScopeKey)

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

/** An account owns one client, one ephemeral store and a scoped conditional HTTP cache. */
internal class ScopedProviderRuntime(
    allowedOrigins: Set<String>,
    private val cacheScopeKey: String? = null,
) : ManagedProviderRuntime {
    private val origins = allowedOrigins.map(::parseSdkAllowedOrigin).toSet()
    private val closed = AtomicBoolean(false)
    private val storage = mutableMapOf<String, ByteArray>()
    private val httpLock = Any()
    private val httpCache = LinkedHashMap<String, CachedHttpEntry>(24, 0.75f, true)
    private val httpInflight = HashMap<String, CompletableDeferred<HttpResponse>>()
    private val client = HttpClient {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
    }

    override fun hostState(): HostRuntimeState = HostTrafficPolicy.sdkHostState()

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

        val isCacheableMethod = request.method == "GET" &&
            !hasNoCacheDirective(request.headers)
        if (!isCacheableMethod) {
            return@withContext executeHttp(request)
        }

        val cacheKey = buildHttpCacheKey(cacheScopeKey, request)
        val hasSensitiveAuthHeader = request.headers.any {
            val lower = it.name.lowercase()
            lower == "cookie" || lower == "authorization"
        }

        val now = currentTimeMillis()
        val cachedEntry = synchronized(httpLock) { httpCache[cacheKey] }
            ?: if (!hasSensitiveAuthHeader && cacheScopeKey != null && !PlatformInfo.isAndroid) {
                loadDiskHttpCache(cacheKey)?.also { diskEntry ->
                    synchronized(httpLock) { putMemoryCacheLocked(cacheKey, diskEntry) }
                }
            } else {
                null
            }

        if (cachedEntry != null && (now < cachedEntry.expiresAtMs || HostTrafficPolicy.offlineMode)) {
            return@withContext cachedEntry.toResponse()
        }

        var leader = false
        val deferred = synchronized(httpLock) {
            httpInflight[cacheKey] ?: CompletableDeferred<HttpResponse>().also {
                httpInflight[cacheKey] = it
                leader = true
            }
        }
        if (!leader) return@withContext deferred.await()

        try {
            val conditionalRequest = attachConditionalHeaders(request, cachedEntry)
            val response = executeHttp(conditionalRequest)
            val finalResponse = if (response.status == 304 && cachedEntry != null) {
                val mergedHeaders = mergeHeadersForNotModified(cachedEntry.headers, response.headers)
                val updated = cachedEntry.copy(
                    headers = mergedHeaders,
                    expiresAtMs = computeExpiresAtMs(mergedHeaders, currentTimeMillis()),
                    etag = findHeaderValue(mergedHeaders, "etag") ?: cachedEntry.etag,
                    lastModified = findHeaderValue(mergedHeaders, "last-modified") ?: cachedEntry.lastModified,
                )
                storeHttpCache(cacheKey, updated, persistToDisk = !hasSensitiveAuthHeader && cacheScopeKey != null && !PlatformInfo.isAndroid)
                updated.toResponse()
            } else {
                if (response.status == 200 && isResponseCacheable(response) && response.body.size <= MAX_HTTP_CACHE_BODY_BYTES) {
                    val entry = CachedHttpEntry(
                        headers = response.headers,
                        body = response.body,
                        expiresAtMs = computeExpiresAtMs(response.headers, currentTimeMillis()),
                        etag = findHeaderValue(response.headers, "etag"),
                        lastModified = findHeaderValue(response.headers, "last-modified"),
                    )
                    if (entry.etag != null || entry.lastModified != null || entry.expiresAtMs > currentTimeMillis()) {
                        storeHttpCache(cacheKey, entry, persistToDisk = !hasSensitiveAuthHeader && cacheScopeKey != null && !PlatformInfo.isAndroid)
                    }
                }
                response
            }
            deferred.complete(finalResponse)
            finalResponse
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            synchronized(httpLock) {
                if (httpInflight[cacheKey] === deferred) httpInflight.remove(cacheKey)
            }
        }
    }

    private suspend fun executeHttp(request: HttpRequest): HttpResponse = withTimeout(10_000) {
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

    private fun storeHttpCache(cacheKey: String, entry: CachedHttpEntry, persistToDisk: Boolean) {
        synchronized(httpLock) { putMemoryCacheLocked(cacheKey, entry) }
        if (persistToDisk) saveDiskHttpCache(cacheKey, entry)
    }

    private fun putMemoryCacheLocked(cacheKey: String, entry: CachedHttpEntry) {
        httpCache[cacheKey] = entry
        while (httpCache.size > MAX_HTTP_MEMORY_ENTRIES) {
            httpCache.remove(httpCache.keys.first())
        }
    }

    private fun buildHttpCacheKey(scope: String?, request: HttpRequest): String {
        val authDigest = request.headers
            .filter { it.name.equals("cookie", true) || it.name.equals("authorization", true) }
            .joinToString("|") { "${it.name.lowercase()}=${it.value}" }
        val raw = "${scope.orEmpty()}|${request.url}|$authDigest"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0F])
            }
        }
    }

    private fun hasNoCacheDirective(headers: List<Header>): Boolean = headers.any {
        (it.name.equals("cache-control", true) && (it.value.contains("no-store", true) || it.value.contains("no-cache", true))) ||
            (it.name.equals("pragma", true) && it.value.contains("no-cache", true))
    }

    private fun isResponseCacheable(response: HttpResponse): Boolean {
        for (h in response.headers) {
            if (h.name.equals("set-cookie", true)) return false
            if (h.name.equals("cache-control", true)) {
                val lower = h.value.lowercase()
                if ("no-store" in lower || "private" in lower || "max-age=0" in lower) return false
            }
        }
        return true
    }

    private fun findHeaderValue(headers: List<Header>, name: String): String? =
        headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    private fun computeExpiresAtMs(headers: List<Header>, nowMs: Long): Long {
        val cc = findHeaderValue(headers, "cache-control")?.lowercase() ?: return nowMs
        val maxAgeMatch = Regex("max-age\\s*=\\s*([0-9]+)").find(cc)
        val seconds = maxAgeMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return if (seconds > 0L) nowMs + seconds.coerceAtMost(3600L) * 1000L else nowMs
    }

    private fun attachConditionalHeaders(request: HttpRequest, cached: CachedHttpEntry?): HttpRequest {
        if (cached == null) return request
        val hasIfNoneMatch = request.headers.any { it.name.equals("if-none-match", true) }
        val hasIfModifiedSince = request.headers.any { it.name.equals("if-modified-since", true) }
        val extra = ArrayList<Header>(2)
        if (!hasIfNoneMatch && !cached.etag.isNullOrBlank()) extra.add(Header("If-None-Match", cached.etag))
        if (!hasIfModifiedSince && !cached.lastModified.isNullOrBlank()) extra.add(Header("If-Modified-Since", cached.lastModified))
        if (extra.isEmpty()) return request
        return request.copy(headers = request.headers + extra)
    }

    private fun mergeHeadersForNotModified(oldHeaders: List<Header>, newHeaders: List<Header>): List<Header> {
        val replacedNames = newHeaders.map { it.name.lowercase() }.toSet()
        return oldHeaders.filter { it.name.lowercase() !in replacedNames } + newHeaders
    }

    private fun loadDiskHttpCache(cacheKey: String): CachedHttpEntry? = runCatching {
        val dir = DesktopDataDir.directory("cache/sdk-http")
        val meta = File(dir, "$cacheKey.meta")
        val bodyFile = File(dir, "$cacheKey.body")
        if (!meta.isFile || !bodyFile.isFile) return@runCatching null
        val lines = meta.readLines(Charsets.UTF_8)
        if (lines.size < 3) return@runCatching null
        val expiresAt = lines[0].toLongOrNull() ?: 0L
        val etag = lines[1].takeIf { it.isNotEmpty() }
        val lastMod = lines[2].takeIf { it.isNotEmpty() }
        val headers = lines.drop(3).mapNotNull { line ->
            val idx = line.indexOf('\t')
            if (idx <= 0) null else Header(line.substring(0, idx), line.substring(idx + 1))
        }
        CachedHttpEntry(headers, bodyFile.readBytes(), expiresAt, etag, lastMod)
    }.getOrNull()

    private fun saveDiskHttpCache(cacheKey: String, entry: CachedHttpEntry) {
        runCatching {
            val dir = DesktopDataDir.directory("cache/sdk-http")
            val bodyFile = File(dir, "$cacheKey.body")
            val meta = File(dir, "$cacheKey.meta")
            bodyFile.writeBytes(entry.body)
            val content = buildString {
                append(entry.expiresAtMs).append('\n')
                append(entry.etag.orEmpty().replace('\n', ' ')).append('\n')
                append(entry.lastModified.orEmpty().replace('\n', ' ')).append('\n')
                for (h in entry.headers) {
                    if (h.name.contains('\n') || h.value.contains('\n')) continue
                    append(h.name).append('\t').append(h.value).append('\n')
                }
            }
            meta.writeText(content, Charsets.UTF_8)
        }
    }

    private data class CachedHttpEntry(
        val headers: List<Header>,
        val body: ByteArray,
        val expiresAtMs: Long,
        val etag: String?,
        val lastModified: String?,
    ) {
        fun toResponse(): HttpResponse = HttpResponse(200, headers, body.copyOf())
    }

    private companion object {
        const val MAX_HTTP_MEMORY_ENTRIES = 24
        const val MAX_HTTP_CACHE_BODY_BYTES = 1024 * 1024
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
            synchronized(httpLock) { httpCache.clear() }
        }
    }
}
