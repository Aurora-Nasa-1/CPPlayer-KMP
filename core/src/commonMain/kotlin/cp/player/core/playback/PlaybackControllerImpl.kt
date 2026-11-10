package cp.player.core.playback

import cp.player.core.api.AmllTtmlClient
import cp.player.core.api.LyricsSourceMode
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [PlaybackController] 的平台无关实现。
 *
 * 核心编排职责：
 * 1. 组合与协调队列管理（[PlaybackQueueManager]）、媒体加载（[PlaybackMediaLoader]）、
 *    歌词装配（[PlaybackLyricsLoader]）、淡入淡出（[PlaybackFadeController]）、
 *    收藏管理（[PlaybackFavoritesManager]）、听歌打卡（[PlaybackScrobbler]）及持久化（[PlaybackSessionStore]）。
 * 2. 观察 [PlatformPlayer] 的播放状态、位置、时长并折入 [PlaybackUiState]。
 * 3. 对外投放歌词帧（[lyricPusher]）。
 */
class PlaybackControllerImpl(
    private val platform: PlatformPlayer,
    private val source: UnifiedMusicSource,
    private val scope: CoroutineScope,
    private val streamLocalizer: StreamLocalizer = NoOpStreamLocalizer,
    private val amllClient: AmllTtmlClient? = null,
    private val lyricsSourceMode: () -> LyricsSourceMode = { LyricsSourceMode.AMLL_FIRST },
    private val playbackModeSettings: SettingsStorage? = null,
    private val lyricsPluginService: cp.player.core.lyricsplugin.LyricsPluginService? = null,
    private val lyricsSourceRegistry: cp.player.core.lyrics.LyricsSourceRegistry? = null,
    private val fadeSettings: SettingsStorage? = null,
    private val lyricPusher: cp.player.core.lyricpush.LyricPusher? = null,
    private val cacheNamespace: () -> String = { "" },
    private val downloadedFileResolver: (mediaId: String) -> String? = { null },
    private val downloadedTrackResolver: (mediaId: String) -> TrackSummary? = { null },
) : PlaybackController {

    private val _state = MutableStateFlow(PlaybackUiState())
    override val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    override val seekFailures: kotlinx.coroutines.flow.SharedFlow<SeekFailure>
        get() = platform.seekFailures

    private val navMutex = Mutex()
    @Volatile private var navigationSeq = 0

    // ============ 协同组件 ============

    private val sessionStore = PlaybackSessionStore(playbackModeSettings, scope)

    private val lyricsLoader = PlaybackLyricsLoader(
        source = source,
        amllClient = amllClient,
        lyricsSourceMode = lyricsSourceMode,
        lyricsPluginService = lyricsPluginService,
        lyricsSourceRegistry = lyricsSourceRegistry,
        downloadedFileResolver = downloadedFileResolver,
    )

    private val favoritesManager = PlaybackFavoritesManager(source, scope) { isFav ->
        updateState { it.copy(isFavorite = isFav) }
    }

    private val scrobbler = PlaybackScrobbler(source, scope) {
        persistSession()
    }

    private val fadeController = PlaybackFadeController(platform, scope, fadeSettings)

    private val queueManager = PlaybackQueueManager(
        source = source,
        scope = scope,
        downloadedTrackResolver = downloadedTrackResolver,
    )

    private val mediaLoader = PlaybackMediaLoader(
        platform = platform,
        source = source,
        streamLocalizer = streamLocalizer,
        lyricsLoader = lyricsLoader,
        fadeController = fadeController,
        favoritesManager = favoritesManager,
        scrobbler = scrobbler,
        playbackModeSettings = playbackModeSettings,
        cacheNamespace = cacheNamespace,
        downloadedFileResolver = downloadedFileResolver,
        downloadedTrackResolver = downloadedTrackResolver,
        scope = scope,
        getCurrentEntry = { queueManager.currentEntry },
        getCurrentIndex = { queueManager.currentIndex },
        getSourceId = { queueManager.sourceId },
        getNextIndex = { autoAdvance -> navMutex.withLock { queueManager.computeNext(autoAdvance) } },
        getEntryAt = { idx -> queueManager.getEntry(idx) },
        resolveEntry = { idx -> resolveEntry(idx) },
        getState = { _state.value },
        updateState = { transform -> updateState(transform) },
        refreshLyrics = { refreshLyrics() },
        persistSession = { persistSession() },
    )

    private var lyricsJob: Job? = null
    @Volatile private var lyricsMediaId: String? = null

    // ============ 收藏状态 ============

    override val likedIds: StateFlow<Set<String>>
        get() = favoritesManager.likedIds

    // ============ 睡眠定时 ============

    private var sleepTimerJob: Job? = null
    private var sleepAfterTrack = false

    init {
        restorePlaybackModes()
        observePlatform()
        restoreLastSession()
        observeForLyricPush()
    }

    // ============ 歌词对外投放 ============

    private fun observeForLyricPush() {
        val pusher = lyricPusher ?: return
        _state
            .onEach { snapshot ->
                val lyrics = (snapshot.lyrics as? LyricsState.Success)?.lines.orEmpty()
                val track = snapshot.currentTrack?.let { summary ->
                    cp.player.core.lyricpush.LyricPushTrack(
                        id = summary.id,
                        title = summary.name,
                        artist = summary.artist,
                        album = summary.album.orEmpty(),
                        durationMs = summary.durationMs,
                        sourceId = snapshot.sourceId,
                    )
                }
                runCatching {
                    pusher.onFrame(
                        cp.player.core.lyricpush.LyricPushFrame(
                            track = track,
                            lines = lyrics,
                            lineIndex = snapshot.activeLyricIndex,
                            positionMs = snapshot.positionMs,
                            durationMs = snapshot.durationMs,
                            isPlaying = snapshot.isPlaying,
                        ),
                    )
                }
            }
            .launchIn(scope)
    }

    // ============ 播放模式与会话持久化 ============

    private fun restorePlaybackModes() {
        val (persistedRepeat, persistedShuffle) = sessionStore.restorePlaybackModes()
        queueManager.setModes(persistedRepeat, persistedShuffle)
        persistedRepeat?.let { repeat ->
            updateState { it.copy(repeatMode = repeat) }
        }
        persistedShuffle?.let { shuffle ->
            updateState { it.copy(shuffleEnabled = shuffle) }
        }
    }

    private fun persistPlaybackModes() {
        sessionStore.persistPlaybackModes(queueManager.repeatMode, queueManager.shuffleEnabled)
    }

    private fun currentSessionSnapshot(): PlaybackSessionStore.Session? {
        val idx = queueManager.currentIndex
        if (idx !in 0 until queueManager.size) return null
        return PlaybackSessionStore.Session(
            ids = queueManager.entries.map { it.mediaId },
            index = idx,
            sourceId = queueManager.sourceId,
            positionMs = _state.value.positionMs,
        )
    }

    private fun persistSession() {
        sessionStore.persistSession(::currentSessionSnapshot)
    }

    private fun persistSessionBlocking() {
        sessionStore.persistSessionBlocking(::currentSessionSnapshot)
    }

    private fun restoreLastSession() {
        val session = sessionStore.readSession() ?: return
        scope.launch {
            val restored = navMutex.withLock {
                val (ok, _) = queueManager.restoreSessionQueue(session.ids, session.index, session.sourceId)
                if (ok) {
                    val currentId = queueManager.currentEntry?.mediaId
                    if (currentId != null) {
                        mediaLoader.setRestoredResume(currentId, session.positionMs)
                    }
                }
                ok
            }
            if (!restored) return@launch
            updateState {
                it.copy(
                    sourceId = session.sourceId,
                    positionMs = session.positionMs,
                    activeLyricIndex = computeLyricIndex(it.lyrics, session.positionMs),
                )
            }
            pushQueueState()
            resolveQueueInBackground(startFrom = queueManager.currentIndex)
        }
    }

    // ============ 平台事件 fold 进 UI 状态 ============

    private fun observePlatform() {
        platform.state.onEach { ps ->
            val (playing, buffering, error, ended) = when (ps) {
                PlatformPlaybackState.Idle -> Quad(false, false, null, false)
                PlatformPlaybackState.Buffering -> Quad(false, true, null, false)
                PlatformPlaybackState.Ready -> Quad(false, false, null, false)
                PlatformPlaybackState.Playing -> Quad(true, false, null, false)
                PlatformPlaybackState.Paused -> Quad(false, false, null, false)
                PlatformPlaybackState.Ended -> Quad(false, false, null, true)
                is PlatformPlaybackState.Error -> Quad(false, false, ps.message, false)
            }
            updateState {
                it.copy(
                    isPlaying = playing,
                    isBuffering = buffering,
                    error = if (playing || ps == PlatformPlaybackState.Ready) null else (error ?: it.error),
                )
            }
            if (ended) onTrackEnded()
            if (playing) scrobbler.onPlaying()
            if (!playing) scrobbler.onPaused()
        }.launchIn(scope)

        platform.positionMs.onEach { pos ->
            val currentMediaId = queueManager.currentEntry?.mediaId
            if (mediaLoader.shouldIgnoreEnginePosition(currentMediaId)) return@onEach
            fadeController.maybeStartFadeOut(pos, currentDurationMs(), currentMediaId, mediaLoader.engineReady)
            updateState { it.copy(positionMs = pos, activeLyricIndex = computeLyricIndex(it.lyrics, pos)) }
        }.launchIn(scope)

        platform.durationMs.onEach { dur ->
            if (dur > 0L) updateState { it.copy(durationMs = dur) }
        }.launchIn(scope)

        platform.formatInfo.onEach { info ->
            if (info != null) {
                updateState { it.copy(formatInfo = info) }
            }
        }.launchIn(scope)
    }

    private data class Quad(val playing: Boolean, val buffering: Boolean, val error: String?, val ended: Boolean)

    private fun computeLyricIndex(lyrics: LyricsState, pos: Long): Int =
        PlaybackLyricsLoader.computeLyricIndex(lyrics, pos)

    // ============ 队列操作 ============

    override suspend fun playQueue(mediaIds: List<String>, startIndex: Int, sourceId: String?) {
        if (mediaIds.isEmpty()) return
        navMutex.withLock {
            queueManager.replaceQueue(mediaIds, startIndex, sourceId)
        }
        pushQueueState()
        resolveQueueInBackground(startFrom = queueManager.currentIndex)
        playCurrent()
    }

    override suspend fun setQueue(mediaIds: List<String>, startIndex: Int, sourceId: String?) {
        if (mediaIds.isEmpty()) return
        mediaLoader.onQueueReplaced()
        navMutex.withLock {
            queueManager.replaceQueue(mediaIds, startIndex, sourceId)
        }
        lyricsMediaId = null
        updateState { it.copy(lyrics = LyricsState.Loading, lyricsInfo = null, activeLyricIndex = -1) }
        pushQueueState()
        resolveQueueInBackground(startFrom = queueManager.currentIndex)
    }

    override suspend fun addToQueue(mediaId: String) {
        val added = navMutex.withLock {
            queueManager.addToQueue(mediaId)
        }
        if (!added) return
        pushQueueState()
        mediaLoader.triggerPreloadNext()
        val lastIndex = queueManager.size - 1
        scope.launch { resolveEntry(lastIndex) }
    }

    override suspend fun addNextToQueue(mediaId: String) {
        val insertedAt = navMutex.withLock {
            queueManager.addNextToQueue(mediaId)
        } ?: return
        pushQueueState()
        mediaLoader.triggerPreloadNext()
        scope.launch { resolveEntry(insertedAt) }
    }

    override suspend fun removeQueueItem(index: Int) {
        val res = navMutex.withLock {
            val result = queueManager.removeQueueItem(index) ?: return@withLock null
            if (result.isQueueEmpty) {
                mediaLoader.onCurrentRemoved()
                platform.stop()
                clearState()
            }
            result
        } ?: return
        pushQueueState()
        if (queueManager.isNotEmpty && res.wasCurrent && queueManager.currentIndex >= 0) {
            playCurrent()
        } else {
            mediaLoader.triggerPreloadNext()
        }
    }

    override fun clearQueue() {
        queueManager.cancel()
        mediaLoader.onQueueCleared()
        scope.launch {
            navMutex.withLock {
                queueManager.clear()
            }
            platform.stop()
            clearState()
            pushQueueState()
        }
    }

    override suspend fun moveQueueItem(from: Int, to: Int) {
        if (from == to) return
        navMutex.withLock {
            queueManager.moveQueueItem(from, to)
        }
        pushQueueState()
        mediaLoader.triggerPreloadNext()
    }

    override suspend fun playAt(index: Int) {
        val ok = navMutex.withLock {
            queueManager.playAt(index)
        }
        if (ok) playCurrent()
    }

    // ============ 播控 ============

    override fun togglePlayPause() {
        when (platform.state.value) {
            PlatformPlaybackState.Playing -> pause()
            PlatformPlaybackState.Paused, PlatformPlaybackState.Ready -> resume()
            PlatformPlaybackState.Ended, PlatformPlaybackState.Idle -> {
                if (queueManager.currentIndex in 0 until queueManager.size) {
                    scope.launch { playCurrent() }
                }
            }
            else -> {
                if (queueManager.currentIndex in 0 until queueManager.size) {
                    scope.launch { playCurrent() }
                }
            }
        }
    }

    override fun pause() {
        platform.pause()
        fadeController.setUserVolume(fadeController.userVolume)
        scope.launch { persistSession() }
    }

    override fun resume() {
        fadeController.setUserVolume(fadeController.userVolume)
        platform.play()
    }

    override fun seekTo(positionMs: Long) {
        mediaLoader.requestSeek(positionMs)
    }

    override fun skipNext() {
        val seqAtEntry = navigationSeq
        scope.launch {
            var next: Int? = null
            var stale = false
            navMutex.withLock {
                if (navigationSeq != seqAtEntry) { stale = true; return@withLock }
                next = queueManager.computeNext(autoAdvance = true)
                next?.let { queueManager.advanceTo(it) }
            }
            if (stale) return@launch
            val target = next
            if (target == null) {
                mediaLoader.onCurrentRemoved()
                platform.stop()
                updateState { it.copy(isPlaying = false, positionMs = 0L) }
                return@launch
            }
            pushQueueState()
            playCurrent()
        }
    }

    override fun skipPrevious() {
        val seqAtEntry = navigationSeq
        scope.launch {
            var prev: Int? = null
            var stale = false
            navMutex.withLock {
                if (navigationSeq != seqAtEntry) { stale = true; return@withLock }
                prev = queueManager.computePrev()
                prev?.let { queueManager.advanceTo(it) }
            }
            if (stale) return@launch
            val target = prev
            if (target == null) {
                mediaLoader.requestSeek(0L)
                return@launch
            }
            pushQueueState()
            playCurrent()
        }
    }

    override fun setRepeatMode(mode: RepeatMode) {
        queueManager.setRepeatMode(mode)
        updateState { it.copy(repeatMode = mode) }
        persistPlaybackModes()
        mediaLoader.triggerPreloadNext()
    }

    override fun toggleShuffle() {
        val shuffle = queueManager.toggleShuffle()
        updateState { it.copy(shuffleEnabled = shuffle) }
        persistPlaybackModes()
        mediaLoader.triggerPreloadNext()
    }

    // ============ 收藏 ============

    override suspend fun toggleFavorite() {
        val mediaId = queueManager.currentEntry?.mediaId ?: return
        favoritesManager.toggleFavoriteFor(mediaId)
    }

    override suspend fun toggleFavoriteFor(mediaId: String) {
        favoritesManager.toggleFavoriteFor(mediaId)
    }

    override suspend fun refreshFavorites() {
        favoritesManager.refreshFavorites()
    }

    // ============ 音质与预加载 ============

    override fun setQuality(level: String) {
        mediaLoader.setQuality(level)
    }

    override fun setPreloadNext(enabled: Boolean) {
        mediaLoader.setPreloadNext(enabled)
    }

    override fun preloadNext() {
        mediaLoader.preloadNext()
    }

    // ============ 睡眠定时 ============

    override fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (minutes == PlaybackController.SLEEP_AFTER_TRACK) {
            sleepAfterTrack = true
            updateState { it.copy(sleepAfterTrack = true, sleepTimerRemainingMs = null) }
            return
        }
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        sleepAfterTrack = false
        var remaining = minutes * 60_000L
        updateState { it.copy(sleepAfterTrack = false, sleepTimerRemainingMs = remaining) }
        sleepTimerJob = scope.launch {
            while (remaining > 0) {
                kotlinx.coroutines.delay(1_000L)
                remaining -= 1_000L
                updateState { it.copy(sleepTimerRemainingMs = remaining.coerceAtLeast(0L)) }
            }
            platform.pause()
            cancelSleepTimer()
        }
    }

    override fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepAfterTrack = false
        updateState { it.copy(sleepAfterTrack = false, sleepTimerRemainingMs = null) }
    }

    // ============ 歌词 ============

    override suspend fun refreshLyrics() {
        val entry = queueManager.currentEntry
        val mediaId = entry?.mediaId ?: run {
            lyricsMediaId = null
            updateState { it.copy(lyrics = LyricsState.Idle, activeLyricIndex = -1) }
            return
        }
        lyricsJob?.cancel()
        lyricsMediaId = mediaId
        lyricsJob = scope.launch {
            updateState { it.copy(lyrics = LyricsState.Loading, lyricsInfo = null, activeLyricIndex = -1) }
            val parsed = try {
                lyricsLoader.loadLyrics(mediaId, entry.summary)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                LyricsState.Error(e.message ?: "歌词获取失败") to null
            }
            if (queueManager.currentEntry?.mediaId != mediaId) return@launch
            if (lyricsMediaId != mediaId) return@launch
            val pos = _state.value.positionMs
            updateState {
                it.copy(
                    lyrics = parsed.first,
                    lyricsInfo = parsed.second,
                    activeLyricIndex = computeLyricIndex(parsed.first, pos),
                )
            }
        }
    }

    // ============ 音量 / 音效 / 释放 ============

    override val currentVolume: Float
        get() = fadeController.userVolume

    override fun setVolume(volume: Float) {
        val v = fadeController.setUserVolume(volume)
        updateState { it.copy(volume = v) }
    }

    override val audioEffectCapabilities: AudioEffectCapabilities
        get() = platform.audioEffectCapabilities

    override val supportsPlaybackSpeed: Boolean
        get() = platform.supportsPlaybackSpeed

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        platform.setPlaybackSpeed(clamped)
        // 只写用户设定的值；不支持的平台能力位为 false，UI 根本不会调到，
        // 所以这里不需要「引擎是否真的接受」的回读。
        updateState { it.copy(playbackSpeed = clamped) }
    }

    override fun setFade(config: FadeConfig) {
        fadeController.setFade(config)
    }

    override fun fadeConfiguration(): FadeConfig =
        fadeController.fadeConfig

    override val isSmartKaraokeLrcEnabled: Boolean
        get() = lyricsLoader.smartKaraokeLrcEnabled

    override fun setSmartKaraokeLrcEnabled(enabled: Boolean) {
        if (lyricsLoader.smartKaraokeLrcEnabled != enabled) {
            lyricsLoader.smartKaraokeLrcEnabled = enabled
            scope.launch { refreshLyrics() }
        }
    }

    private fun currentDurationMs(): Long? =
        _state.value.durationMs.takeIf { it > 0L }
            ?: queueManager.currentEntry?.summary?.durationMs?.takeIf { it > 0L }

    @Volatile
    private var lastAudioEffect: AudioEffectConfig = AudioEffectConfig.OFF

    override fun setAudioEffect(config: AudioEffectConfig) {
        lastAudioEffect = config
        platform.applyAudioEffect(config)
    }

    override fun reloadEngine() {
        platform.reloadEngine()
    }

    override fun release() {
        persistSessionBlocking()
        fadeController.cancel()
        scrobbler.cancel()
        lyricsJob?.cancel()
        mediaLoader.cancelAll()
        sleepTimerJob?.cancel()
        favoritesManager.cancel()
        queueManager.cancel()
        platform.release()
    }

    // ============ 内部调度 ============

    private suspend fun playCurrent() {
        mediaLoader.playCurrent(advanceNavSeq = { navigationSeq += 1 })
    }

    private fun onTrackEnded() {
        val seqAtEnd = navigationSeq
        scope.launch {
            if (navigationSeq != seqAtEnd) return@launch
            scrobbler.flush()
            if (sleepAfterTrack) {
                cancelSleepTimer()
                updateState { it.copy(isPlaying = false) }
                return@launch
            }
            when (queueManager.repeatMode) {
                RepeatMode.ONE -> {
                    fadeController.onTrackPreparing()
                    scrobbler.resetCounts()
                    mediaLoader.requestSeek(0L)
                    platform.play()
                    fadeController.startFadeIn()
                }
                RepeatMode.ALL, RepeatMode.OFF -> {
                    var next: Int? = null
                    var stale = false
                    navMutex.withLock {
                        if (navigationSeq != seqAtEnd) { stale = true; return@withLock }
                        next = queueManager.computeNext(autoAdvance = true)
                        next?.let { queueManager.advanceTo(it) }
                    }
                    if (stale) return@launch
                    val target = next
                    if (target == null) {
                        scrobbler.cancel()
                        scrobbler.resetCounts()
                        updateState { it.copy(isPlaying = false, positionMs = 0L) }
                        return@launch
                    }
                    pushQueueState()
                    playCurrent()
                }
            }
        }
    }

    private fun resolveQueueInBackground(startFrom: Int) {
        queueManager.resolveQueueInBackground(
            startFrom = startFrom,
            onCurrentTrackUpdated = { summary ->
                updateState {
                    it.copy(
                        currentTrack = summary,
                        currentIndex = queueManager.currentIndex,
                        durationMs = summary.durationMs.takeIf { d -> d > 0 } ?: it.durationMs,
                    )
                }
                if (_state.value.lyrics is LyricsState.Idle) {
                    scope.launch { refreshLyrics() }
                }
            },
            onQueueStructureChanged = {
                pushQueueState()
            },
        )
    }

    private suspend fun resolveEntry(index: Int) {
        queueManager.resolveEntry(
            index = index,
            onCurrentTrackUpdated = { summary ->
                updateState {
                    it.copy(
                        currentTrack = summary,
                        currentIndex = queueManager.currentIndex,
                        durationMs = summary.durationMs.takeIf { d -> d > 0 } ?: it.durationMs,
                    )
                }
                ensureLyricsForCurrentIfNeeded()
            },
            onQueueStructureChanged = {
                pushQueueState()
            },
        )
    }

    private fun ensureLyricsForCurrentIfNeeded() {
        val mediaId = queueManager.currentEntry?.mediaId ?: return
        if (lyricsMediaId == mediaId && _state.value.lyrics is LyricsState.Loading) return
        if (lyricsMediaId == mediaId && _state.value.lyrics is LyricsState.Success) return
        if (lyricsMediaId == mediaId && _state.value.lyrics is LyricsState.NoLyrics) return
        if (lyricsMediaId == mediaId && _state.value.lyrics is LyricsState.Error) return
        scope.launch { refreshLyrics() }
    }

    private fun pushQueueState() {
        val current = queueManager.currentEntry
        favoritesManager.syncCurrentMediaId(current?.mediaId)
        updateState {
            it.copy(
                queue = queueManager.entries.map { e ->
                    e.summary?.toQueueItem(e.mediaId) ?: QueueItem(e.mediaId, "加载中…", "", null, null, 0L)
                },
                currentIndex = queueManager.currentIndex,
            )
        }
        persistSession()
    }

    private fun clearState() {
        scrobbler.flush()
        scrobbler.cancel()
        scrobbler.resetCounts()
        lyricsMediaId = null
        updateState {
            it.copy(
                currentTrack = null,
                currentIndex = -1,
                positionMs = 0L,
                durationMs = 0L,
                lyrics = LyricsState.Idle,
                activeLyricIndex = -1,
                isPlaying = false,
                isBuffering = false,
                isLocalizing = false,
                formatInfo = null,
                lyricsInfo = null,
                error = null,
                sourceId = null,
                isFavorite = false,
            )
        }
    }

    private fun updateState(transform: (PlaybackUiState) -> PlaybackUiState) {
        _state.value = transform(_state.value)
    }
}
