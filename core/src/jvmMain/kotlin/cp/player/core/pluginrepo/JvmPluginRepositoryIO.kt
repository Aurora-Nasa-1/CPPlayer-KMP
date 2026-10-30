package cp.player.core.pluginrepo

import cp.player.core.lyricsplugin.PluginManifest
import cp.player.core.provider.ModuleManifest
import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformInfo
import cp.player.core.util.PlatformSupport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class JvmPluginRepositoryIO(private val root: File) : PluginRepositoryIO {
    private val client = HttpClient(OkHttp) {
        followRedirects = false
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 15 * 60_000
        }
    }

    override suspend fun fetchCatalog(url: String): String = withContext(Dispatchers.IO) {
        val output = ByteArrayOutputStream()
        readResponse(url, PluginRepositoryService.MAX_CATALOG_BYTES.toLong(), output)
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    override suspend fun cachedCatalog(url: String): String? = withContext(Dispatchers.IO) {
        val file = cacheFile(url)
        if (!file.isFile || file.length() > PluginRepositoryService.MAX_CATALOG_BYTES) null
        else runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    override suspend fun cacheCatalog(url: String, text: String): Unit = withContext(Dispatchers.IO) {
        val file = cacheFile(url)
        requireNotNull(file.parentFile).mkdirs()
        val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(text, Charsets.UTF_8)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun cacheFile(url: String) = File(root, "catalogs/${sha256(url.toByteArray())}.json")

    override suspend fun preparePackage(
        plugin: RepositoryPlugin,
        artifact: PluginArtifact,
        onProgress: (PluginInstallPhase, Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val directory = File(root, "downloads").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.zip")
        try {
            var lastProgress = 0L
            file.outputStream().buffered().use { output ->
                readResponse(artifact.url, artifact.bytes, output) { bytes ->
                    val now = System.nanoTime()
                    if (now - lastProgress >= 100_000_000 || bytes == artifact.bytes) {
                        lastProgress = now
                        onProgress(PluginInstallPhase.DOWNLOADING, bytes)
                    }
                }
            }
            onProgress(PluginInstallPhase.VERIFYING, file.length())
            val context = currentCoroutineContext()
            verifyRepositoryPackage(file, plugin, artifact) { context.ensureActive() }
            file.absolutePath
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private suspend fun readResponse(
        initialUrl: String,
        limit: Long,
        output: OutputStream,
        onProgress: (Long) -> Unit = {},
    ) {
        var url = requireRepositoryHttpsUrl(initialUrl)
        repeat(6) { redirectCount ->
            currentCoroutineContext().ensureActive()
            val redirect = client.prepareGet(url) {
                header(HttpHeaders.UserAgent, "CPPlayer-PluginRepository/1")
                header(HttpHeaders.Accept, "application/json, application/zip, application/octet-stream")
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
                if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                    require(redirectCount < 5) { "Too many download redirects" }
                    val location = response.headers[HttpHeaders.Location] ?: error("Redirect has no destination")
                    requireRepositoryHttpsUrl(URI(url).resolve(location).toString())
                } else {
                    require(response.status.value == 200) { "HTTP ${response.status.value}" }
                    val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    require(length == null || length in 0..limit) { "Response exceeds the allowed size" }
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= limit) { "Response exceeds the allowed size" }
                        output.write(buffer, 0, count)
                        onProgress(total)
                    }
                    null
                }
            }
            if (redirect == null) return
            url = redirect
        }
        error("Too many download redirects")
    }

    override suspend fun discardPackage(path: String) {
        withContext(Dispatchers.IO) {
            val file = File(path).canonicalFile
            val downloads = File(root, "downloads").canonicalFile
            require(file.parentFile == downloads) { "Refusing to remove a non-download file" }
            file.delete()
        }
    }

    override fun close() = client.close()
}

/** Validate the complete archive before any existing plugin is touched or any entry is executed. */
internal fun verifyRepositoryPackage(
    file: File,
    plugin: RepositoryPlugin,
    artifact: PluginArtifact,
    checkCancellation: () -> Unit = {},
) {
    require(file.length() == artifact.bytes) { "Package length does not match the catalog" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkCancellation()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    require(digest.digest().toHex() == artifact.sha256) { "Package SHA-256 does not match the catalog" }
    val lyrics = plugin.kind == RepositoryPluginKind.LYRICS
    val maxEntries = if (lyrics) 512 else 4096
    val maxEntryBytes = if (lyrics) 16L * 1024 * 1024 else PluginRepositoryService.MAX_PACKAGE_BYTES
    val maxTotalBytes = if (lyrics) 64L * 1024 * 1024 else 512L * 1024 * 1024
    val names = mutableSetOf<String>()
    var total = 0L
    var manifestText: String? = null
    ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().toList()
        require(entries.size <= maxEntries) { "Too many archive entries" }
        for (entry in entries) {
            checkCancellation()
            val name = entry.name
            requireSafePluginPath(name.trimEnd('/'))
            require(names.add(name.trimEnd('/').lowercase())) { "Duplicate archive path" }
            if (entry.isDirectory) continue
            require(name == "manifest.json" || !name.endsWith("/manifest.json", ignoreCase = true)) {
                "Repository packages must contain exactly one root manifest"
            }
            var written = 0L
            val manifest = if (name == "manifest.json") ByteArrayOutputStream() else null
            zip.getInputStream(entry).buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    checkCancellation()
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    total += count
                    require(written <= maxEntryBytes && total <= maxTotalBytes) { "Archive expands beyond its size limit" }
                    if (manifest != null) {
                        require(written <= 256 * 1024) { "Manifest is too large" }
                        manifest.write(buffer, 0, count)
                    }
                }
            }
            manifest?.let { manifestText = it.toString(Charsets.UTF_8.name()) }
        }
        val text = manifestText ?: error("Package has no root manifest.json")
        val json = Json { ignoreUnknownKeys = true }
        if (lyrics) {
            val manifest = json.decodeFromString<PluginManifest>(text)
            require(manifest.id == plugin.id && manifest.versionName == plugin.version &&
                manifest.versionCode == plugin.versionCode
            ) { "Lyrics package identity/version does not match the catalog" }
            require(manifest.apiVersion == plugin.apiVersion && manifest.minHostApiVersion == plugin.minHostApiVersion) {
                "Lyrics package API does not match the catalog"
            }
            requireSafePluginPath(manifest.entry)
            require(zip.getEntry(manifest.entry)?.isDirectory == false) { "Lyrics entry script is missing" }
            manifest.includeDirs.forEach(::requireSafePluginPath)
        } else {
            val manifest = json.decodeFromString<ModuleManifest>(text)
            require(manifest.id == plugin.id && manifest.version == plugin.version && manifest.type == artifact.runtime) {
                "Provider package identity/version/runtime does not match the catalog"
            }
            require(manifest.resolvedApiVersion == plugin.apiVersion &&
                manifest.resolvedMinHostApiVersion == plugin.minHostApiVersion
            ) { "Provider package API does not match the catalog" }
            if (manifest.type == "http") {
                val uri = URI(manifest.entryPoint)
                require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                    "Invalid provider service URL"
                }
            } else {
                requireSafePluginPath(manifest.entryPoint)
                // Online packages deliberately require an explicit entry, not legacy filename guessing.
                val abi = when (artifact.arch) {
                    "x64" -> "x86_64"
                    "arm64" -> "arm64-v8a"
                    "armv7" -> "armeabi-v7a"
                    else -> error("Unknown native architecture")
                }
                require(listOf(manifest.entryPoint, "lib/$abi/${manifest.entryPoint}").any {
                    zip.getEntry(it)?.isDirectory == false
                }) { "Native entry is missing; use an exact root or lib/ABI entry filename" }
            }
        }
    }
}

