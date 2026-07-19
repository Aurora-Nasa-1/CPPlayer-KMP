package cp.player.kmp.playback

import cp.player.kmp.api.MusicApiService
import cp.player.kmp.music.TrackSummary
import cp.player.kmp.music.UnifiedMusicSource
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
import kotlin.random.Random

/**
 * [PlaybackController] 的平台无关实现。
 *
 * 职责：
 * 1. 队列管理（含延迟解析 [TrackSummary]、shuffle 化播放顺序）。
 * 2. 通过 [UnifiedMusicSource] 取播放 URL，交给 [PlatformPlayer] 播放。
 * 3. 把 [PlatformPlayer] 的状态/位置/时长/格式信息 fold 进 [PlaybackUiState]。
 * 4. 自动抓取当前曲目歌词（[MusicApiService.getLyric] → [LyricsParser]）。
 * 5. 听歌打卡（scrobble）：播放中每秒计数，每 [SCROBBLE_INTERVAL_S] 秒上报。
 * 6. 自然播完自动跳下一首（按 [RepeatMode]/shuffle 规则）。
 *
 * **保证**：前端 collect [state] 后零额外工作；所有 URL 解析、Song↔MediaItem 转换、
 * 歌词格式转换、引擎差异均封装在此处。
 *
 * @param scope 应用级 CoroutineScope（生命周期与 [MusicBackend] 一致）。
 * @param cookieProvider 返回当前活跃 Provider 的完整 cookie 字符串（用于播放 URL 请求头注入）。
 */
