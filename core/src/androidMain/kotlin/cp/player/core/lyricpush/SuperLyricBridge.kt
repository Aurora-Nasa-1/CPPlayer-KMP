/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/SuperLyricBridge.kt
 * Adapted for SuperLyricApi 4.0: publish one complete Lyric document per track/lyrics change;
 * the service computes the current row from its timeline. API-model-independent mapping lives in
 * commonMain (SuperLyricPayload.kt) so the contract can be unit tested on desktop.
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.os.SystemClock
import android.util.Log
import com.hchen.superlyricapi.Publisher
import com.hchen.superlyricapi.SuperLyric
import com.hchen.superlyricapi.model.Line as ApiLine
import com.hchen.superlyricapi.model.Lyric as ApiLyric
import com.hchen.superlyricapi.model.Word as ApiWord
import cp.player.core.playback.SyncedLyricLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SuperLyricApi 4.0 publisher bridge.
 *
 * The 4.0 contract accepts a whole-song document once; it no longer exposes the 3.x
 * registerPublisher/sendLyric/sendStop broadcast API. The receiving service derives the active
 * row from the document timeline, so playback frames do not trigger per-line IPC.
 */
internal class SuperLyricBridge(
    private val scope: CoroutineScope,
) {

    private var enabled = false
    private var secondary = LyricSecondaryMode.TRANSLATION

    private var lastTrack: LyricPushTrack? = null
    private var lastLines: List<SyncedLyricLine> = emptyList()
    private var targetPayload: SuperLyricPayload? = null
    private var lastPublishedPayload: SuperLyricPayload? = null
    private var hasPublished = false

    private var retryJob: Job? = null
    private var consecutiveFailures = 0
    private var retryAfterElapsedMs = 0L

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        cancelRetry()
        resetRetryState()
        if (enabled) {
            publishCurrent(force = true)
        } else {
            sendStop()
        }
    }

    fun setSecondary(mode: LyricSecondaryMode) {
        if (secondary == mode) return
        secondary = mode
        refreshTarget(force = true)
    }

    /** Cache and publish the complete song when its metadata or lyrics change. */
    fun sendSong(
        track: LyricPushTrack,
        lines: List<SyncedLyricLine>,
        force: Boolean = false,
    ) {
        val previousTrackKey = lastTrack?.trackKey()
        val trackChanged = track.trackKey() != previousTrackKey
        lastTrack = track
        lastLines = lines.toList()
        val next = buildSuperLyricPayload(track, lastLines, secondary)
        val contentChanged = next != targetPayload

        if (!trackChanged && !contentChanged && !force && next == lastPublishedPayload) return
        targetPayload = next
        if (next == null) {
            sendStop()
            return
        }
        if (trackChanged || contentChanged) lastPublishedPayload = null
        cancelRetry()
        resetRetryState()
        publishCurrent(force = true)
    }

    /** Clear on explicit stop/disable/teardown, or when the new track has no valid lyric document. */
    fun sendStop() {
        cancelRetry()
        if (hasPublished) {
            runCatching { SuperLyric.publisher().clear() }
                .onFailure { Log.w(TAG, "SuperLyric clear failed", it) }
        }
        hasPublished = false
        lastPublishedPayload = null
        resetRetryState()
    }

    fun destroy() {
        enabled = false
        sendStop()
        lastTrack = null
        lastLines = emptyList()
        targetPayload = null
    }

    private fun refreshTarget(force: Boolean) {
        val track = lastTrack ?: return
        val next = buildSuperLyricPayload(track, lastLines, secondary)
        if (next == targetPayload && (!force || next == lastPublishedPayload)) return
        targetPayload = next
        if (next == null) {
            sendStop()
            return
        }
        if (next != lastPublishedPayload) lastPublishedPayload = null
        cancelRetry()
        resetRetryState()
        publishCurrent(force = force)
    }

    private fun publishCurrent(force: Boolean) {
        if (!enabled) return
        val payload = targetPayload ?: run {
            val track = lastTrack ?: return
            buildSuperLyricPayload(track, lastLines, secondary)?.also { targetPayload = it } ?: return
        }
        if (!force && payload == lastPublishedPayload) return

        val now = SystemClock.elapsedRealtime()
        if (now < retryAfterElapsedMs) {
            scheduleRetry()
            return
        }

        val result = runCatching { SuperLyric.publisher().publish(payload.toApiLyric()) }
            .getOrElse { cause ->
                recordFailure("publish", cause)
                scheduleRetry()
                return
            }

        when (result) {
            Publisher.PublishResult.OK -> {
                hasPublished = true
                lastPublishedPayload = payload
                cancelRetry()
                resetRetryState()
            }
            Publisher.PublishResult.SERVICE_UNAVAILABLE,
            Publisher.PublishResult.REJECTED_TRACK_MISMATCH -> {
                recordFailure("publish ($result)")
                scheduleRetry()
            }
            else -> {
                // These results are deterministic for the current document (e.g. size, malformed
                // payload, or quota). Retry on a later track/lyrics change rather than resending
                // the same rejected document in the background.
                cancelRetry()
                Log.w(TAG, "SuperLyric rejected document: $result")
            }
        }
    }

    private fun scheduleRetry() {
        if (!enabled || targetPayload == null) return
        cancelRetry()
        val waitMs = (retryAfterElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        retryJob = scope.launch {
            delay(waitMs)
            retryJob = null
            publishCurrent(force = true)
        }
    }

    private fun recordFailure(operation: String, cause: Throwable? = null) {
        consecutiveFailures++
        val delayMs = superLyricRetryDelayMs(consecutiveFailures)
        retryAfterElapsedMs = SystemClock.elapsedRealtime() + delayMs
        Log.w(
            TAG,
            "SuperLyric $operation unavailable; retry in ${delayMs / 1000}s" +
                cause?.let { " (${it.javaClass.simpleName}: ${it.message.orEmpty()})" }.orEmpty(),
        )
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun resetRetryState() {
        consecutiveFailures = 0
        retryAfterElapsedMs = 0L
    }

    private fun SuperLyricPayload.toApiLyric(): ApiLyric = ApiLyric(
        id = id,
        title = title,
        artist = artist,
        album = album,
        duration = durationMs,
        lines = lines.map { line ->
            ApiLine(
                text = line.text,
                start = line.startMs,
                end = line.endMs,
                words = line.words.takeIf { it.isNotEmpty() }?.map { word ->
                    ApiWord(word.text, word.startMs, word.endMs)
                },
                translation = line.translation,
                phonetic = line.phonetic,
            )
        },
    )

    private companion object {
        const val TAG = "SuperLyricBridge"
    }
}
