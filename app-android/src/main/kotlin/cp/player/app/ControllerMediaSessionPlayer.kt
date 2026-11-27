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
import kotlinx.coroutines.delay
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

    /**
     * 用户/系统的「播放 / 暂停」意图，在控制器状态追上之前由它接管 [getState] 的
     * `playWhenReady`。
     *
     * ### 为什么必须有这一层
     * [handleSetPlayWhenReady] 只能**发起**动作：`PlaybackController.resume()` → `platform.play()`
     * → 引擎线程 → StateFlow，翻成 `isPlaying = true` 是异步的（Flick 至少要等一次引擎事件）。
     * 而 media3 的 `SimpleBasePlayer` 在每次 `invalidateState()` 时都用 [getState] 的返回值
     * 整体覆盖内部状态：控制器还没翻过来的那一刻，[getState] 会报 `playWhenReady = false`，
     * 刚按下的「播放」被自己覆盖回去 —— 通知栏/蓝牙耳机要按**第二次**才真正恢复，
     * 表现就是「只能暂停、恢复要点两下」。
     *
     * 状态追上（`isPlaying` 与意图一致 / 已进入缓冲）或出错、换曲时清掉，交由真实状态说话。
     */
    private var pendingPlayWhenReady: Boolean? = null

    /**
     * [pendingPlayWhenReady] 的兜底定时器。
     *
     * 有些「播放」请求本来就不会让状态变化：队列已播完时 [PlaybackController.resume] 只是
     * 对已结束的引擎再 `play()` 一次，什么都不发生，也没有新的状态发射把乐观值撤掉。
     * 不设超时，通知栏会一直显示「正在播放」。
     */
    private var pendingWatchdog: Job? = null

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
        settlePending()
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

        // 控制器状态追上用户的播放/暂停意图后，撤掉乐观值；撤下的这一帧必须重绘，
        // 否则通知栏会停在「按意图画出来的那个图标」上。
        val pendingCleared = clearSettledPending()

        if (key != lastSnapshotKey || posJumped || pendingCleared) {
            lastSnapshotKey = key
            lastSyncedPositionMs = ui.positionMs.coerceAtLeast(0L)
            lastSyncedWallMs = nowMs
            invalidateOnMain()
        }
    }

    /** 真实状态是否已经能替 [pendingPlayWhenReady] 说话了：能就清掉并返回 true。 */
    private fun clearSettledPending(): Boolean {
        val pending = pendingPlayWhenReady ?: return false
        val ui = latestUiState
        val settled = if (pending) {
            ui.isPlaying || ui.isBuffering
        } else {
            !ui.isPlaying && !ui.isBuffering
        }
        if (!settled && ui.error == null && ui.currentTrack != null) return false
        settlePending()
        return true
    }

    /** 撤掉乐观播放意图（含兜底定时器）。 */
    private fun settlePending() {
        pendingPlayWhenReady = null
        pendingWatchdog?.cancel()
        pendingWatchdog = null
    }

    private fun markPendingPlayWhenReady(playWhenReady: Boolean) {
        pendingPlayWhenReady = playWhenReady
        pendingWatchdog?.cancel()
        pendingWatchdog = scope.launch(Dispatchers.Main.immediate) {
            delay(PENDING_PLAY_INTENT_TIMEOUT_MS)
            if (pendingPlayWhenReady != null) {
                settlePending()
                invalidateState()
            }
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
        val playWhenReady = pendingPlayWhenReady ?: (ui.isPlaying || (ui.isBuffering && track != null))
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
            // 先记意图再发起：恢复是异步的，期间 getState 必须按意图报，见 pendingPlayWhenReady。
            markPendingPlayWhenReady(playWhenReady)
            if (playWhenReady) c.resume() else c.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val c = controllerProvider()
        if (c != null) {
            markPendingPlayWhenReady(true)
            c.resume()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        val c = controllerProvider()
        if (c != null) {
            markPendingPlayWhenReady(false)
            c.pause()
        }
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

        /** 乐观播放意图的最长存活时间：再久说明这次「播放/暂停」本来就不会有状态变化。 */
        const val PENDING_PLAY_INTENT_TIMEOUT_MS = 2000L
    }
}
