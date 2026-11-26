package cp.player.core.lyricsplugin

import cp.player.core.lyrics.LyricsMatchScorer
import cp.player.core.playback.SyncedLyricLine
import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * 歌词源插件服务实现（jvmMain：Android 与桌面共用）。
 *
 * 运行 Lyrico 格式插件的完整链路：载入 → 求值 → `searchSongs` → `getLyrics` → 解析。
 * JS 运行时（Rhino）**非线程安全**，因此所有插件调用都收敛到一个专属单线程调度器上。
 */
class LyricoLyricsPluginService(
    rootDir: File,
    cacheDir: File?,
) : LyricsPluginService {

    private val store = FileLyricsPluginStore(rootDir)
    private val parser = LyricsPluginJsonParser()
    private val cacheDir = cacheDir

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "cpplayer-lyrics-plugin", 4L * 1024L * 1024L)
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    /** 每个插件一个常驻运行时（惰性创建，复用以免每次调用都重新求值脚本）。 */
    private val runtimes = mutableMapOf<String, RhinoPluginRuntime>()

    private fun runtimeFor(source: LyricoPluginSource): RhinoPluginRuntime =
        runtimes.getOrPut(source.manifest.id) {
            val hostApi = JvmPluginHostApi(
                pluginId = source.manifest.id,
                cacheRootDir = cacheDir?.let { File(it, source.manifest.id) },
            )
            RhinoPluginRuntime(hostApi).also { it.eval(source.script, source.manifest.entry) }
        }

    override suspend fun listSources(): List<LyricsPluginSourceInfo> = withContext(Dispatchers.IO) {
        listSourcesSync()
    }

    override fun listSourcesSync(): List<LyricsPluginSourceInfo> {
        val enabled = store.enabledIds()
        return store.loadSources().map { source ->
            LyricsPluginSourceInfo(
                id = source.manifest.id,
                name = source.manifest.name,
                author = source.manifest.author,
                version = source.manifest.versionName.ifBlank { source.manifest.versionCode.toString() },
                description = source.manifest.description,
                capabilities = source.manifest.capabilities.ifEmpty { setOf(PluginCapability.SEARCH_SONGS) },
                enabled = source.manifest.id in enabled,
                bundled = source.bundled,
                configFields = source.manifest.configFields,
                versionCode = source.manifest.versionCode,
            )
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        store.setEnabled(id, enabled)
    }

    override suspend fun importPluginZip(zipPath: String): List<String> = withContext(dispatcher) {
        val file = File(zipPath)
        require(file.isFile) { "Plugin zip not found: $zipPath" }
        // Keep file replacement and runtime invalidation on the same thread as script execution.
        store.importZip(file).also { ids -> ids.forEach(::closeRuntime) }
    }

    override suspend fun deletePlugin(id: String): Boolean = withContext(dispatcher) {
        closeRuntime(id)
        store.deletePlugin(id)
    }

    override suspend fun pluginConfig(id: String): Map<String, String> = withContext(Dispatchers.IO) {
        store.pluginConfig(id)
    }

    override suspend fun setPluginConfigValue(id: String, key: String, value: String) = withContext(Dispatchers.IO) {
        store.setConfigValue(id, key, value)
    }

    @Deprecated("Use fetchLyricsFrom with an explicit source id instead.")
    override suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        mediaId: String?,
    ): PluginLyricsOutcome? = withContext(dispatcher) {
        if (title.isBlank()) return@withContext null
        val enabledIds = store.enabledIds()
        if (enabledIds.isEmpty()) return@withContext null

        val sources = store.loadSources().filter { it.manifest.id in enabledIds }
        val keyword = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")

        for (source in sources) {
            val capabilities = source.manifest.capabilities.ifEmpty { setOf(PluginCapability.SEARCH_SONGS) }
            if (PluginCapability.GET_LYRICS !in capabilities) continue
            val outcome = runCatching {
                fetchFromSource(source, title, artist, album, durationMs, mediaId)
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                null
            }
            if (outcome != null) return@withContext outcome
        }
        null
    }

    /**
     * 指定插件取词 —— 统一来源体系的入口。
     *
     * 与 [fetchLyrics] 的差别：**只试一个插件**，不再内部遍历。
     * 顺序由 `LyricsSourceRegistry` 决定，插件服务回归「执行单个插件」的单一职责。
     */
    override suspend fun fetchLyricsFrom(
        id: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        mediaId: String?,
    ): PluginLyricsOutcome? = withContext(dispatcher) {
        if (title.isBlank()) return@withContext null
        val source = store.loadSources().firstOrNull { it.manifest.id == id } ?: return@withContext null
        val capabilities = source.manifest.capabilities.ifEmpty { setOf(PluginCapability.SEARCH_SONGS) }
        if (PluginCapability.GET_LYRICS !in capabilities) return@withContext null
        runCatching { fetchFromSource(source, title, artist, album, durationMs, mediaId) }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            null
        }
    }

    private fun fetchFromSource(
        source: LyricoPluginSource,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        mediaId: String? = null,
    ): PluginLyricsOutcome? {
        val runtime = runtimeFor(source)
        val config = store.pluginConfig(source.manifest.id)
        val target = LyricsMatchScorer.Target(
            title = title,
            artist = artist.takeIf { it.isNotBlank() },
            album = album.takeIf { it.isNotBlank() },
            durationMs = durationMs,
        )
        val searchesSongs = PluginCapability.SEARCH_SONGS in source.manifest.capabilities
        val hit = if (searchesSongs) {
            findSong(runtime, source, target, config, mediaId) ?: return null
        } else {
            null
        }
        val pluginId = source.manifest.id
        val song = hit?.toPluginSongRequest(mediaId = mediaId) ?: PluginSongRequest(
            id = "",
            title = title,
            artist = artist,
            album = album,
            duration = durationMs,
            sourceId = pluginId,
            pluginId = pluginId,
            mediaId = mediaId,
        )
        val request = PluginGetLyricsRequest(song = song, config = config, mediaId = mediaId)
        val raw = runtime.call("getLyrics", pluginJson.encodeToString(request))
        val lines: List<SyncedLyricLine> = if (searchesSongs) {
            LyricsPluginJsonParser.toSyncedLines(parser.parseLyrics(raw) ?: return null)
        } else {
            parser.parseLyricsCandidates(raw)
                .mapNotNull { candidate ->
                    val tags = candidate.tags
                    val candidateTitle = tags["ti"].orEmpty()
                    val score = if (candidateTitle.isBlank() && source.manifest.apiVersion < 4) {
                        // API 1–3 also allowed untagged lyrics and raw LRC strings.
                        0
                    } else {
                        LyricsMatchScorer.score(
                            target,
                            LyricsMatchScorer.Candidate(
                                titles = listOf(candidateTitle),
                                artists = listOf(tags["ar"].orEmpty()),
                                albums = listOf(tags["al"].orEmpty()),
                            ),
                        ).takeIf { it >= LyricsMatchScorer.MIN_ACCEPTABLE_SCORE } ?: return@mapNotNull null
                    }
                    score to candidate
                }
                .sortedByDescending { it.first }
                .firstNotNullOfOrNull { (_, candidate) ->
                    LyricsPluginJsonParser.toSyncedLines(candidate).takeIf { it.isNotEmpty() }
                }.orEmpty()
        }
        if (lines.isEmpty()) return null
        return PluginLyricsOutcome(
            sourceId = pluginId,
            sourceName = source.manifest.name,
            lines = lines,
            hasWordLevel = lines.any { it.words.isNotEmpty() },
            coverUrl = hit?.picUrl?.takeIf { it.isNotBlank() },
        )
    }

    private fun findSong(
        runtime: RhinoPluginRuntime,
        source: LyricoPluginSource,
        target: LyricsMatchScorer.Target,
        config: Map<String, String>,
        mediaId: String?,
    ): PluginSongSearchResult? {
        val rawKeyword = listOfNotNull(target.title, target.artist).joinToString(" ")
        val sanitized = cp.player.core.util.MusicSearchSanitizer.sanitize(rawKeyword)
        // Some indexes match single fields only. Keep the artist in the scorer when retrying by title.
        val candidateKeywords = listOf(sanitized.cleanKeyword, rawKeyword, target.title)
            .distinct().filter { it.isNotBlank() }

        for (kw in candidateKeywords) {
            val searchRequest = PluginSearchSongsRequest(
                keyword = kw,
                page = 1,
                pageSize = 10,
                config = config,
                mediaId = mediaId,
            )
            val hits = parser.parseSongResults(
                rawJson = runtime.call("searchSongs", pluginJson.encodeToString(searchRequest)),
                pluginId = source.manifest.id,
                pluginName = source.manifest.name,
            )
            val hit = LyricsMatchScorer.findBestMatch(target, hits) { song ->
                LyricsMatchScorer.Candidate(
                    titles = listOf(song.title),
                    artists = listOf(song.artist),
                    albums = listOf(song.album),
                    durationMs = song.duration,
                )
            }
            if (hit != null) return hit
        }
        return null
    }

    override fun close() {
        runtimes.values.forEach { runCatching { it.close() } }
        runtimes.clear()
        runCatching { dispatcher.close() }
        executor.shutdown()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private fun closeRuntime(id: String) {
        runtimes.remove(id)?.let { runCatching { it.close() } }
    }
}

actual fun createLyricsPluginService(context: PlatformContext, cacheDir: String?): LyricsPluginService {
    val root = File(PlatformSupport.dataDir(context), "lyrics-plugins")
    return LyricoLyricsPluginService(rootDir = root, cacheDir = cacheDir?.let(::File))
}
