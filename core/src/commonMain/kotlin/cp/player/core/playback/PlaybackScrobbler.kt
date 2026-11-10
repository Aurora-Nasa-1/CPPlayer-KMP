package cp.player.core.playback

import cp.player.core.music.CPMediaId
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 听歌打卡（Scrobble）协同组件。
 *
 * 职责：
 * 1. 播放中每秒累计收听秒数，并在每 [SCROBBLE_INTERVAL_S] 秒定时上报心跳。
 * 2. 完播、切歌、暂停时结算累计收听时长。
 * 3. 驱动播放期间的定期 tick 回调（例如会话保存）。
 */
internal class PlaybackScrobbler(
    private val source: UnifiedMusicSource,
    private val scope: CoroutineScope,
    private val onPeriodicSave: () -> Unit,
) {
    private var scrobbleTickJob: Job? = null
    private var scrobbledSeconds = 0
    private var lastScrobbledSeconds = 0

    private var activeMediaId: String? = null
    private var activeSummary: TrackSummary? = null
    private var activeSourceId: String? = null

    fun onPlaying() {
        if (scrobbleTickJob?.isActive == true) return
        scrobbleTickJob = scope.launch {
            while (true) {
                delay(1_000L)
                scrobbledSeconds += 1
                if (scrobbledSeconds % SCROBBLE_INTERVAL_S == 0) {
                    flush()
                }
                if (scrobbledSeconds % PlaybackSessionStore.SESSION_SAVE_INTERVAL_S == 0) {
                    onPeriodicSave()
                }
            }
        }
    }

    fun onPaused() {
        scrobbleTickJob?.cancel()
        scrobbleTickJob = null
    }

    fun setActiveTrack(mediaId: String?, summary: TrackSummary?, sourceId: String?) {
        activeMediaId = mediaId
        activeSummary = summary
        activeSourceId = sourceId
    }

    fun updateActiveSummary(mediaId: String, summary: TrackSummary?, sourceId: String?) {
        if (activeMediaId == mediaId) {
            activeSummary = summary
            if (activeSourceId == null || activeSourceId == "0") {
                activeSourceId = sourceId ?: summary?.album ?: "0"
            }
        }
    }

    fun resetCounts() {
        scrobbledSeconds = 0
        lastScrobbledSeconds = 0
    }

    fun flush() {
        val mediaId = activeMediaId ?: return
        val summary = activeSummary
        val sourceId = activeSourceId ?: summary?.album ?: "0"
        val seconds = scrobbledSeconds
        if (seconds <= 0 || seconds == lastScrobbledSeconds) return
        val id = runCatching { CPMediaId.parse(mediaId) }.getOrNull() ?: return
        if (id.providerId == "local") return

        lastScrobbledSeconds = seconds
        scope.launch {
            runCatching {
                source.scrobble(mediaId, sourceId, seconds)
            }
        }
    }

    fun cancel() {
        scrobbleTickJob?.cancel()
        scrobbleTickJob = null
    }

    companion object {
        const val SCROBBLE_INTERVAL_S = 30
    }
}
