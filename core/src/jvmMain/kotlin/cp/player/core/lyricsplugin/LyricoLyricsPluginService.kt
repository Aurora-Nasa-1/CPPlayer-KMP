package cp.player.core.lyricsplugin

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
        val enabled = store.enabledIds()
        store.loadSources().map { source ->
            LyricsPluginSourceInfo(
                id = source.manifest.id,
                name = source.manifest.name,
                author = source.manifest.author,
                version = source.manifest.versionName.ifBlank { source.manifest.versionCode.toString() },
                description = source.manifest.description,
                capabilities = source.manifest.capabilities.ifEmpty { setOf(PluginCapability.SEARCH_SONGS) },
                enabled = source.manifest.id in enabled,
                bundled = source.bundled,
            )
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        store.setEnabled(id, enabled)
    }

    override suspend fun importPluginZip(zipPath: String): List<String> = withContext(Dispatchers.IO) {
        val file = File(zipPath)
        require(file.isFile) { "Plugin zip not found: $zipPath" }
        store.importZip(file)
    }

    override suspend fun deletePlugin(id: String): Boolean = withContext(Dispatchers.IO) {
        closeRuntime(id)
        store.deletePlugin(id)
    }

    override suspend fun pluginConfig(id: String): Map<String, String> = withContext(Dispatchers.IO) {
        store.pluginConfig(id)
    }

    override suspend fun setPluginConfigValue(id: String, key: String, value: String) = withContext(Dispatchers.IO) {
        store.setConfigValue(id, key, value)
    }

    override suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
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
                fetchFromSource(source, keyword, durationMs)
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                null
            }
            if (outcome != null) return@withContext outcome
        }
        null
    }

    private fun fetchFromSource(
        source: LyricoPluginSource,
        keyword: String,
        durationMs: Long,
    ): PluginLyricsOutcome? {
        val runtime = runtimeFor(source)
        val config = store.pluginConfig(source.manifest.id)

        val searchRequest = PluginSearchSongsRequest(keyword = keyword, page = 1, pageSize = 5, config = config)
        val hits = parser.parseSongResults(
            rawJson = runtime.call("searchSongs", pluginJson.encodeToString(searchRequest)),
            pluginId = source.manifest.id,
            pluginName = source.manifest.name,
        )
        if (hits.isEmpty()) return null

        // 优先挑时长最接近的候选，减少同名曲误配。
        val hit = if (durationMs > 0) {
            hits.minByOrNull { kotlin.math.abs(it.duration - durationMs) } ?: hits.first()
        } else {
            hits.first()
        }

        val lyricsRequest = PluginGetLyricsRequest(song = hit.toPluginSongRequest(), config = config)
        val raw = runtime.call(
            "getLyrics",
            pluginJson.encodeToString(lyricsRequest),
        )
        val lyrics = parser.parseLyrics(raw) ?: return null
        val lines: List<SyncedLyricLine> = LyricsPluginJsonParser.toSyncedLines(lyrics)
        if (lines.isEmpty()) return null

        return PluginLyricsOutcome(
            sourceId = source.manifest.id,
            sourceName = source.manifest.name,
            lines = lines,
            hasWordLevel = lines.any { it.words.isNotEmpty() },
        )
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
