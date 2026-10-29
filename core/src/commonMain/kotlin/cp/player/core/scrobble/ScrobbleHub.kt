package cp.player.core.scrobble

import cp.player.core.util.SettingsStorage
import cp.player.core.util.currentTimeMillis
import cp.player.core.util.defaultSettingsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 听歌打卡与外部同步中心（通用调度层）。
 *
 * 统一管理所有已注册的外部打卡服务（Last.fm 等），
 * 负责打卡门槛判定、生命周期防重、离线待办队列落盘与重试。
 */
class ScrobbleHub(
    val lastFm: LastFmScrobbler = LastFmScrobbler(),
    private val storage: SettingsStorage = defaultSettingsStorage("scrobble_hub_queue"),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val scrobblers: List<ExternalScrobbler> = listOf(lastFm)

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(
        ScrobbleHubState(
            isEnabled = lastFm.isEnabled,
            lastFmConnected = lastFm.isAuthorized,
            lastFmUsername = lastFm.username,
            todayScrobbledCount = loadTodayScrobbleCount(),
            pendingQueueSize = loadPendingQueue().size,
        )
    )
    val state: StateFlow<ScrobbleHubState> = _state.asStateFlow()

    // 播放追踪内部状态
    private var currentMediaId: String? = null
    private var currentTrack: ScrobbleTrack? = null
    private var playedDurationMs: Long = 0L
    private var lastTickMs: Long = 0L
    private var hasScrobbledCurrent: Boolean = false
    private var nowPlayingJob: Job? = null

    init {
        // 监听 Last.fm 状态变化
        lastFm.statusFlow.onEach { status ->
            _state.update { current ->
                current.copy(
                    isEnabled = status.isEnabled,
                    lastFmConnected = status.isAuthorized,
                    lastFmUsername = status.username,
                    lastSyncError = status.lastError,
                )
            }
        }.launchIn(scope)
    }

    /**
     * 收到曲目起播或切歌事件。
     */
    fun onTrackChanged(
        mediaId: String?,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        isLocal: Boolean = false,
    ) {
        // 先保底处理上一首在途会话
        flushCurrentSessionIfNeeded()

        if (mediaId.isNullOrBlank() || title.isNullOrBlank() || artist.isNullOrBlank()) {
            currentMediaId = null
            currentTrack = null
            nowPlayingJob?.cancel()
            return
        }

        val nowSec = currentTimeMillis() / 1000L
        val durSec = (durationMs / 1000L).toInt().coerceAtLeast(0)

        currentMediaId = mediaId
        currentTrack = ScrobbleTrack(
            title = title,
            artist = artist,
            album = album,
            durationSec = durSec,
            timestampSec = nowSec,
            mediaId = mediaId,
            isLocal = isLocal,
        )
        playedDurationMs = 0L
        lastTickMs = currentTimeMillis()
        hasScrobbledCurrent = false

        // 延迟 2.5 秒发送 Now Playing，避免连续快速切歌时的无谓打扰与网络抖动
        nowPlayingJob?.cancel()
        nowPlayingJob = scope.launch {
            delay(2500L)
            val track = currentTrack ?: return@launch
            scrobblers.forEach { scrobbler ->
                if (scrobbler.isEnabled && scrobbler.isAuthorized) {
                    runCatching { scrobbler.updateNowPlaying(track) }
                }
            }
        }
    }

    /**
     * 播放进度推进（由播放状态机高频驱动）。
     */
    fun onPlaybackProgress(isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        val track = currentTrack ?: return
        val now = currentTimeMillis()

        if (isPlaying) {
            val delta = (now - lastTickMs).coerceIn(0L, 2000L)
            playedDurationMs += delta
        }
        lastTickMs = now

        // 更新时长
        val durSec = (durationMs / 1000L).toInt()
        if (durSec > 0 && track.durationSec <= 0) {
            currentTrack = track.copy(durationSec = durSec)
        }

        // 检查打卡条件：
        // 1. 时长 >= 30 秒；
        // 2. 累计有效播放时长 >= min(时长 / 2, 240 秒)；
        // 3. 当前曲目单次播放未打卡过。
        if (!hasScrobbledCurrent && isEligibleForScrobble(track.durationSec, playedDurationMs)) {
            triggerScrobble(track)
        }
    }

    /**
     * 收到曲目自然播放结束或停止。
     */
    fun onTrackEnded(reachedEnd: Boolean) {
        flushCurrentSessionIfNeeded(reachedEnd = reachedEnd)
    }

    /**
     * 标记或取消喜爱联动。
     */
    fun onFavoriteChanged(track: ScrobbleTrack, isFavorite: Boolean) {
        scope.launch {
            scrobblers.forEach { scrobbler ->
                if (scrobbler.isEnabled && scrobbler.isAuthorized) {
                    runCatching {
                        if (isFavorite) scrobbler.love(track) else scrobbler.unlove(track)
                    }
                }
            }
        }
    }

    private fun isEligibleForScrobble(durationSec: Int, playedMs: Long): Boolean {
        if (durationSec < 30) return false
        val thresholdMs = minOf((durationSec * 1000L) / 2L, 240_000L)
        return playedMs >= thresholdMs
    }

    private fun triggerScrobble(track: ScrobbleTrack) {
        if (hasScrobbledCurrent) return
        hasScrobbledCurrent = true

        scope.launch {
            dispatchScrobble(track)
        }
    }

    private fun flushCurrentSessionIfNeeded(reachedEnd: Boolean = false) {
        val track = currentTrack ?: return
        if (!hasScrobbledCurrent) {
            // 如果在播放结束前已满条件，或播到尾部满足门槛，触发打卡
            if (isEligibleForScrobble(track.durationSec, playedDurationMs) ||
                (reachedEnd && track.durationSec >= 30 && playedDurationMs >= 15_000L)
            ) {
                triggerScrobble(track)
            }
        }
    }

    private suspend fun dispatchScrobble(track: ScrobbleTrack) {
        var anySuccess = false
        scrobblers.forEach { scrobbler ->
            if (scrobbler.isEnabled && scrobbler.isAuthorized) {
                val res = runCatching { scrobbler.scrobble(track) }
                    .getOrElse { ScrobbleResult.failure(it.message) }

                if (res.success) {
                    anySuccess = true
                } else if (!res.ignored) {
                    // 网络失败等可恢复错误，加入离线待办队列
                    enqueuePending(scrobbler.id, track)
                }
            }
        }

        if (anySuccess) {
            recordSuccessScrobble(track)
        }
    }

    private fun recordSuccessScrobble(track: ScrobbleTrack) {
        val today = loadTodayScrobbleCount() + 1
        saveTodayScrobbleCount(today)

        _state.update {
            it.copy(
                todayScrobbledCount = today,
                lastScrobbledTitle = track.title,
                lastScrobbledArtist = track.artist,
                lastScrobbledTimeMs = currentTimeMillis(),
                lastSyncError = null,
            )
        }
    }

    // ============ 离线队列管理 ============

    private fun enqueuePending(serviceId: String, track: ScrobbleTrack) {
        val queue = loadPendingQueue().toMutableList()
        // 去重防爆炸
        if (queue.none { it.targetServiceId == serviceId && it.track.title == track.title && it.track.artist == track.artist && it.track.timestampSec == track.timestampSec }) {
            queue.add(
                PendingScrobble(
                    targetServiceId = serviceId,
                    track = track,
                    addedAtMs = currentTimeMillis(),
                )
            )
            savePendingQueue(queue)
            _state.update { it.copy(pendingQueueSize = queue.size) }
        }
    }

    /**
     * 立即重试离线待办队列。
     */
    fun flushPendingQueue() {
        scope.launch {
            val queue = loadPendingQueue()
            if (queue.isEmpty()) return@launch

            val nowSec = currentTimeMillis() / 1000L
            // Last.fm 官方规范：拒绝 14 天前的时间戳（超过 14 天丢弃）
            val maxAgeSec = 14 * 86400L

            val remaining = mutableListOf<PendingScrobble>()
            var flushedCount = 0

            queue.forEach { pending ->
                if ((nowSec - pending.track.timestampSec) > maxAgeSec || pending.retryCount >= 3) {
                    // 过期或超出最大重试次数，放弃
                    return@forEach
                }

                val scrobbler = scrobblers.find { it.id == pending.targetServiceId }
                if (scrobbler != null && scrobbler.isEnabled && scrobbler.isAuthorized) {
                    val res = runCatching { scrobbler.scrobble(pending.track) }
                        .getOrElse { ScrobbleResult.failure(it.message) }

                    if (res.success) {
                        flushedCount++
                    } else if (!res.ignored) {
                        remaining.add(pending.copy(retryCount = pending.retryCount + 1))
                    }
                } else {
                    remaining.add(pending)
                }
            }

            savePendingQueue(remaining)
            _state.update {
                it.copy(
                    pendingQueueSize = remaining.size,
                    todayScrobbledCount = it.todayScrobbledCount + flushedCount,
                )
            }
        }
    }

    /**
     * 清空离线队列。
     */
    fun clearPendingQueue() {
        savePendingQueue(emptyList())
        _state.update { it.copy(pendingQueueSize = 0) }
    }

    private fun loadPendingQueue(): List<PendingScrobble> {
        val raw = storage.getString(KEY_PENDING_QUEUE) ?: return emptyList()
        return runCatching { json.decodeFromString<List<PendingScrobble>>(raw) }.getOrDefault(emptyList())
    }

    private fun savePendingQueue(queue: List<PendingScrobble>) {
        runCatching {
            storage.putString(KEY_PENDING_QUEUE, json.encodeToString(queue))
        }
    }

    private fun loadTodayScrobbleCount(): Int {
        val todayKey = cp.player.core.insights.Insights.dateKeyOf(currentTimeMillis())
        val savedDay = storage.getString(KEY_TODAY_DATE)
        if (savedDay != todayKey) {
            storage.putString(KEY_TODAY_DATE, todayKey)
            storage.putString(KEY_TODAY_COUNT, "0")
            return 0
        }
        return storage.getString(KEY_TODAY_COUNT)?.toIntOrNull() ?: 0
    }

    private fun saveTodayScrobbleCount(count: Int) {
        val todayKey = cp.player.core.insights.Insights.dateKeyOf(currentTimeMillis())
        storage.putString(KEY_TODAY_DATE, todayKey)
        storage.putString(KEY_TODAY_COUNT, count.toString())
    }

    companion object {
        private const val KEY_PENDING_QUEUE = "pending_scrobbles"
        private const val KEY_TODAY_DATE = "scrobble_today_date"
        private const val KEY_TODAY_COUNT = "scrobble_today_count"
    }
}
