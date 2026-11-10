package cp.player.core.playback

import cp.player.core.api.AmllTtmlClient
import cp.player.core.api.LyricsSourceMode
import cp.player.core.lyrics.SmartKaraokeLrcSynthesizer
import cp.player.core.model.LyricsInfo
import cp.player.core.music.CPMediaId
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 歌词拉取、解析与智能逐字合成协同组件。
 *
 * 职责：
 * 1. 组合统一来源体系（[cp.player.core.lyrics.LyricsSourceRegistry]）或旧回退链（AMLL / 音源 API / 插件），
 *    自动拉取并解析当前曲目的歌词。
 * 2. 支持本地音频同级边车歌词（.lrc/.ttml/.elrc）与内嵌 ID3/FLAC 标签歌词的高优先级命中。
 * 3. 智能逐字歌词（SmartKaraoke）的异步补全与 LRU 缓存。
 * 4. 歌词高亮索引的高性能二分查找计算（[computeLyricIndex]）。
 */
internal class PlaybackLyricsLoader(
    private val source: UnifiedMusicSource,
    private val amllClient: AmllTtmlClient? = null,
    private val lyricsSourceMode: () -> LyricsSourceMode = { LyricsSourceMode.AMLL_FIRST },
    private val lyricsPluginService: cp.player.core.lyricsplugin.LyricsPluginService? = null,
    private val lyricsSourceRegistry: cp.player.core.lyrics.LyricsSourceRegistry? = null,
    private val downloadedFileResolver: (mediaId: String) -> String? = { null },
) {
    var smartKaraokeLrcEnabled: Boolean = false

    private val karaokeCacheMutex = Mutex()
    private val karaokeCache = object : LinkedHashMap<String, List<SyncedLyricLine>>(
        /* initialCapacity = */ 8, /* loadFactor = */ 0.75f, /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SyncedLyricLine>>): Boolean =
            size > 8
    }

    /**
     * 为指定曲目获取歌词与格式元信息。
     */
    suspend fun loadLyrics(
        mediaId: String,
        summary: TrackSummary?,
    ): Pair<LyricsState, LyricsInfo?> {
        val id = CPMediaId.parse(mediaId)
        val outcome = fetchLyricsForInternal(id, summary)
        return applySmartKaraokeIfEnabled(outcome)
    }

    private suspend fun fetchLyricsForInternal(
        id: CPMediaId,
        summary: TrackSummary?,
    ): Pair<LyricsState, LyricsInfo?> {
        val downloadedPath = downloadedFileResolver(id.toString())
        if (downloadedPath != null) {
            SidecarLyrics.load(downloadedPath)?.let { return it }
        }
        // 统一来源体系：顺序由用户排序决定，首个命中即胜出。
        lyricsSourceRegistry?.let { registry ->
            val engine = cp.player.core.lyrics.LyricsEngine(registry)
            val origId = summary?.originalMediaId?.let {
                runCatching { CPMediaId.parse(it) }.getOrNull()
            }
            val request = cp.player.core.lyrics.LyricsRequest(
                mediaId = id,
                originalMediaId = origId,
                title = summary?.name.orEmpty(),
                artist = summary?.artist.orEmpty(),
                album = summary?.album.orEmpty(),
                durationMs = summary?.durationMs ?: 0L,
                isLocal = id.providerId == "local",
                localFilePath = downloadedPath,
            )
            return engine.resolveToState(request)
        }
        return fetchLyricsForLegacy(id, summary)
    }

    /**
     * 旧回退链（无注册表时的兜底路径，行为与改造前逐字一致）。
     *
     * 保留它的理由：既有测试与最小装配不注入注册表，若直接删掉这条路径，
     * 那些测试会在「没有来源可用」的空实现上全绿 —— 绿得没有意义。
     * 组合根（MusicBackend）总是注入注册表，生产路径优先走上面。
     */
    private suspend fun fetchLyricsForLegacy(
        id: CPMediaId,
        summary: TrackSummary?,
    ): Pair<LyricsState, LyricsInfo?> {
        val mode = lyricsSourceMode()
        val isLocal = id.providerId == "local"
        val downloadedPath = downloadedFileResolver(id.toString())
        val localAudioPath = if (isLocal) id.resourceId else downloadedPath
        // 本地歌曲/已下载歌曲先看边车歌词（同目录同名 .lrc/.ttml/.elrc）
        if (localAudioPath != null) {
            SidecarLyrics.load(localAudioPath)?.let { return it }
            // 内嵌歌词次之
            EmbeddedLyricLoader.load(localAudioPath)?.let { hit ->
                val lines = hit.first
                val hasWords = lines.any { it.words.isNotEmpty() }
                return LyricsState.Success(lines) to LyricsInfo(
                    source = "Embedded",
                    format = if (hasWords) "Embedded (Karaoke)" else "Embedded",
                    hasWordLevel = hasWords,
                    hasTranslation = lines.any { !it.translation.isNullOrBlank() },
                    hasPhonetic = lines.any { !it.romanization.isNullOrBlank() },
                )
            }
        }
        val origId = summary?.originalMediaId?.let {
            runCatching { CPMediaId.parse(it) }.getOrNull()
        }
        val effectiveMediaId = if (isLocal) origId else id
        if (mode != LyricsSourceMode.PROVIDER_ONLY && amllClient != null) {
            val targetForAmll = effectiveMediaId ?: id
            val allowLookup = targetForAmll.providerId != "local"
            val amll = fetchFromAmll(targetForAmll, summary, allowPlatformLookup = allowLookup)
            if (amll != null) return amll
            if (mode == LyricsSourceMode.AMLL_ONLY) {
                return fetchFromPlugins(summary)
                    ?: (LyricsState.NoLyrics to LyricsInfo(source = "AMLL TTML", format = "N/A"))
            }
            if (isLocal && effectiveMediaId == null) return fetchFromPlugins(summary) ?: (LyricsState.NoLyrics to null)
        } else if (isLocal && effectiveMediaId == null) {
            return fetchFromPlugins(summary) ?: (LyricsState.NoLyrics to null)
        }
        if (effectiveMediaId == null || effectiveMediaId.providerId == "local") {
            return fetchFromPlugins(summary) ?: (LyricsState.NoLyrics to null)
        }
        val lyrics = when (val result = source.getLyrics(effectiveMediaId.toString())) {
            is cp.player.core.BackendResult.Success -> result.data
            is cp.player.core.BackendResult.Error,
            is cp.player.core.BackendResult.Unsupported -> null
        }
        val lines = lyrics?.let { LyricsParser.parseTrackLyrics(it, summary?.durationMs ?: 0L) }.orEmpty()
        val info = lyrics?.let { extractLyricsInfo(it, lines) }
        // 音源也没词时，最后尝试用户启用的歌词源插件。
        if (lines.isEmpty()) {
            fetchFromPlugins(summary)?.let { return it }
        }
        val state = if (lines.isEmpty()) LyricsState.NoLyrics else LyricsState.Success(lines)
        return state to info
    }

    private suspend fun fetchFromPlugins(summary: TrackSummary?): Pair<LyricsState, LyricsInfo?>? {
        val service = lyricsPluginService ?: return null
        val title = summary?.name ?: return null
        val outcome = runCatching {
            @Suppress("DEPRECATION")
            service.fetchLyrics(
                title = title,
                artist = summary.artist.orEmpty(),
                album = summary.album.orEmpty(),
                durationMs = summary.durationMs,
                mediaId = summary.originalMediaId ?: summary.id,
            )
        }.getOrNull() ?: return null
        if (outcome.lines.isEmpty()) return null
        return LyricsState.Success(outcome.lines) to LyricsInfo(
            source = outcome.sourceName,
            format = if (outcome.hasWordLevel) "Plugin (Karaoke)" else "Plugin",
            hasWordLevel = outcome.hasWordLevel,
            hasTranslation = outcome.lines.any { !it.translation.isNullOrBlank() },
            hasPhonetic = outcome.lines.any { !it.romanization.isNullOrBlank() },
        )
    }

    private suspend fun fetchFromAmll(
        id: CPMediaId,
        summary: TrackSummary?,
        allowPlatformLookup: Boolean,
    ): Pair<LyricsState, LyricsInfo>? {
        val sanitized = cp.player.core.util.MusicSearchSanitizer.sanitize(summary?.name.orEmpty(), summary?.artist.orEmpty())
        val ttml = amllClient?.fetchLyricsTtml(
            providerId = id.providerId.takeIf { allowPlatformLookup },
            songId = id.resourceId.takeIf { allowPlatformLookup },
            name = sanitized.title.ifBlank { null },
            artist = sanitized.artist.ifBlank { null },
            album = summary?.album?.ifBlank { null },
        ) ?: return null
        val lines = LyricsParser.parseTtml(ttml)
        if (lines.isEmpty()) return null
        val hasWords = lines.any { it.words.isNotEmpty() }
        val info = LyricsInfo(
            source = "AMLL TTML",
            format = if (hasWords) "TTML (Karaoke)" else "TTML",
            hasWordLevel = hasWords,
            hasTranslation = lines.any { !it.translation.isNullOrBlank() },
            hasPhonetic = lines.any { !it.romanization.isNullOrBlank() },
        )
        return LyricsState.Success(lines) to info
    }

    private suspend fun applySmartKaraokeIfEnabled(
        result: Pair<LyricsState, LyricsInfo?>,
    ): Pair<LyricsState, LyricsInfo?> {
        if (!smartKaraokeLrcEnabled) return result
        val (state, info) = result
        if (state !is LyricsState.Success) return result
        val lines = state.lines
        if (lines.isEmpty()) return result
        if (lines.all { it.words.isNotEmpty() }) return result

        val key = "${state.hashCode()}#${lines.size}#${lines.first().time}#${lines.last().time}#${lines.hashCode()}"
        val synthesized = withContext(Dispatchers.Default) {
            karaokeCacheMutex.withLock { karaokeCache[key] } ?: run {
                val computed = SmartKaraokeLrcSynthesizer.synthesize(lines)
                karaokeCacheMutex.withLock { karaokeCache[key] = computed }
                computed
            }
        }
        val addedWordLevel = synthesized.count { it.words.isNotEmpty() } > lines.count { it.words.isNotEmpty() }
        val updatedInfo = info?.copy(
            format = if (addedWordLevel) {
                if (info.format.isNotBlank()) "${info.format} (智能逐字 Beta)" else "智能逐字 (Beta)"
            } else {
                info.format
            },
            hasWordLevel = info.hasWordLevel || addedWordLevel,
        )
        return LyricsState.Success(synthesized) to updatedInfo
    }

    private fun extractLyricsInfo(
        lyrics: cp.player.core.music.TrackLyrics,
        lines: List<SyncedLyricLine>,
    ): LyricsInfo {
        val format = when {
            !lyrics.ttml.isNullOrBlank() -> "TTML"
            !lyrics.yrc.isNullOrBlank() -> "YRC"
            !lyrics.lrc.isNullOrBlank() -> "LRC"
            else -> "Unknown"
        }
        val hasWordLevel = lines.any { it.words.isNotEmpty() }
        val hasTranslation = lines.any { !it.translation.isNullOrBlank() } || !lyrics.translation.isNullOrBlank()
        val hasPhonetic = lines.any { !it.romanization.isNullOrBlank() } || !lyrics.romanization.isNullOrBlank()
        return LyricsInfo(
            source = "KMP SDK",
            format = format,
            hasWordLevel = hasWordLevel,
            hasTranslation = hasTranslation,
            hasPhonetic = hasPhonetic,
        )
    }

    companion object {
        /**
         * 计算当前播放毫秒位置对应的歌词行下标。
         *
         * 采用二分查找 (O(log n))，在频繁的位置轮询（每秒多次）下保证极低 CPU 开销。
         */
        fun computeLyricIndex(lyrics: LyricsState, pos: Long): Int {
            val lines = (lyrics as? LyricsState.Success)?.lines ?: return -1
            if (lines.isEmpty()) return -1
            var low = 0
            var high = lines.lastIndex
            var idx = -1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (lines[mid].time <= pos) {
                    idx = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return idx
        }
    }
}
