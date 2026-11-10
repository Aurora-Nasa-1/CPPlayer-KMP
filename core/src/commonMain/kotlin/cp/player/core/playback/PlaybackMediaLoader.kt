package cp.player.core.playback

import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.util.PlatformSupport
import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放媒体装载、格式解析、网络/本地回退、流缓存与预加载协同管理组件。
 *
 * 职责：
 * 1. 负责当前曲目媒体流/本地文件的解析与装载（[playCurrent]），含格式探测与异常降级重试。
 * 2. 负责无损音频流的后台离线落盘与首次拖拽时的无缝切源（[startBackgroundLocalize] / [switchToLocal]）。
 * 3. 负责装载期间的用户拖拽暂存（[deferredSeek]）与恢复播放断点接管（[restoredResume]）。
 * 4. 负责下一首歌曲的元数据、歌词与音频流后台预加载（[triggerPreloadNext]）。
 */
internal class PlaybackMediaLoader(
    private val platform: PlatformPlayer,
    private val source: UnifiedMusicSource,
    private val streamLocalizer: StreamLocalizer,
    private val lyricsLoader: PlaybackLyricsLoader,
    private val fadeController: PlaybackFadeController,
    private val favoritesManager: PlaybackFavoritesManager,
    private val scrobbler: PlaybackScrobbler,
    private val playbackModeSettings: SettingsStorage?,
    private val cacheNamespace: () -> String,
    private val downloadedFileResolver: (mediaId: String) -> String?,
    private val downloadedTrackResolver: (mediaId: String) -> TrackSummary?,
    private val scope: CoroutineScope,
    private val getCurrentEntry: () -> PlaybackQueueManager.Entry?,
    private val getCurrentIndex: () -> Int,
    private val getSourceId: () -> String?,
    private val getNextIndex: suspend (autoAdvance: Boolean) -> Int?,
    private val getEntryAt: (index: Int) -> PlaybackQueueManager.Entry?,
    private val resolveEntry: suspend (index: Int) -> Unit,
    private val getState: () -> PlaybackUiState,
    private val updateState: ((PlaybackUiState) -> PlaybackUiState) -> Unit,
    private val refreshLyrics: suspend () -> Unit,
    private val persistSession: () -> Unit,
) {
    var qualityLevel: String = "exhigh"
        private set

    var preloadNextEnabled: Boolean = playbackModeSettings?.let {
        PlaybackSessionSettings.preloadNextEnabled(it)
    } ?: PlaybackSessionSettings.DEFAULT_PRELOAD_NEXT
        private set

    @Volatile var engineReady = false
        private set

    @Volatile var playingMediaId: String? = null
        private set

    @Volatile private var deferredSeek: Pair<String, Long>? = null
    @Volatile private var restoredResume: Pair<String, Long>? = null
    @Volatile private var localized: Pair<String, String>? = null
    @Volatile private var playingFromLocal = false

    private var loadJob: Job? = null
    private var localizeJob: Job? = null
    private var preloadJob: Job? = null
    @Volatile private var preloadedKey: String? = null
    private var loadGeneration = 0

    @Volatile private var lastHeaders: Map<String, String> = emptyMap()
    @Volatile private var lastMetadata: PlaybackMetadata? = null

    fun setRestoredResume(mediaId: String, positionMs: Long) {
        restoredResume = mediaId to positionMs
    }

    fun clearRestoredResume() {
        restoredResume = null
    }

    fun shouldIgnoreEnginePosition(currentMediaId: String?): Boolean {
        if (deferredSeek != null) return true
        if (!engineReady && restoredResume?.first == currentMediaId) return true
        return false
    }

    fun setQuality(level: String) {
        if (level.isBlank()) return
        if (qualityLevel == level) return
        qualityLevel = level
        updateState { it.copy(qualityLevel = level) }
        preloadedKey = null
        triggerPreloadNext()
    }

    fun setPreloadNext(enabled: Boolean) {
        preloadNextEnabled = enabled
        playbackModeSettings?.putString(PlaybackSessionSettings.KEY_PRELOAD_NEXT, enabled.toString())
        if (!enabled) {
            preloadJob?.cancel()
            preloadJob = null
            preloadedKey = null
        } else {
            triggerPreloadNext()
        }
    }

    fun preloadNext() {
        triggerPreloadNext()
    }

    fun onQueueReplaced() {
        loadJob?.cancel()
        loadGeneration++
        restoredResume = null
    }

    fun onQueueCleared() {
        preloadJob?.cancel()
        preloadedKey = null
        loadJob?.cancel()
        loadGeneration++
        engineReady = false
        deferredSeek = null
        playingMediaId = null
        localizeJob?.cancel()
        localized = null
        playingFromLocal = false
    }

    fun onCurrentRemoved() {
        loadJob?.cancel()
        loadGeneration++
        engineReady = false
        deferredSeek = null
        playingMediaId = null
    }

    fun onNativeTrackStarted(mediaId: String, summary: TrackSummary?) {
        fadeController.onTrackPreparing()
        loadJob?.cancel()
        preloadJob?.cancel()
        scrobbler.flush()
        scrobbler.resetCounts()
        scrobbler.setActiveTrack(mediaId, summary, getSourceId() ?: summary?.album ?: "0")
        playingMediaId = mediaId
        preloadedKey = null
        engineReady = true
        deferredSeek = null
        restoredResume = null
        localized = null
        playingFromLocal = false
        loadGeneration++
        triggerPreloadNext()
    }

    fun cancelAll() {
        loadJob?.cancel()
        preloadJob?.cancel()
        localizeJob?.cancel()
        engineReady = false
        deferredSeek = null
    }

    fun requestSeek(positionMs: Long) {
        val mediaId = getCurrentEntry()?.mediaId ?: return
        val target = clampSeek(positionMs)
        if (!engineReady) {
            deferredSeek = mediaId to target
            updateState { it.copy(positionMs = target, activeLyricIndex = computeLyricIndex(it.lyrics, target)) }
            return
        }
        val local = localized?.takeIf { it.first == mediaId }?.second
        // 引擎能直接定位 http(s) 流时，不要再为了「让拖动生效」而把播放源切成本地副本：
        // 那会重新 load 一次、打断正在播的流并产生咔哒。切本地是旧引擎
        // （FLAC over HTTP 无法 seek）时代的兜底，现在只在它确实做不到时启用。
        if (local != null && !playingFromLocal && !platform.supportsSeekableRemoteStreams) {
            playingFromLocal = true
            engineReady = false
            deferredSeek = null
            updateState { it.copy(positionMs = target, activeLyricIndex = computeLyricIndex(it.lyrics, target)) }
            scope.launch { switchToLocal(mediaId, local, target) }
            return
        }
        deferredSeek = null
        platform.seekTo(target)
    }

    private suspend fun switchToLocal(mediaId: String, path: String, target: Long) {
        val gen = loadGeneration
        try {
            platform.load(
                path,
                startPositionMs = target,
                headers = lastHeaders,
                metadata = lastMetadata,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            if (loadGeneration != gen) return
            playingFromLocal = false
            localized = null
            engineReady = true
            platform.seekTo(target)
            return
        }
        if (loadGeneration != gen) return
        engineReady = true
        consumeDeferredSeek(mediaId)?.let { platform.seekTo(it) }
    }

    fun clampSeek(positionMs: Long): Long {
        val target = positionMs.coerceAtLeast(0L)
        val duration = effectiveDurationMs()
        return if (duration > 0L) target.coerceIn(0L, duration) else target
    }

    fun effectiveDurationMs(): Long =
        getState().durationMs.takeIf { it > 0L }
            ?: getState().currentTrack?.durationMs?.takeIf { it > 0L }
            ?: 0L

    fun consumeDeferredSeek(mediaId: String): Long? {
        val pending = deferredSeek
        deferredSeek = null
        return pending?.takeIf { it.first == mediaId }?.second
    }

    fun triggerPreloadNext() {
        if (!preloadNextEnabled) return
        val currentGen = loadGeneration
        preloadJob?.cancel()
        preloadJob = scope.launch {
            delay(1200)
            if (loadGeneration != currentGen) return@launch

            val nextIndex = getNextIndex(true) ?: return@launch
            if (nextIndex == getCurrentIndex()) return@launch
            val targetEntry = getEntryAt(nextIndex) ?: return@launch
            val nextMediaId = targetEntry.mediaId
            val requestedLevel = qualityLevel
            val namespace = cacheNamespaceOf(nextMediaId)
            val requestKey = cacheKeyOf(nextMediaId, requestedLevel, namespace)
            fun isCurrentRequest() = loadGeneration == currentGen &&
                qualityLevel == requestedLevel && cacheNamespaceOf(nextMediaId) == namespace
            if (preloadedKey == requestKey) return@launch

            if (targetEntry.summary == null) {
                resolveEntry(nextIndex)
            }
            val summary = targetEntry.summary
                ?: source.getTrackDetail(nextMediaId).getOrNull()?.also { targetEntry.summary = it }

            if (!isCurrentRequest()) return@launch

            runCatching {
                lyricsLoader.loadLyrics(nextMediaId, targetEntry.summary)
            }

            if (!isCurrentRequest()) return@launch

            val downloadedNext = downloadedFileResolver(nextMediaId)
            if (downloadedNext != null) {
                if (platform.supportsGaplessQueue) {
                    platform.queueNext(nextMediaId, downloadedNext, emptyMap())
                }
                preloadedKey = requestKey
                return@launch
            }

            val urlResult = source.preloadTrack(nextMediaId, level = requestedLevel)
            if (!isCurrentRequest()) return@launch
            val songUrl = urlResult.getOrNull() ?: return@launch
            if (songUrl.url.isBlank()) return@launch
            val actualLevel = songUrl.level.ifBlank { requestedLevel }
            val cacheKey = cacheKeyOf(nextMediaId, actualLevel, namespace)

            val headers = buildMap {
                songUrl.headers?.let { putAll(it) }
                songUrl.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            }

            if (!isCurrentRequest()) return@launch

            if (platform.supportsGaplessQueue) {
                val cacheKey = cacheKeyOf(nextMediaId, actualLevel, namespace)
                val cached = downloadedNext ?: streamLocalizer.cachedPath(cacheKey)
                platform.queueNext(nextMediaId, cached ?: songUrl.url, headers)
                preloadedKey = requestKey
                return@launch
            }

            val localizing = streamLocalizer.isLocalizing(actualLevel)
            if (localizing && streamLocalizer.cachedPath(cacheKey) == null) {
                val meta = summary?.let { SongCacheMeta(title = it.name, artist = it.artist) }
                streamLocalizer.preload(
                    url = songUrl.url,
                    cacheKey = cacheKey,
                    headers = headers,
                    meta = meta,
                )
            }

            if (isCurrentRequest()) {
                preloadedKey = requestKey
            }
        }
    }

    suspend fun playCurrent(advanceNavSeq: () -> Unit = {}) {
        val entry = getCurrentEntry() ?: return
        fadeController.onTrackPreparing()
        advanceNavSeq()
        engineReady = false
        loadJob?.cancel()
        preloadJob?.cancel()
        scrobbler.flush()
        scrobbler.resetCounts()
        scrobbler.setActiveTrack(entry.mediaId, entry.summary, getSourceId() ?: entry.summary?.album ?: "0")
        val previousMediaId = playingMediaId
        playingMediaId = entry.mediaId
        val gen = ++loadGeneration
        loadJob = scope.launch {
            fun emit(u: PlaybackUiState) { if (loadGeneration == gen) updateState { u } }

            val mediaId = entry.mediaId
            val requestedLevel = qualityLevel
            val namespace = cacheNamespaceOf(mediaId)
            val downloadedSummary = downloadedTrackResolver(mediaId)
            if (entry.summary == null && downloadedSummary != null) {
                entry.summary = downloadedSummary
            } else if (entry.summary == null) {
                resolveEntry(getCurrentIndex())
            }
            var summary = entry.summary ?: downloadedSummary ?: source.getTrackDetail(mediaId).getOrNull()?.also { entry.summary = it }
            if (summary == null) {
                // findByMediaId 只查旁车索引；别在这里用 entries() —— 那会列目录 + 解析标签。
                summary = streamLocalizer.findByMediaId(mediaId, namespace)?.let { cacheEntry ->
                    TrackSummary(
                        id = mediaId,
                        name = cacheEntry.title?.ifBlank { null } ?: "离线曲目",
                        artist = cacheEntry.artist.orEmpty(),
                        album = "",
                        coverUrl = null,
                        durationMs = 0L,
                    ).also { entry.summary = it }
                }
            }
            scrobbler.updateActiveSummary(mediaId, summary, getSourceId())
            if (restartIfSourceChanged(mediaId, namespace, gen)) return@launch
            val currentLyrics = getState().lyrics
            val sameSong = previousMediaId != null &&
                previousMediaId == mediaId &&
                currentLyrics is LyricsState.Success
            emit(
                getState().copy(
                    currentTrack = summary,
                    currentIndex = getCurrentIndex(),
                    sourceId = getSourceId(),
                    lyrics = if (sameSong) currentLyrics else LyricsState.Loading,
                    activeLyricIndex = if (sameSong) computeLyricIndex(currentLyrics, getState().positionMs) else -1,
                    durationMs = summary?.durationMs?.takeIf { d -> d > 0 } ?: 0L,
                    positionMs = if (sameSong) getState().positionMs else 0L,
                    error = if (summary == null) "无法获取曲目信息" else null,
                    isBuffering = summary != null,
                    isLocalizing = false,
                )
            )
            if (!sameSong) {
                refreshLyrics()
            }
            if (summary == null) {
                deferredSeek = null
                platform.stop()
                playingMediaId = null
                return@launch
            }
            favoritesManager.ensureFavoritesLoaded()
            emit(getState().copy(
                isFavorite = favoritesManager.isFavorite(mediaId),
            ))
            val downloadedLocal = downloadedFileResolver(mediaId)
            val finalSongUrl: SongUrl = if (downloadedLocal != null) {
                val ext = downloadedLocal.substringAfterLast('.', "").lowercase()
                val inferredCodec = when (ext) {
                    "flac" -> "FLAC"
                    "wav" -> "WAV"
                    "mp3" -> "MP3"
                    "aac", "m4a" -> "AAC"
                    "ogg" -> "OGG"
                    else -> ext.uppercase().takeIf { it.isNotBlank() } ?: "MP3"
                }
                val inferredLevel = when (inferredCodec) {
                    "FLAC", "WAV" -> "lossless"
                    else -> requestedLevel
                }
                SongUrl(
                    url = downloadedLocal,
                    level = inferredLevel,
                    sizeBytes = PlatformSupport.fileSize(downloadedLocal),
                    expireAt = null,
                    codec = inferredCodec,
                )
            } else {
                var urlResult = source.getSongUrl(mediaId, level = requestedLevel)
                if (restartIfSourceChanged(mediaId, namespace, gen)) return@launch
                var remoteSongUrl = urlResult.getOrNull()
                if ((remoteSongUrl == null || remoteSongUrl.url.isBlank()) && requestedLevel != "standard") {
                    val fallbackResult = source.getSongUrl(mediaId, level = "standard")
                    if (restartIfSourceChanged(mediaId, namespace, gen)) return@launch
                    val fallbackUrl = fallbackResult.getOrNull()
                    if (fallbackUrl != null && fallbackUrl.url.isNotBlank()) {
                        urlResult = fallbackResult
                        remoteSongUrl = fallbackUrl
                    }
                }
                if (remoteSongUrl != null && remoteSongUrl.url.isNotBlank()) {
                    remoteSongUrl
                } else {
                    val actualLevelBeforeFallback = remoteSongUrl?.level?.ifBlank { requestedLevel } ?: requestedLevel
                    val tempCacheKey = cacheKeyOf(mediaId, actualLevelBeforeFallback, namespace)
                    val cachedStream = streamLocalizer.cachedPath(tempCacheKey)
                        ?: streamLocalizer.findByMediaId(mediaId, namespace)?.let { streamLocalizer.entryPath(it.id) }
                    if (cachedStream != null) {
                        val ext = cachedStream.substringAfterLast('.', "").lowercase()
                        val inferredLevel = if (ext == "flac") "lossless" else "standard"
                        SongUrl(
                            url = cachedStream,
                            level = inferredLevel,
                            sizeBytes = PlatformSupport.fileSize(cachedStream),
                            expireAt = null,
                            codec = if (ext == "flac") "FLAC" else "MP3",
                        )
                    } else {
                        deferredSeek = null
                        platform.stop()
                        playingMediaId = null
                        val errMsg = (urlResult as? cp.player.core.BackendResult.Error)?.message
                        val hint = if (errMsg?.contains("Network", ignoreCase = true) == true ||
                            errMsg?.contains("Connect", ignoreCase = true) == true ||
                            errMsg?.contains("Timeout", ignoreCase = true) == true
                        ) "网络连接失败且无离线缓存" else (errMsg ?: "无法获取播放地址")
                        emit(getState().copy(
                            isBuffering = false,
                            error = hint,
                        ))
                        return@launch
                    }
                }
            }
            val actualLevel = finalSongUrl.level.ifBlank { requestedLevel }
            val resolvedFormat = AudioFormatInfo(
                codecName = finalSongUrl.codec?.uppercase() ?: when (actualLevel) {
                    "lossless", "hires", "jymaster" -> "FLAC"
                    else -> "MP3"
                },
                bitrate = finalSongUrl.bitrate ?: when (actualLevel) {
                    "standard" -> 128000
                    "higher" -> 192000
                    "exhigh" -> 320000
                    "lossless" -> 999000
                    else -> null
                },
                bitDepth = when (actualLevel) {
                    "hires", "jymaster" -> 24
                    "lossless" -> 16
                    else -> null
                },
                sampleRate = when (actualLevel) {
                    "hires" -> 96000
                    "lossless", "exhigh", "higher", "standard" -> 44100
                    else -> null
                },
            )
            emit(getState().copy(
                qualityLevel = actualLevel,
                formatInfo = platform.formatInfo.value ?: resolvedFormat,
                sourceQualityKey = finalSongUrl.qualityKey ?: actualLevel,
                sourceQualityLabel = finalSongUrl.qualityLabel,
            ))
            val headers = buildMap<String, String> {
                finalSongUrl.headers?.let { putAll(it) }
                finalSongUrl.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            }
            val resume = restoredResume
            restoredResume = null
            val startAt = consumeDeferredSeek(mediaId)
                ?: resume?.takeIf { it.first == mediaId }?.second
                ?: 0L
            val cacheKey = cacheKeyOf(mediaId, actualLevel, namespace)
            val isLocalFile = !finalSongUrl.url.startsWith("http://", ignoreCase = true) && !finalSongUrl.url.startsWith("https://", ignoreCase = true)
            val localizing = streamLocalizer.isLocalizing(actualLevel)
            val cachedLocal = if (isLocalFile) finalSongUrl.url else if (localizing) streamLocalizer.cachedPath(cacheKey) else null
            if (cachedLocal != null && !isLocalFile) {
                streamLocalizer.recordMetadata(cacheKey, SongCacheMeta(title = summary.name, artist = summary.artist))
            }
            localizeJob?.cancel()
            localized = cachedLocal?.let { mediaId to it }
            playingFromLocal = cachedLocal != null
            val metadata = PlaybackMetadata(
                id = mediaId,
                title = summary.name,
                artist = summary.artist,
                album = summary.album,
                coverUrl = summary.coverUrl,
                durationMs = summary.durationMs,
                cacheKey = cacheKey,
            )
            lastHeaders = headers
            lastMetadata = metadata
            try {
                if (restartIfSourceChanged(mediaId, namespace, gen)) return@launch
                val effectivePlayUrl = if (isLocalFile) finalSongUrl.url else (cachedLocal ?: finalSongUrl.url)
                platform.load(
                    effectivePlayUrl,
                    startPositionMs = startAt,
                    headers = headers,
                    metadata = metadata,
                )
                if (restartIfSourceChanged(mediaId, namespace, gen)) return@launch
                engineReady = true
                consumeDeferredSeek(mediaId)?.let { platform.seekTo(it) }
                platform.play()
                fadeController.startFadeIn()
                persistSession()
                // 直连流已经可 seek ⇒ 不必再为「拖动可用」偷偷把整首歌下到磁盘。
                // 省下的是无损单曲几十 MB 的流量与磁盘寿命，也避免 LRU 把别的缓存挤掉。
                if (localizing && cachedLocal == null && !isLocalFile &&
                    !platform.supportsSeekableRemoteStreams
                ) {
                    startBackgroundLocalize(
                        mediaId = mediaId,
                        cacheKey = cacheKey,
                        url = finalSongUrl.url,
                        headers = headers,
                        gen = gen,
                        meta = SongCacheMeta(title = summary.name, artist = summary.artist),
                        namespace = namespace,
                    )
                }
                triggerPreloadNext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (restartIfSourceChanged(mediaId, namespace, gen)) return@launch
                val msg = e.message ?: e.javaClass.simpleName
                if (actualLevel != "standard") {
                    val retried = retryWithStandardQuality(mediaId, gen, namespace)
                    if (retried) return@launch
                }
                deferredSeek = null
                platform.stop()
                playingMediaId = null
                emit(getState().copy(isBuffering = false, error = msg))
            }
        }
    }

    private fun startBackgroundLocalize(
        mediaId: String,
        cacheKey: String,
        url: String,
        headers: Map<String, String>,
        gen: Int,
        meta: SongCacheMeta?,
        namespace: String,
    ) {
        localizeJob?.cancel()
        val rawLevel = cacheKey.substringAfterLast('@', "")
        val isLossless = rawLevel.substringBefore('#') in StreamLocalizer.LOSSLESS_LEVELS
        if (isLossless) {
            updateState { it.copy(isLocalizing = true) }
        }
        localizeJob = scope.launch {
            if (loadGeneration != gen) return@launch
            if (cacheNamespaceOf(mediaId) != namespace) {
                if (isLossless) updateState { it.copy(isLocalizing = false) }
                return@launch
            }
            val path = streamLocalizer.localize(url, cacheKey, headers, meta)
            if (loadGeneration != gen) return@launch
            if (path != null && cacheNamespaceOf(mediaId) == namespace) localized = mediaId to path
            if (isLossless) updateState { it.copy(isLocalizing = false) }
        }
    }

    private suspend fun retryWithStandardQuality(mediaId: String, gen: Int, namespace: String): Boolean {
        updateState { it.copy(error = "音质降级中…") }
        return try {
            if (restartIfSourceChanged(mediaId, namespace, gen)) return true
            val result = source.getSongUrl(mediaId, level = "standard")
            if (restartIfSourceChanged(mediaId, namespace, gen)) return true
            val fallback = result.getOrNull() ?: return false
            if (fallback.url.isBlank()) return false
            val fallbackFormat = AudioFormatInfo(
                codecName = fallback.codec?.uppercase() ?: "MP3",
                bitrate = fallback.bitrate ?: 128000,
                sampleRate = 44100,
            )
            updateState { it.copy(qualityLevel = "standard", formatInfo = fallbackFormat) }
            val headers = buildMap {
                fallback.headers?.let { putAll(it) }
                fallback.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            }
            val summary = getCurrentEntry()?.summary
            if (restartIfSourceChanged(mediaId, namespace, gen)) return true
            platform.load(
                fallback.url,
                startPositionMs = 0L,
                headers = headers,
                metadata = summary?.let {
                    PlaybackMetadata(
                        id = mediaId,
                        title = it.name,
                        artist = it.artist,
                        album = it.album,
                        coverUrl = it.coverUrl,
                        durationMs = it.durationMs,
                        cacheKey = cacheKeyOf(mediaId, fallback.level.ifBlank { "standard" }, namespace),
                    )
                },
            )
            if (restartIfSourceChanged(mediaId, namespace, gen)) return true
            engineReady = true
            platform.play()
            fadeController.startFadeIn()
            refreshLyrics()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    private fun cacheNamespaceOf(mediaId: String): String =
        if (mediaId.startsWith("local://")) "" else cacheNamespace()

    private fun cacheKeyOf(mediaId: String, level: String, namespace: String = cacheNamespaceOf(mediaId)): String {
        return "$mediaId@$level" + if (namespace.isEmpty()) "" else "#${cp.player.core.util.stableHash64(namespace)}"
    }

    private suspend fun restartIfSourceChanged(mediaId: String, namespace: String, gen: Int): Boolean {
        if (loadGeneration != gen) return true
        if (cacheNamespaceOf(mediaId) == namespace) return false
        playCurrent()
        return true
    }

    private fun computeLyricIndex(lyrics: LyricsState, pos: Long): Int =
        PlaybackLyricsLoader.computeLyricIndex(lyrics, pos)

    private fun <T> cp.player.core.BackendResult<T>.getOrNull(): T? =
        (this as? cp.player.core.BackendResult.Success)?.data
}
