package cp.player.core.playback

import cp.player.core.api.MusicApiService
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.model.LyricsInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    /**
     * 无损档位的「边播边落盘」（见 [StreamLocalizer]）。
     *
     * 默认是空实现，桌面由组合根注入 [DesktopStreamLocalizer] —— 桌面引擎（rodio）
     * 无法定位 FLAC over HTTP，不落盘就只能「能播但不能拖」。
     * 给默认值是为了让既有测试不必改动。
     */
    private val streamLocalizer: StreamLocalizer = NoOpStreamLocalizer,
) : PlaybackController {

    private val _state = MutableStateFlow(PlaybackUiState())
    override val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /**
     * seek 未能生效的事件（宽限期已过、乐观值已放弃）。
     *
     * 直接转发平台层信号：它是**事件**不是状态，用 StateFlow 会在重新订阅时重放，
     * 还得手工清理。前端收到后提示用户即可。
     */
    override val seekFailures: kotlinx.coroutines.flow.SharedFlow<SeekFailure>
        get() = platform.seekFailures

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

    /**
     * 「引擎里装的已经是当前曲目」标志。
     *
     * `playCurrent` 从发起到 [PlatformPlayer.load] 返回之间有一段不短的窗口：
     * 解析播放地址（网络）、建连、prepare。**这段时间引擎里还是上一首的媒体**，
     * 期间用户拖动的 seek 会被 `load(startPositionMs = 0)` 直接覆盖掉——
     * 表现为「拖了没反应」。因此窗口内的 seek 先记进 [deferredSeek]，
     * 由 `playCurrent` 作为起始位置一并交给引擎。
     */
    @Volatile private var engineReady = false

    /**
     * 装载窗口内用户发起的 seek：`曲目 mediaId → 目标毫秒`。
     *
     * 带上 mediaId 是为了防止串曲：用户在 A 装载期间拖了进度、随即又切到 B，
     * 这个意图不能落到 B 头上。
     */
    @Volatile private var deferredSeek: Pair<String, Long>? = null

    /**
     * 当前曲目**已就绪的本地副本**：`曲目 mediaId → 本地绝对路径`。
     *
     * 无损档位在后台落盘完成后写入。此时引擎还在放流，**不立刻切源**
     * （换源会有一声咔哒），等用户第一次拖动时才切过去——见 [requestSeek]。
     * 带上 mediaId 是为了防串曲：A 的副本绝不能落到 B 头上。
     */
    @Volatile private var localized: Pair<String, String>? = null

    /**
     * 引擎当前是否已在播**本地副本**（而非流）。
     *
     * 用来保证「拖动时才切换」只发生一次：第二次之后的拖动直接就是普通 seek，
     * 引擎已经在放可定位的本地文件了。
     */
    @Volatile private var playingFromLocal = false

    /**
     * 后台落盘任务。换曲时取消——否则会白下一首已经切走的歌，白占带宽。
     */
    private var localizeJob: Job? = null

    /**
     * 最近一次 [platform.load] 用的请求头与元信息。
     *
     * 切到本地副本时要重新 load 一次，必须复用同一份元信息
     * （否则 SMTC / 缓存键会退回默认值）。
     */
    @Volatile private var lastHeaders: Map<String, String> = emptyMap()
    @Volatile private var lastMetadata: PlaybackMetadata? = null

    /**
     * 导航世代：每次开始加载新曲目自增。
     *
     * 用于丢弃**过期事件**——典型场景是上一首的 `Ended` 回调姗姗来迟，
     * 此时用户已经手动切歌，若不丢弃就会把新歌再跳掉一首（"多跳"）。
     * 同时也用于串行化 [skipNext] / [onTrackEnded] 的并发推进。
     */
    @Volatile private var navigationSeq = 0

    /** 队列后台解析任务；换队列时取消，避免把旧队列的元信息写进新队列。 */
    private var resolveJob: Job? = null

    /** 队列世代：换队列时自增，用于丢弃旧解析任务在途的写入。 */
    private var queueGeneration = 0

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
            // 装载窗口内已有暂存的 seek 时**不要**采信引擎位置：
            // 此刻引擎上报的还是上一首的位置（新曲目还没 load 进去），
            // 直接写入会把 requestSeek 刚做的乐观回写冲掉——进度条松手即回弹。
            // 等 playCurrent 把暂存值作为起始位置交给引擎后，这里自然恢复接管。
            if (deferredSeek != null) return@onEach
            updateState { it.copy(positionMs = pos, activeLyricIndex = computeLyricIndex(it.lyrics, pos)) }
        }.launchIn(scope)
        platform.durationMs.onEach { dur ->
            // ⚠️ 只在拿到**有效**时长时覆盖。
            //
            // 引擎在装载/缓冲期会上报 0（ExoPlayer 的 TIME_UNSET、rodio 取不到时长时也是 0）。
            // 旧写法无条件写入，会把 `playCurrent` 里用曲目元信息填好的时长清成 0，
            // 于是进度条的 `valueRange` 从 `0..durationMs` 塌成 `0..1`：
            // 用户拖动只会得到 0 或 1 毫秒 —— 也就是「seek 用不了」。
            // 元信息里的时长总是先到且正确，让它当兜底。
            if (dur > 0L) updateState { it.copy(durationMs = dur) }
        }.launchIn(scope)
        platform.formatInfo.onEach { info ->
            updateState { it.copy(formatInfo = info) }
        }.launchIn(scope)
    }

    private data class Quad(val playing: Boolean, val buffering: Boolean, val error: String?, val ended: Boolean)

    private fun computeLyricIndex(lyrics: LyricsState, pos: Long): Int {
        val lines = (lyrics as? LyricsState.Success)?.lines ?: return -1
        if (lines.isEmpty()) return -1
        // ⚡ Bolt: Use binary search (O(log n)) instead of linear search (O(n)) to find the active lyric line.
        // This function is called continuously on every position tick (e.g. 60+ times per second),
        // and lines are guaranteed to be sorted by time.
        // We look for the *last* line where time <= pos.
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
        playCurrent()
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
                engineReady = false
                deferredSeek = null
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
            playCurrent()
        }
    }

    override fun clearQueue() {
        // 队列即将清空：作废在途的后台解析，避免它继续写已失效的条目。
        resolveJob?.cancel()
        queueGeneration++
        // 引擎即将被停掉：任何在途 seek 都失去意义，立刻失效避免落到下一队列上。
        engineReady = false
        deferredSeek = null
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
        playCurrent()
    }

    // ============ 播控 ============

    override fun togglePlayPause() {
        when (platform.state.value) {
            PlatformPlaybackState.Playing -> platform.pause()
            PlatformPlaybackState.Paused, PlatformPlaybackState.Ready -> platform.play()
            PlatformPlaybackState.Ended -> {
                if (_index in _queue.indices) scope.launch { playCurrent() }
            }
            else -> {
                if (_index in _queue.indices) scope.launch { playCurrent() }
            }
        }
    }

    override fun pause() { platform.pause() }
    override fun resume() { platform.play() }
    override fun seekTo(positionMs: Long) { requestSeek(positionMs) }

    /**
     * 唯一的 seek 入口：内部重播归零（单曲循环、上一首 3 秒规则）与 UI 拖动都走这里，
     * 保证「装载窗口内暂存、否则直发」的规则只有一处实现。
     *
     * 与旧实现的区别：
     * 1. 队列为空 / 无当前曲目时**直接忽略**，不再把无效目标丢给引擎；
     * 2. 目标钳制到 `[0, 有效时长]`，避免元信息时长与引擎时长不一致时越界；
     * 3. 引擎还没装上当前曲目时先暂存，并乐观回写 UI 位置
     *    （否则滑条松手后会先弹回旧位置——正是「seek 失灵」的观感）。
     */
    private fun requestSeek(positionMs: Long) {
        val mediaId = _queue.getOrNull(_index)?.mediaId ?: return
        val target = clampSeek(positionMs)
        if (!engineReady) {
            deferredSeek = mediaId to target
            updateState { it.copy(positionMs = target) }
            return
        }
        // 无损流：本地副本已就绪、但引擎还在放流 ⇒ 先切到本地文件再 seek。
        // 桌面引擎定位不了 FLAC over HTTP，只有本地文件拖得动。而「拖动」本来就是一个
        // 不连续点，所以这次重新 load 带出的一声咔哒在预期之内 —— 用它换来的是
        // **正常听歌全程零中断**（否则就只能在「下载完成时立刻切」和「等整曲下完再播」之间选）。
        val local = localized?.takeIf { it.first == mediaId }?.second
        if (local != null && !playingFromLocal) {
            // 同步置位：切换要重新 load，这期间的拖动一律暂存，
            // 否则会被 load 的起始位置覆盖掉（「拖了没反应」的老毛病）。
            playingFromLocal = true
            engineReady = false
            deferredSeek = null
            // 乐观回写：load 期间引擎位置还是 0，不接管滑条会先弹回再跳过去。
            updateState { it.copy(positionMs = target) }
            scope.launch { switchToLocal(mediaId, local, target) }
            return
        }
        deferredSeek = null
        platform.seekTo(target)
    }

    /**
     * 后台把无损流落盘。**不阻塞播放** —— 调用时引擎已经在放流了。
     *
     * 完成后只把路径记进 [localized]，**不立刻切源**（换源会有咔哒声）。
     * 真正的切换发生在用户第一次拖动时，见 [requestSeek]。
     */
    private fun startBackgroundLocalize(
        mediaId: String,
        cacheKey: String,
        url: String,
        headers: Map<String, String>,
        gen: Int,
    ) {
        localizeJob?.cancel()
        updateState { it.copy(isLocalizing = true) }
        localizeJob = scope.launch {
            val path = streamLocalizer.localize(url, cacheKey, headers)
            // 期间用户可能已经切歌：结果只能落到它自己那一首头上。
            if (loadGeneration != gen) return@launch
            if (path != null) localized = mediaId to path
            // 成败都要收回提示：失败时本曲就是「能播但不能拖」，不能一直挂着「缓存中」。
            updateState { it.copy(isLocalizing = false) }
        }
    }

    /**
     * 把引擎从「放流」切到「放本地副本」，并落到 [target]。
     *
     * 只在用户第一次拖动无损曲目时调用（见 [requestSeek]）。复用 [lastMetadata] /
     * [lastHeaders]，让这次重新 load 与首次装载在元信息上完全一致。
     */
    private suspend fun switchToLocal(mediaId: String, path: String, target: Long) {
        val gen = loadGeneration
        try {
            platform.load(
                path,
                startPositionMs = target,
                headers = lastHeaders,
                metadata = lastMetadata,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 切本地失败（典型：副本刚好被 LRU 淘汰）。
            // 必须自己兜住 —— 这是挂在 scope 上的独立协程，异常冒出去就是未捕获异常。
            if (loadGeneration != gen) return
            // 退回「放流、不可拖」的原状态：让后续 seek 走正常的失败上报（Snackbar），
            // 而不是反复重试一个已经不存在的文件。
            playingFromLocal = false
            localized = null
            engineReady = true
            platform.seekTo(target)
            return
        }
        if (loadGeneration != gen) return
        engineReady = true
        // 切换途中又拖了一次：此时引擎已装上本地文件，补发即可生效。
        consumeDeferredSeek(mediaId)?.let { platform.seekTo(it) }
    }

    /** 把目标位置钳制到 `[0, 有效时长]`；时长未知时只保证非负。 */
    private fun clampSeek(positionMs: Long): Long {
        val target = positionMs.coerceAtLeast(0L)
        val duration = effectiveDurationMs()
        return if (duration > 0L) target.coerceIn(0L, duration) else target
    }

    /**
     * 有效时长：优先用引擎上报值，缺失时回落到曲目元信息。
     *
     * 进度条刻度与 seek 钳制都依赖它——为 0 时滑条范围会塌成 `0..1`，
     * 拖动等于没法用。引擎时长在流媒体首包到达前是未知的，元信息则是立刻可得的。
     */
    private fun effectiveDurationMs(): Long =
        _state.value.durationMs.takeIf { it > 0L }
            ?: _state.value.currentTrack?.durationMs?.takeIf { it > 0L }
            ?: 0L

    /**
     * 取出并清空属于 [mediaId] 的暂存 seek；没有则返回 null。
     *
     * 不属于本曲的暂存（用户拖完 A 又切到 B）会被一并丢弃，绝不串到新曲目上。
     */
    private fun consumeDeferredSeek(mediaId: String): Long? {
        val pending = deferredSeek
        deferredSeek = null
        return pending?.takeIf { it.first == mediaId }?.second
    }

    override fun skipNext() {
        val seqAtEntry = navigationSeq
        scope.launch {
            var next: Int? = null
            var stale = false
            navMutex.withLock {
                // 期间若已发生别的导航（如刚自动续播过），本次点击作废，避免连跳两首。
                if (navigationSeq != seqAtEntry) { stale = true; return@withLock }
                next = computeNext(autoAdvance = true)
                next?.let { n -> _index = n; _orderPos = _order?.indexOf(n) ?: n }
            }
            if (stale) return@launch
            val target = next
            if (target == null) {
                engineReady = false
                deferredSeek = null
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
            // 3 秒内回到上一首；超过 3 秒回退到本曲开头
            if (platform.positionMs.value > 3_000L) {
                requestSeek(0L)
                return@launch
            }
            var prev: Int? = null
            var stale = false
            navMutex.withLock {
                if (navigationSeq != seqAtEntry) { stale = true; return@withLock }
                prev = computePrev()
                prev?.let { p -> _index = p; _orderPos = _order?.indexOf(p) ?: p }
            }
            if (stale) return@launch
            val target = prev
            if (target == null) {
                requestSeek(0L)
                return@launch
            }
            pushQueueState()
            playCurrent()
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
        val parsed = runCatching { cp.player.core.music.CPMediaId.parse(mediaId) }.getOrNull() ?: return
        if (parsed.providerId == "local") return
        val id = parsed.resourceId
        ensureFavoritesLoaded()
        favoritesLoadingJob?.join() // 等待加载完成，避免新状态被旧列表覆盖
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
        val parsed = runCatching { cp.player.core.music.CPMediaId.parse(mediaId) }.getOrNull() ?: return
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
            updateState { it.copy(lyrics = LyricsState.Loading, lyricsInfo = null, activeLyricIndex = -1) }
            val parsed = try {
                val id = cp.player.core.music.CPMediaId.parse(mediaId)
                if (id.providerId == "local") {
                    updateState { it.copy(lyrics = LyricsState.NoLyrics, lyricsInfo = null) }
                    return@launch
                }
                val json = api.getLyric(id.resourceId)
                val lines = LyricsParser.parse(json)
                val info = extractLyricsInfo(json, lines)
                val state = if (lines.isEmpty()) LyricsState.NoLyrics else LyricsState.Success(lines)
                state to info
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 被新一轮 refreshLyrics / 切歌取消 ⇒ 这是「预期中的结束」，不是失败。
                // 必须原样抛出：写成 LyricsState.Error 会盖掉新曲目的 Loading，
                // 表现为切歌瞬间闪一下「歌词获取失败」。
                throw e
            } catch (e: Throwable) {
                LyricsState.Error(e.message ?: "歌词获取失败") to null
            }
            val pos = platform.positionMs.value
            updateState {
                it.copy(
                    lyrics = parsed.first,
                    lyricsInfo = parsed.second,
                    activeLyricIndex = computeLyricIndex(parsed.first, pos),
                )
            }
        }
    }

    // ============ 音量 / 释放 ============

    override fun setVolume(volume: Float) { platform.setVolume(volume.coerceIn(0f, 1f)) }

    override fun release() {
        scrobbleTickJob?.cancel(); scrobbleJob?.cancel(); lyricsJob?.cancel(); loadJob?.cancel()
        sleepTimerJob?.cancel(); favoritesLoadingJob?.cancel(); resolveJob?.cancel()
        queueGeneration++
        engineReady = false
        deferredSeek = null
        platform.release()
    }

    // ============ 内部 ============

    /** 获取 URL 并交给平台播放器播放当前曲目。 */
    private suspend fun playCurrent() {
        val entry = _queue.getOrNull(_index) ?: return
        // 每次加载新曲目都推进导航世代：在此之前的 Ended / 切歌请求都会因世代不匹配而作废。
        navigationSeq += 1
        // 引擎里此刻装的还是上一首：load() 返回之前到达的 seek 必须暂存，
        // 否则会被 load(startPositionMs = 0) 覆盖掉。
        engineReady = false
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
                    // 必须显式复位：从「正在落盘的无损曲」切到「有损曲」时，
                    // 上一首的 true 会一直挂着，进度条被永久禁用且提示不消失。
                    isLocalizing = false,
                )
            )
            if (summary == null) {
                // 加载失败：清掉暂存的 seek。留着会让位置采集器一直屏蔽引擎位置，
                // 进度条被永久冻在乐观值上。
                deferredSeek = null
                return@launch
            }
            ensureFavoritesLoaded()
            // 解析失败只影响收藏态展示，绝不能中断加载——否则整首歌都播不出来。
            val resourceId = runCatching { cp.player.core.music.CPMediaId.parse(mediaId).resourceId }.getOrNull()
            emit(_state.value.copy(
                isFavorite = resourceId != null && resourceId in _likedIds.value,
            ))
            val urlResult = source.getSongUrl(mediaId, level = qualityLevel)
            val songUrl = urlResult.getOrNull()
            if (songUrl == null || songUrl.url.isBlank()) {
                // 解析不出播放地址：同样清掉暂存 seek，避免位置被永久冻住。
                deferredSeek = null
                emit(_state.value.copy(
                    isBuffering = false,
                    error = (urlResult as? cp.player.core.BackendResult.Error)?.message ?: "无法获取播放地址"
                ))
                return@launch
            }
            val headers = buildMap {
                songUrl.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                if (!containsKey("Cookie")) {
                    cookieProvider()?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                }
            }
            // 装载窗口内用户拖过的进度：作为起始位置一并交给引擎，
            // 否则这次 seek 会被 load() 的默认 0 直接覆盖掉（「拖了没反应」）。
            // 注意必须在这里取而不是协程开头——用户的 seek 通常发生在
            // 「协程已启动、播放地址还没解析出来」这段时间里。
            val startAt = consumeDeferredSeek(mediaId) ?: 0L
            // 无损档位最终必须播**本地文件**，引擎才定位得动（桌面 rodio 无法定位
            // FLAC over HTTP，见 [StreamLocalizer]）。但**绝不等整曲下完才开播**：
            // 命中缓存就直接播本地；未命中就先用流立刻开播，落盘丢到后台并行做。
            val cacheKey = cacheKeyOf(mediaId, qualityLevel)
            val localizing = streamLocalizer.isLocalizing(qualityLevel)
            val cachedLocal = if (localizing) streamLocalizer.cachedPath(cacheKey) else null
            // 换曲即作废上一首的后台落盘与「可切本地」状态，避免串曲。
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
                // 磁盘缓存的稳定键：CDN 刷新鉴权 token 会换 URL，
                // 用 mediaId@音质当键才能让 seek 回退命中已下载区间。
                cacheKey = cacheKey,
            )
            lastHeaders = headers
            lastMetadata = metadata
            try {
                platform.load(
                    cachedLocal ?: songUrl.url,
                    startPositionMs = startAt,
                    headers = headers,
                    metadata = metadata,
                )
                if (loadGeneration == gen) {
                    engineReady = true
                    // load 途中又拖了一次：此时引擎已装上本曲，补发即可生效。
                    consumeDeferredSeek(mediaId)?.let { platform.seekTo(it) }
                }
                platform.play()
                // 开播之后才起后台落盘：不占首帧出声的时间。
                if (localizing && cachedLocal == null) {
                    startBackgroundLocalize(mediaId, cacheKey, songUrl.url, headers, gen)
                }
                refreshLyrics()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                // 播放失败 → 自动降级到 standard 音质重试一次（JavaFX/格式问题/网络等均适用）
                if (qualityLevel != "standard") {
                    val retried = retryWithStandardQuality(mediaId)
                    if (retried) return@launch
                }
                // 播放彻底失败：清掉暂存 seek，别把进度条冻在乐观值上。
                deferredSeek = null
                emit(_state.value.copy(isBuffering = false, error = msg))
            }
        }
    }

    /**
     * 磁盘流缓存的稳定键：`<mediaId>@<音质>`。
     *
     * 不含 URL，因为 CDN 的鉴权参数会过期换新；音质必须进键，
     * 否则切换音质后会命中另一音质的缓存字节，直接串音。
     */
    private fun cacheKeyOf(mediaId: String, level: String): String = "$mediaId@$level"

    /** 格式不支持时降级到 standard 音质重试。成功返回 true。 */
    private suspend fun retryWithStandardQuality(mediaId: String): Boolean {
        updateState { it.copy(error = "音质降级中…") }
        return try {
            val fallback = source.getSongUrl(mediaId, level = "standard").getOrNull() ?: return false
            if (fallback.url.isBlank()) return false
            val headers = buildMap {
                fallback.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                if (!containsKey("Cookie")) cookieProvider()?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            }
            val summary = _queue.getOrNull(_index)?.summary
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
                        // 降级后音质变了，缓存键必须跟着变，否则会命中原音质的缓存字节。
                        cacheKey = cacheKeyOf(mediaId, "standard"),
                    )
                },
            )
            // 降级装载完成：引擎已装上本曲，此后 seek 可以直接下发。
            engineReady = true
            platform.play()
            refreshLyrics()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun onTrackEnded() {
        val seqAtEnd = navigationSeq
        scope.launch {
            // 丢弃过期/并发的 Ended：若期间已开始新的加载（用户切歌、换队列、手动重播），
            // 这个 Ended 已无意义，继续推进会把刚切过去的歌再跳掉一首。
            // 注意：只按世代判定，不要额外要求平台此刻仍处于 Ended——
            // 桌面端轮询会在 200ms 内把 Ended 覆写成 Idle，那样会把正常的自动续播也一起丢掉。
            if (navigationSeq != seqAtEnd) return@launch
            if (sleepAfterTrack) {
                // 睡眠定时：播完当前后暂停，不再自动续播
                cancelSleepTimer()
                updateState { it.copy(isPlaying = false) }
                return@launch
            }
            when (_repeat) {
                RepeatMode.ONE -> {
                    requestSeek(0L); platform.play()
                }
                RepeatMode.ALL, RepeatMode.OFF -> {
                    var next: Int? = null
                    var stale = false
                    navMutex.withLock {
                        if (navigationSeq != seqAtEnd) { stale = true; return@withLock }
                        next = computeNext(autoAdvance = true)
                        next?.let { n -> _index = n; _orderPos = _order?.indexOf(n) ?: n }
                    }
                    if (stale) return@launch
                    val target = next
                    if (target == null) {
                        updateState { it.copy(isPlaying = false, positionMs = 0L) }
                        return@launch
                    }
                    pushQueueState()
                    playCurrent()
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

    private fun extractLyricsInfo(json: JsonElement, lines: List<SyncedLyricLine>): LyricsInfo {
        val obj = json as? JsonObject
        val yrc = ((obj?.get("yrc") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
        val tlyric = ((obj?.get("tlyric") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
        val romalrc = ((obj?.get("romalrc") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
        val lrc = ((obj?.get("lrc") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("lyric") as? JsonPrimitive)?.contentOrNull
            ?: ((obj?.get("klyric") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull

        val format = when {
            !yrc.isNullOrBlank() -> "YRC"
            !lrc.isNullOrBlank() -> "LRC"
            else -> "Unknown"
        }
        val hasWordLevel = lines.any { it.words.isNotEmpty() }
        val hasTranslation = lines.any { !it.translation.isNullOrBlank() } || !tlyric.isNullOrBlank()
        val hasPhonetic = lines.any { !it.romanization.isNullOrBlank() } || !romalrc.isNullOrBlank()
        return LyricsInfo(
            source = "MusicApiService.getLyric",
            format = format,
            hasWordLevel = hasWordLevel,
            hasTranslation = hasTranslation,
            hasPhonetic = hasPhonetic,
        )
    }

    private fun ensureOrderScopeSafe() { /* placeholder for future constraints */ }

    // ============ 队列解析（懒解析） ============

    private fun resolveQueueInBackground(startFrom: Int) {
        // 取消上一轮解析：换队列后旧任务的下标已失效，继续写会污染新队列。
        resolveJob?.cancel()
        val gen = ++queueGeneration

        val toResolveIndices = _queue.indices.sortedBy { if (it == startFrom) 0 else 1 + kotlin.math.abs(it - startFrom) }
        val toResolveEntries = toResolveIndices.filter { _queue.getOrNull(it)?.summary == null }
        if (toResolveEntries.isEmpty()) return

        resolveJob = scope.launch {
            for (chunk in toResolveEntries.chunked(50)) {
                if (queueGeneration != gen) return@launch
                // 用 getOrNull：队列可能在请求途中被删减，直接下标访问会越界崩溃。
                val mediaIds = chunk.mapNotNull { _queue.getOrNull(it)?.mediaId }
                if (mediaIds.isEmpty()) continue
                val result = source.getTrackDetails(mediaIds).getOrNull() ?: emptyList()
                val map = result.associateBy { it.id }
                if (queueGeneration != gen) return@launch

                // 遍历 _queue.indices 以防止 O(N^2) 查找并支持队列中的重复曲目。
                var changed = false
                for (idx in _queue.indices) {
                    val entry = _queue[idx]
                    if (entry.summary == null) {
                        val summary = map[entry.mediaId]
                        if (summary != null) {
                            entry.summary = summary
                            if (idx == _index) {
                                updateState { it.copy(currentTrack = summary, currentIndex = _index, durationMs = summary.durationMs.takeIf { d -> d > 0 } ?: it.durationMs) }
                            }
                            changed = true
                        }
                    }
                }
                if (changed) pushQueueState()
            }
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
        val id = runCatching { cp.player.core.music.CPMediaId.parse(mediaId) }.getOrNull() ?: return
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
private fun <T> cp.player.core.BackendResult<T>.getOrNull(): T? =
    (this as? cp.player.core.BackendResult.Success)?.data