private fun requireSafePluginPath(path: String) {
    val reserved = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }
    require(path.isNotEmpty() && path.length <= 512 && !path.startsWith('/') && '\\' !in path && ':' !in path) {
        "Unsafe archive path"
    }
    require(path.split('/').all {
        it.isNotEmpty() && it != "." && it != ".." && !it.endsWith('.') && !it.endsWith(' ') &&
            it.substringBefore('.').lowercase() !in reserved && it.none { c -> c.code < 32 }
    }) { "Unsafe archive path" }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

actual fun createPluginRepositoryIO(context: PlatformContext): PluginRepositoryIO =
    JvmPluginRepositoryIO(File(PlatformSupport.dataDir(context), "plugin-repository"))

actual fun currentPluginTarget(): PluginTarget {
    val os = System.getProperty("os.name", "").orEmpty().lowercase()
    val arch = if (PlatformInfo.isAndroid) PlatformInfo.supportedAbis.firstOrNull().orEmpty().lowercase()
        else System.getProperty("os.arch", "").orEmpty().lowercase()
    return PluginTarget(
        platform = when {
            PlatformInfo.isAndroid -> "android"
            os.contains("win") -> "windows"
            os.contains("mac") -> "macos"
            else -> "linux"
        },
        arch = when (arch) {
            "amd64", "x86_64", "x64" -> "x64"
            "aarch64", "arm64", "arm64-v8a" -> "arm64"
            "arm", "armv7l", "armeabi-v7a" -> "armv7"
            else -> arch
        },
    )
}
