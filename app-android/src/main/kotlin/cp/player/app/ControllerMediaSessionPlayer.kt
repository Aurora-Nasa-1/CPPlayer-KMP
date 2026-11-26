package cp.player.app

import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 将 [PlaybackController]（无论底层当前跑的是 Media3 还是 Rust FlickEngine）
 * 统一暴露为 Media3 `Player` 供给 [PlaybackMediaSessionService] 的 `MediaSession`：
 *
 * - 切到 FlickEngine 时，系统媒体通知、锁屏封面、进度条、蓝牙 AVRCP 与 ColorOS 状态栏歌词全部同步工作；
 * - 切回 Media3 时行为无缝衔接；
 * - [release] 为空操作，避免销毁 Service 时误停共享控制器。
 */
internal class ControllerMediaSessionPlayer(
    looper: Looper,
    private val scope: CoroutineScope,
    private val controllerProvider: () -> PlaybackController?,
) : SimpleBasePlayer(looper) {

    private data class LyricOverlay(
        val trackId: String? = null,
        val title: String? = null,
        val subtitle: String? = null,
        val extras: Map<String, String> = emptyMap(),
    )

    private data class SnapshotKey(
        val trackId: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val coverUrl: String?,
        val isPlaying: Boolean,
        val isBuffering: Boolean,
        val durationMs: Long,
        val repeatMode: RepeatMode,
        val shuffleEnabled: Boolean,
        val speed: Float,
        val lyricOverlay: LyricOverlay,
    )

    private var latestUiState: PlaybackUiState = PlaybackUiState()
    private var lyricOverlay: LyricOverlay = LyricOverlay()
    private var lastSnapshotKey: SnapshotKey? = null
    private var lastSyncedPositionMs: Long = 0L
    private var lastSyncedWallMs: Long = 0L
    private var observeJob: Job? = null

    fun startObserving() {
        if (observeJob?.isActive == true) return
        val controller = runCatching { controllerProvider() }.getOrNull() ?: return
        latestUiState = controller.state.value
        observeJob = scope.launch(Dispatchers.Main.immediate) {
            controller.state.collectLatest { ui ->
                onControllerState(ui)
            }
        }
        invalidateOnMain()
    }

    fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
    }

    fun setLyricOverlay(title: String?, subtitle: String?, extras: Map<String, String>) {
        val trackId = latestUiState.currentTrack?.id
        val normalizedExtras = extras.filterValues { it.isNotBlank() }
        val next = LyricOverlay(
            trackId = trackId,
            title = title?.takeIf { it.isNotBlank() },
            subtitle = subtitle?.takeIf { it.isNotBlank() },
            extras = normalizedExtras,
        )
        if (next == lyricOverlay) return
        lyricOverlay = next
        invalidateOnMain()
    }

    private fun onControllerState(ui: PlaybackUiState) {
        val prevTrackId = latestUiState.currentTrack?.id
        val nextTrackId = ui.currentTrack?.id
        if (nextTrackId != prevTrackId) {
            lyricOverlay = LyricOverlay(trackId = nextTrackId)
        }
        latestUiState = ui

        val durationMs = effectiveDurationMs(ui)
        val key = SnapshotKey(
            trackId = nextTrackId,
            title = ui.currentTrack?.name,
            artist = ui.currentTrack?.artist,
            album = ui.currentTrack?.album,
            coverUrl = ui.currentTrack?.coverUrl,
            isPlaying = ui.isPlaying,
            isBuffering = ui.isBuffering,
            durationMs = durationMs,
            repeatMode = ui.repeatMode,
            shuffleEnabled = ui.shuffleEnabled,
            speed = ui.playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f,
            lyricOverlay = lyricOverlay,
        )

        val nowMs = System.nanoTime() / 1_000_000L
        val expectedPosMs = if (lastSnapshotKey?.isPlaying == true) {
            lastSyncedPositionMs + ((nowMs - lastSyncedWallMs) * key.speed).toLong()
        } else {
            lastSyncedPositionMs
        }
        val posJumped = abs(ui.positionMs - expectedPosMs) > POSITION_SYNC_THRESHOLD_MS

        if (key != lastSnapshotKey || posJumped) {
            lastSnapshotKey = key
            lastSyncedPositionMs = ui.positionMs.coerceAtLeast(0L)
            lastSyncedWallMs = nowMs
            invalidateOnMain()
        }
    }

    private fun invalidateOnMain() {
        if (Looper.myLooper() == applicationLooper) {
            invalidateState()
        } else {
            scope.launch(Dispatchers.Main.immediate) {
                invalidateState()
            }
        }
    }

    override fun getState(): State {
        val ui = runCatching { controllerProvider()?.state?.value }.getOrNull() ?: latestUiState
        latestUiState = ui
        val track = ui.currentTrack
        val overlay = lyricOverlay.takeIf { it.trackId == track?.id }
        val durationMs = effectiveDurationMs(ui)
        val posMs = ui.positionMs.coerceAtLeast(0L).let { pos ->
            if (durationMs > 0L) pos.coerceAtMost(durationMs) else pos
        }
        val speed = ui.playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f

        val playlist = if (track != null) {
            val metaBuilder = MediaMetadata.Builder()
                .setTitle(overlay?.title ?: track.name)
                .setArtist(overlay?.subtitle ?: track.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.coverUrl?.let(Uri::parse))
            val extrasMap = overlay?.extras.orEmpty()
            if (extrasMap.isNotEmpty()) {
                val bundle = Bundle()
                extrasMap.forEach { (k, v) -> bundle.putString(k, v) }
                metaBuilder.setExtras(bundle)
            }
            val metadata = metaBuilder.build()
            val mediaItem = MediaItem.Builder()
                .setMediaId(track.id)
                .setMediaMetadata(metadata)
                .build()
            val durationUs = if (durationMs > 0L) androidx.media3.common.util.Util.msToUs(durationMs) else C.TIME_UNSET
            listOf(
                MediaItemData.Builder(track.id)
                    .setMediaItem(mediaItem)
                    .setMediaMetadata(metadata)
                    .setDurationUs(durationUs)
                    .setIsSeekable(durationMs > 0L)
                    .build(),
            )
        } else {
            emptyList()
        }

        val playbackState = when {
            track == null -> Player.STATE_IDLE
            ui.isBuffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        val playWhenReady = ui.isPlaying || (ui.isBuffering && track != null)
        val repeat = when (ui.repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }

        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_VOLUME,
            )
            .build()

        val positionSupplier = if (ui.isPlaying) {
            PositionSupplier.getExtrapolating(posMs, speed)
        } else {
            PositionSupplier.getConstant(posMs)
        }

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (playlist.isNotEmpty()) 0 else C.INDEX_UNSET)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setIsLoading(ui.isBuffering)
            .setRepeatMode(repeat)
            .setShuffleModeEnabled(ui.shuffleEnabled)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setContentPositionMs(positionSupplier)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setVolume(ui.volume.coerceIn(0f, 1f))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val c = controllerProvider()
        if (c != null) {
            if (playWhenReady) c.resume() else c.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        controllerProvider()?.resume()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controllerProvider()?.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val c = controllerProvider()
        if (c != null) {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> c.skipNext()
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> c.skipPrevious()
                else -> {
                    val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
                    lastSyncedPositionMs = target
                    lastSyncedWallMs = System.nanoTime() / 1_000_000L
                    c.seekTo(target)
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        controllerProvider()?.setPlaybackSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeFlags: Int): ListenableFuture<*> {
        controllerProvider()?.setVolume(volume.coerceIn(0f, 1f))
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        val mode = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        controllerProvider()?.setRepeatMode(mode)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        val c = controllerProvider()
        if (c != null && c.state.value.shuffleEnabled != shuffleModeEnabled) {
            c.toggleShuffle()
        }
        return Futures.immediateVoidFuture()
    }

    private fun effectiveDurationMs(ui: PlaybackUiState): Long {
        val trackDur = ui.currentTrack?.durationMs ?: 0L
        return if (ui.durationMs > 0L) ui.durationMs else trackDur.coerceAtLeast(0L)
    }

    private companion object {
        const val POSITION_SYNC_THRESHOLD_MS = 1200L
    }
}