class PlaybackControllerImpl(
    private val platform: PlatformPlayer,
    private val source: UnifiedMusicSource,
    private val api: MusicApiService,
    private val cookieProvider: () -> String?,
    private val scope: CoroutineScope,
) : PlaybackController {

    private val _state = MutableStateFlow(PlaybackUiState())
    override val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val navMutex = Mutex()

    /** 队列条目。mediaId 唯一；summary 可能尚未解析（懒解析）。 */
    private data class Entry(val mediaId: String) {
        @Volatile var summary: TrackSummary? = null
    }

    private val _queue = mutableListOf<Entry>()
    private var _index = -1
    private var _repeat = RepeatMode.OFF
    private var _shuffle = false
    /** 实际播放顺序（指向 _queue 索引）。null 表示按自然顺序。 */
    private var _order: List<Int>? = null
    private var _orderPos = -1
    private var _sourceId: String? = null

    /** 当前曲目加载任务（用于切换时取消旧任务）。 */
    private var loadJob: Job? = null
    private var lyricsJob: Job? = null
    private var scrobbleJob: Job? = null
    /** 当前曲目已播放秒数（累计，仅 isPlaying=true 期间）。 */
    private var scrobbledSeconds = 0
    private var scrobbleTickJob: Job? = null
    /** 加载世代替换旧任务状态过时写入。 */
    private var loadGeneration = 0

    // ============ 收藏状态 ============

    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    override val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    /** 收藏列表是否已成功拉取过（避免每次切歌重复请求）。 */
    private var favoritesLoaded = false
    private var favoritesLoadingJob: Job? = null

    // ============ 音质 ============

    private var qualityLevel: String = "exhigh"

    // ============ 睡眠定时 ============

    private var sleepTimerJob: Job? = null
    private var sleepAfterTrack = false

    init {
        observePlatform()
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
                    error = error ?: it.error,
                )
            }
            if (ended) onTrackEnded()
            if (playing) ensureScrobbleRunning()
            if (!playing) pauseScrobble()
        }.launchIn(scope)
        platform.positionMs.onEach { pos ->
            updateState { it.copy(positionMs = pos, activeLyricIndex = computeLyricIndex(it.lyrics, pos)) }
        }.launchIn(scope)
        platform.durationMs.onEach { dur ->
            updateState { it.copy(durationMs = dur) }
        }.launchIn(scope)
        platform.formatInfo.onEach { info ->
            updateState { it.copy(formatInfo = info) }
        }.launchIn(scope)
    }

    private data class Quad(val playing: Boolean, val buffering: Boolean, val error: String?, val ended: Boolean)

    private fun computeLyricIndex(lyrics: LyricsState, pos: Long): Int {
        val lines = (lyrics as? LyricsState.Success)?.lines ?: return -1
        if (lines.isEmpty()) return -1
        // 找最后一行 time<=pos
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].time <= pos) idx = i else break
        }
        return idx
    }

    // ============ 队列 / 播放 ============

    override suspend fun playQueue(mediaIds: List<String>, startIndex: Int, sourceId: String?) {
        if (mediaIds.isEmpty()) return
        ensureOrderScopeSafe()
        navMutex.withLock {
            _queue.clear()
            mediaIds.forEach { _queue.add(Entry(it)) }
            _order = if (_shuffle) shuffledOrder(_queue.size) else null
            _index = startIndex.coerceIn(0, _queue.lastIndex)
            _orderPos = _order?.indexOf(_index) ?: _index
            _sourceId = sourceId
        }
        pushQueueState()
        resolveQueueInBackground(startFrom = _index)
        playCurrent(skipIfSame = false)
    }

    override suspend fun setQueue(mediaIds: List<String>, startIndex: Int, sourceId: String?) {
        if (mediaIds.isEmpty()) return
        navMutex.withLock {
            _queue.clear()
            mediaIds.forEach { _queue.add(Entry(it)) }
            _order = if (_shuffle) shuffledOrder(_queue.size) else null
            _index = startIndex.coerceIn(0, _queue.lastIndex)
            _orderPos = _order?.indexOf(_index) ?: _index
            _sourceId = sourceId
        }
        pushQueueState()
        resolveQueueInBackground(startFrom = _index)
    }

    override suspend fun addToQueue(mediaId: String) {
        navMutex.withLock {
            _queue.add(Entry(mediaId))
            if (_shuffle) _order = (_order ?: _queue.indices.toList()) + (_queue.lastIndex)
        }
        pushQueueState()
        scope.launch { resolveEntry(_queue.lastIndex) }
    }

    override suspend fun removeQueueItem(index: Int) {
        navMutex.withLock {
            if (index !in _queue.indices) return@withLock
            val wasCurrent = index == _index
            _queue.removeAt(index)
            if (_queue.isEmpty()) {
                _index = -1
                _order = null
                _orderPos = -1
                platform.stop()
                clearState()
                return@withLock
            }
            // 重建顺序
            _order = (_order?.filter { it != index }?.map { if (it > index) it - 1 else it })
                ?.let { if (_shuffle) it else null }
            if (wasCurrent) {
                _index = (_order?.getOrNull(_orderPos) ?: _index).coerceIn(0, _queue.lastIndex)
                _orderPos = _order?.indexOf(_index) ?: _index.coerceAtLeast(0)
            } else if (_index > index) {
                _index -= 1
            }
        }
        pushQueueState()
        if (_queue.isNotEmpty() && index <= _index && _index >= 0) {
            // 若移除了当前或之前的，按约定重新播放当前
            playCurrent(skipIfSame = false)
        }
    }

    override fun clearQueue() {
        scope.launch {
            navMutex.withLock {
                _queue.clear()
                _index = -1
                _order = null
                _orderPos = -1
                _sourceId = null
            }
            platform.stop()
            clearState()
            pushQueueState()
        }
    }

    override suspend fun moveQueueItem(from: Int, to: Int) {
        if (from == to) return
        navMutex.withLock {
            if (from !in _queue.indices || to !in _queue.indices) return@withLock
            val item = _queue.removeAt(from)
            _queue.add(to, item)
            // 维护 _index：当前曲目得跟着移
            _index = when (_index) {
                from -> to
                in (from + 1)..to -> _index - 1
                in to..<from -> _index + 1
                else -> _index
            }
            // shuffle 顺序失效，重建：丢弃旧顺序，按当前 _index 在最前
            if (_shuffle) {
                _order = listOf(_index) + (_queue.indices.toList() - _index).shuffled(Random(System.nanoTime()))
                _orderPos = 0
            }
        }
        pushQueueState()
    }

    override suspend fun playAt(index: Int) {
        navMutex.withLock {
            if (index !in _queue.indices) return
            _index = index
            _orderPos = _order?.indexOf(index) ?: index
        }
        playCurrent(skipIfSame = false)
    }

    // ============ 播控 ============

    override fun togglePlayPause() {
        when (platform.state.value) {
            PlatformPlaybackState.Playing -> platform.pause()
            PlatformPlaybackState.Paused, PlatformPlaybackState.Ready -> platform.play()
            PlatformPlaybackState.Ended -> {
                if (_index in _queue.indices) scope.launch { playCurrent(skipIfSame = false) }
            }
            else -> {
                if (_index in _queue.indices) scope.launch { playCurrent(skipIfSame = false) }
            }
        }
    }

    override fun pause() { platform.pause() }
    override fun resume() { platform.play() }
    override fun seekTo(positionMs: Long) { platform.seekTo(positionMs) }

    override fun skipNext() {
        scope.launch {
            val next = computeNext(autoAdvance = true)
            if (next == null) {
                platform.stop()
                updateState { it.copy(isPlaying = false, positionMs = 0L) }
                return@launch
            }
            _index = next
            _orderPos = _order?.indexOf(next) ?: next
            pushQueueState()
            playCurrent(skipIfSame = false)
        }
    }

    override fun skipPrevious() {
        scope.launch {
            // 3 秒内回到上一首；超过 3 秒回退到本曲开头
            if (platform.positionMs.value > 3_000L) {
                platform.seekTo(0L)
                return@launch
            }
            val prev = computePrev()
            if (prev == null) {
                platform.seekTo(0L)
                return@launch
            }
            _index = prev
            _orderPos = _order?.indexOf(prev) ?: prev
            pushQueueState()
            playCurrent(skipIfSame = false)
        }
    }

    override fun setRepeatMode(mode: RepeatMode) {
        _repeat = mode
        updateState { it.copy(repeatMode = mode) }
    }

    // ============ 收藏 ============

    override suspend fun toggleFavorite() {
        val mediaId = _queue.getOrNull(_index)?.mediaId ?: return
        toggleFavoriteFor(mediaId)
    }

    override suspend fun toggleFavoriteFor(mediaId: String) {
        val parsed = runCatching { cp.player.kmp.music.CPMediaId.parse(mediaId) }.getOrNull() ?: return
        if (parsed.providerId == "local") return
        val id = parsed.resourceId
        ensureFavoritesLoaded()
        val currentlyLiked = id in _likedIds.value
        val target = !currentlyLiked
        // 乐观更新
        applyLike(id, target)
        val ok = runCatching {
            val json = api.likeSong(id, target)
            val code = ((json as? kotlinx.serialization.json.JsonObject)
                ?.get("code") as? kotlinx.serialization.json.JsonPrimitive)
                ?.let { runCatching { it.content.toInt() }.getOrNull() }
            code == null || code in listOf(200, 201, 301)
        }.getOrDefault(false)
        if (!ok) {
            // 回滚
            applyLike(id, currentlyLiked)
        }
    }

    override suspend fun refreshFavorites() {
        favoritesLoaded = false
        favoritesLoadingJob?.cancel()
        ensureFavoritesLoadedInternal(force = true)
    }

    /** 后台确保收藏列表已加载（只触发一次，失败静默）。 */
    private fun ensureFavoritesLoaded() {
        if (favoritesLoaded || favoritesLoadingJob?.isActive == true) return
        favoritesLoadingJob = scope.launch { ensureFavoritesLoadedInternal() }
    }

    private suspend fun ensureFavoritesLoadedInternal(force: Boolean = false) {
        if (favoritesLoaded && !force) return
        runCatching {
            val status = api.getLoginStatus()
            val root = (status as? kotlinx.serialization.json.JsonObject) ?: return@runCatching
            val data = (root["data"] as? kotlinx.serialization.json.JsonObject) ?: root
            val account = (data["account"] as? kotlinx.serialization.json.JsonObject)
                ?: (data["profile"] as? kotlinx.serialization.json.JsonObject)
            val uid = account?.let {
                (it["id"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.let { p -> runCatching { p.content.toLong() }.getOrNull() }
            }
            if (uid == null) {
                // 未登录/登出：清空收藏集合
                _likedIds.value = emptySet()
                updateState { s -> s.copy(isFavorite = false) }
                return@runCatching
            }
            val likeJson = api.getLikeList(uid)
            val likeRoot = (likeJson as? kotlinx.serialization.json.JsonObject) ?: return@runCatching
            val ids = ((likeRoot["ids"] ?: likeRoot["data"] ?: (likeRoot["data"] as? kotlinx.serialization.json.JsonObject)?.get("ids"))
                    as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.toSet()
                ?: return@runCatching
            _likedIds.value = ids
            favoritesLoaded = true
            syncIsFavorite()
        }
    }

    private fun applyLike(id: String, liked: Boolean) {
        _likedIds.value = if (liked) _likedIds.value + id else _likedIds.value - id
        syncIsFavorite()
    }

    /** 把当前曲目收藏态同步进 UI 状态。 */
    private fun syncIsFavorite() {
        val mediaId = _queue.getOrNull(_index)?.mediaId ?: return
        val parsed = runCatching { cp.player.kmp.music.CPMediaId.parse(mediaId) }.getOrNull() ?: return
        val fav = parsed.resourceId in _likedIds.value
        updateState { it.copy(isFavorite = fav) }
    }

    // ============ 音质 ============

    override fun setQuality(level: String) {
        if (level.isBlank()) return
        qualityLevel = level
        updateState { it.copy(qualityLevel = level) }
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
        sleepAfterTrack = false
        var remaining = minutes * 60_000L
        updateState { it.copy(sleepAfterTrack = false, sleepTimerRemainingMs = remaining) }
        sleepTimerJob = scope.launch {
            while (remaining > 0) {
                kotlinx.coroutines.delay(1_000L)
                remaining -= 1_000L
                updateState { it.copy(sleepTimerRemainingMs = remaining.coerceAtLeast(0L)) }
            }
            // 到时：暂停并清除定时
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

    override fun toggleShuffle() {
        _shuffle = !_shuffle
        if (_shuffle) {
            _order = shuffledOrder(_queue.size)
            _orderPos = _order?.indexOf(_index) ?: -1
        } else {
            _order = null
            _orderPos = _index
        }
        updateState { it.copy(shuffleEnabled = _shuffle) }
    }

    // ============ 歌词 ============

    override suspend fun refreshLyrics() {
        val mediaId = _queue.getOrNull(_index)?.mediaId ?: run {
            updateState { it.copy(lyrics = LyricsState.Idle, activeLyricIndex = -1) }
            return
        }
        lyricsJob?.cancel()
        lyricsJob = scope.launch {
            updateState { it.copy(lyrics = LyricsState.Loading, activeLyricIndex = -1) }
            val parsed = try {
                val id = cp.player.kmp.music.CPMediaId.parse(mediaId)
                if (id.providerId == "local") {
                    updateState { it.copy(lyrics = LyricsState.NoLyrics) }
                    return@launch
                }
                val json = api.getLyric(id.resourceId)
                val lines = LyricsParser.parse(json)
                if (lines.isEmpty()) LyricsState.NoLyrics
                else LyricsState.Success(lines)
            } catch (e: Throwable) {
                LyricsState.Error(e.message ?: "歌词获取失败")
            }
            val pos = platform.positionMs.value
            updateState { it.copy(lyrics = parsed, activeLyricIndex = computeLyricIndex(parsed, pos)) }
        }
    }

    // ============ 音量 / 释放 ============

    override fun setVolume(volume: Float) { platform.setVolume(volume.coerceIn(0f, 1f)) }

    override fun release() {
        scrobbleTickJob?.cancel(); scrobbleJob?.cancel(); lyricsJob?.cancel(); loadJob?.cancel()
        sleepTimerJob?.cancel(); favoritesLoadingJob?.cancel()
        platform.release()
    }

    // ============ 内部 ============

    /** 获取 URL 并交给平台播放器播放当前曲目。 */
    private suspend fun playCurrent(skipIfSame: Boolean) {
        val entry = _queue.getOrNull(_index) ?: return
        loadJob?.cancel()
        scrobbleTickJob?.cancel(); scrobbleJob?.cancel()
        scrobbledSeconds = 0
        val gen = ++loadGeneration
        loadJob = scope.launch {
            fun emit(u: PlaybackUiState) { if (loadGeneration == gen) updateState { u } }

            val mediaId = entry.mediaId
            if (entry.summary == null) resolveEntry(_index)
            val summary = entry.summary ?: source.getTrackDetail(mediaId).getOrNull()?.also { entry.summary = it }
            emit(
                _state.value.copy(
                    currentTrack = summary,
                    currentIndex = _index,
                    sourceId = _sourceId,
                    lyrics = LyricsState.Loading,
                    activeLyricIndex = -1,
                    durationMs = summary?.durationMs?.takeIf { d -> d > 0 } ?: 0L,
                    error = if (summary == null) "无法获取曲目信息" else null,
                    isBuffering = summary != null,
                )
            )
            if (summary == null) return@launch
            ensureFavoritesLoaded()
            emit(_state.value.copy(
                isFavorite = cp.player.kmp.music.CPMediaId.parse(mediaId).resourceId in _likedIds.value,
            ))
            val urlResult = source.getSongUrl(mediaId, level = qualityLevel)
            val songUrl = urlResult.getOrNull()
            if (songUrl == null || songUrl.url.isBlank()) {
                emit(_state.value.copy(
                    isBuffering = false,
                    error = (urlResult as? cp.player.kmp.BackendResult.Error)?.message ?: "无法获取播放地址"
                ))
                return@launch
            }
            val headers = buildMap {
                songUrl.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                if (!containsKey("Cookie")) {
                    cookieProvider()?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                }
            }
            try {
                platform.load(songUrl.url, startPositionMs = 0L, headers = headers)
                platform.play()
                refreshLyrics()
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                emit(_state.value.copy(isBuffering = false, error = msg))
            }
        }
    }

    private fun onTrackEnded() {
        scope.launch {
            if (sleepAfterTrack) {
                // 睡眠定时：播完当前后暂停，不再自动续播
                cancelSleepTimer()
                updateState { it.copy(isPlaying = false) }
                return@launch
            }
            when (_repeat) {
                RepeatMode.ONE -> {
                    platform.seekTo(0L); platform.play()
                }
                RepeatMode.ALL, RepeatMode.OFF -> {
                    val next = computeNext(autoAdvance = true)
                    if (next == null) {
                        updateState { it.copy(isPlaying = false, positionMs = 0L) }
                        return@launch
                    }
                    _index = next
                    _orderPos = _order?.indexOf(next) ?: next
                    pushQueueState()
                    playCurrent(skipIfSame = false)
                }
            }
        }
    }

    /** 计算下一首索引（不修改状态）。null=到达终点。 */
    private suspend fun computeNext(autoAdvance: Boolean): Int? {
        if (_queue.isEmpty()) return null
        if (_shuffle && _order != null) {
            val order = _order!!
            val nextPos = _orderPos + 1
            return if (nextPos <= order.lastIndex) order[nextPos]
            else if (_repeat == RepeatMode.ALL) order[0]
            else null
        }
        val next = _index + 1
        return when {
            next <= _queue.lastIndex -> next
            _repeat == RepeatMode.ALL -> 0
            else -> null
        }
    }

    private suspend fun computePrev(): Int? {
        if (_queue.isEmpty()) return null
        if (_shuffle && _order != null) {
            val order = _order!!
            val prevPos = _orderPos - 1
            return if (prevPos >= 0) order[prevPos]
            else if (_repeat == RepeatMode.ALL) order.last()
            else null
        }
        val prev = _index - 1
        return if (prev >= 0) prev else if (_repeat == RepeatMode.ALL) _queue.lastIndex else null
    }

    private fun shuffledOrder(size: Int): List<Int> {
        return (0 until size).shuffled(Random(System.nanoTime()))
    }

    private fun ensureOrderScopeSafe() { /* placeholder for future constraints */ }

    // ============ 队列解析（懒解析） ============

    private fun resolveQueueInBackground(startFrom: Int) {
        val toResolve = _queue.indices.sortedBy { if (it == startFrom) 0 else 1 + kotlin.math.abs(it - startFrom) }
        scope.launch {
            toResolve.forEach { i -> resolveEntry(i) }
        }
    }

    private suspend fun resolveEntry(index: Int) {
        val entry = _queue.getOrNull(index) ?: return
        if (entry.summary != null) return
        val result = source.getTrackDetail(entry.mediaId)
        val summary = result.getOrNull()
        if (summary != null) {
            entry.summary = summary
            if (index == _index) {
                updateState { it.copy(currentTrack = summary, currentIndex = _index, durationMs = summary.durationMs.takeIf { d -> d > 0 } ?: it.durationMs) }
            }
            pushQueueState()
        }
    }

    private fun pushQueueState() {
        updateState {
            it.copy(
                queue = _queue.mapIndexed { i, e -> e.summary?.toQueueItem() ?: QueueItem(e.mediaId, "加载中…", "", null, null, 0L) },
                currentIndex = _index,
            )
        }
    }

    private fun clearState() {
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
                formatInfo = null,
                error = null,
                sourceId = null,
                isFavorite = false,
            )
        }
    }

    private fun updateState(transform: (PlaybackUiState) -> PlaybackUiState) {
        _state.value = transform(_state.value)
    }

    // ============ Scrobble ============

    private fun ensureScrobbleRunning() {
        if (scrobbleTickJob?.isActive == true) return
        scrobbleTickJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000L)
                scrobbledSeconds += 1
                if (scrobbledSeconds % SCROBBLE_INTERVAL_S == 0) flushScrobble()
            }
        }
    }

    private fun pauseScrobble() {
        scrobbleTickJob?.cancel(); scrobbleTickJob = null
    }

    private fun flushScrobble() {
        val mediaId = _queue.getOrNull(_index)?.mediaId ?: return
        val summary = _queue.getOrNull(_index)?.summary ?: return
        if (scrobbledSeconds <= 0) return
        val id = cp.player.kmp.music.CPMediaId.parse(mediaId)
        if (id.providerId == "local") return
        scrobbleJob?.cancel()
        scrobbleJob = scope.launch {
            runCatching {
                api.scrobble(
                    songId = id.resourceId,
                    sourceId = _sourceId ?: summary.album ?: "0",
                    playedSeconds = scrobbledSeconds,
                )
            }
            // 失败静默：scrobble 不影响播放体验
        }
    }

    private companion object {
        /** 每 N 秒上报一次听歌打卡。 */
        const val SCROBBLE_INTERVAL_S = 30
    }
}

/** 便捷扩展：从 [MusicResult] 取成功值或 null。 */
private fun <T> cp.player.kmp.BackendResult<T>.getOrNull(): T? =
    (this as? cp.player.kmp.BackendResult.Success)?.data