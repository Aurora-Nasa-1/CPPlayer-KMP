package cp.player.app.platform

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState

private var mediaSession: MediaSessionCompat? = null
private var sessionController: PlaybackController? = null

/**
 * 位置推送的节流间隔。
 *
 * `LaunchedEffect(controller, state)` 每 200 ms 被整个 `PlaybackUiState` 的更替打断一次
 * （位置轮询就是 200 ms 一轮），于是 `setPlaybackState` 也每 200 ms 跨进程调用一次。
 * 而系统媒体面板/锁屏**会按 `setState` 里的播放速率自己插值**，跟着 5 Hz 的 UI 节奏推
 * 纯属浪费 —— 而且每次都会唤醒 SystemUI 重绘通知。
 *
 * 与桌面端 `JmtcMediaControls` 的 `POSITION_PUSH_INTERVAL_MS` 保持一致。
 */
private const val POSITION_PUSH_INTERVAL_MS = 1_000L

/** 位置跳变超过这个跨度就立即推送（用户拖动进度条），不等节流窗口。 */
private const val POSITION_JUMP_TOLERANCE_MS = 3_000L

private fun ensureMediaSession(context: Context, controller: PlaybackController): MediaSessionCompat {
    sessionController = controller
    return mediaSession ?: MediaSessionCompat(context.applicationContext, "CPPlayer").also { session ->
        session.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        session.setCallback(object : MediaSessionCompat.Callback() {
            private fun current(): PlaybackController? = sessionController

            override fun onPlay() { current()?.resume() }
            override fun onPause() { current()?.pause() }
            override fun onSkipToNext() { current()?.skipNext() }
            override fun onSkipToPrevious() { current()?.skipPrevious() }
            override fun onSeekTo(pos: Long) { current()?.seekTo(pos.coerceAtLeast(0L)) }
            override fun onStop() { current()?.pause() }
            override fun onMediaButtonEvent(mediaButtonEvent: android.content.Intent): Boolean {
                return super.onMediaButtonEvent(mediaButtonEvent)
            }
        })
        session.isActive = true
        mediaSession = session
    }
}

private fun releaseMediaSession() {
    sessionController = null
    mediaSession?.run {
        isActive = false
        setCallback(null)
        release()
    }
    mediaSession = null
}

@Composable
actual fun PlatformMediaControlsEffect(controller: PlaybackController, state: PlaybackUiState) {
    val context = ctxOrNull ?: return

    DisposableEffect(controller, context) {
        ensureMediaSession(context, controller)
        onDispose {
            if (sessionController === controller) releaseMediaSession()
        }
    }

    // ⚠️ 不要用 `LaunchedEffect(controller, state)`。
    //
    // `state` 是整份 `PlaybackUiState`，位置每 200 ms 就更替一次 ⇒ 这个 effect 每
    // 200 ms 就被取消重启一次，等于把 MediaSession 的推送也拉到 5 Hz。旧写法正是如此：
    // 每次都重建 `PlaybackStateCompat` + `MediaMetadataCompat` 并跨进程 `setPlaybackState`
    // —— 专辑封面/标题一个字节都没变，却每秒叫醒 SystemUI 五次。
    //
    // 现在拆成三条互相独立的 effect，各自只订阅**自己关心的那个字段**：
    // 1. 元数据只在换曲/时长变化时推；
    // 2. 播放态只在真的翻转时推；
    // 3. 位置按节流推（跳变立即推）。
    // 副作用是 state 更新不再重启本 effect，推送节奏完全由这里决定。
    val trackId = state.currentTrack?.id
    val duration = state.durationMs.coerceAtLeast(state.currentTrack?.durationMs ?: 0L)
    val trackName = state.currentTrack?.name
    val trackArtist = state.currentTrack?.artist
    val trackAlbum = state.currentTrack?.album
    val isPlaying = state.isPlaying
    val isBuffering = state.isBuffering
    val positionMs = state.positionMs

    // 1) 元数据：只在曲目身份或时长变化时推。duration 参与是因为时长是后知后觉探到的。
    LaunchedEffect(controller, trackId, duration, trackName, trackArtist, trackAlbum) {
        val session = ensureMediaSession(context, controller)
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, trackId ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, trackName ?: "CPPlayer")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, trackArtist.orEmpty())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, trackAlbum.orEmpty())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build(),
        )
    }

    // 2) 播放态：只在真的翻转时推（buffering 也算一种态）。
    LaunchedEffect(controller, isPlaying, isBuffering, trackId) {
        val session = ensureMediaSession(context, controller)
        val playback = when {
            isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            isPlaying -> PlaybackStateCompat.STATE_PLAYING
            trackId != null -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MEDIA_ACTIONS)
                .setState(playback, positionMs.coerceAtLeast(0L), 1f)
                .build(),
        )
    }

    // 3) 位置：节流。系统会按播放速率自行插值，无需跟随 200 ms 的 UI 节奏；
    //    但跨度大（拖动进度条）必须立即推，否则媒体面板显示会滞后。
    //
    // ⚠️ 循环体跨多次重组存活（key 只有 controller），读到的 `positionMs` 是**启动时的
    // 闭包快照**，不会跟着重组更新。用 `rememberUpdatedState` 转成「始终读最新值」，
    // 否则这里会永远推同一个陈旧位置。
    val latestPositionMs by rememberUpdatedState(positionMs)
    val latestDuration by rememberUpdatedState(duration)
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestIsBuffering by rememberUpdatedState(isBuffering)
    val latestTrackId by rememberUpdatedState(trackId)
    LaunchedEffect(controller) {
        val session = ensureMediaSession(context, controller)
        var lastPushedMs = Long.MIN_VALUE
        var lastPushAt = 0L
        while (true) {
            val now = System.currentTimeMillis()
            val pos = latestPositionMs.coerceIn(0L, latestDuration)
            val jumped = lastPushedMs == Long.MIN_VALUE ||
                kotlin.math.abs(pos - lastPushedMs) > POSITION_JUMP_TOLERANCE_MS
            if (jumped || now - lastPushAt >= POSITION_PUSH_INTERVAL_MS) {
                lastPushedMs = pos
                lastPushAt = now
                session.setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setActions(MEDIA_ACTIONS)
                        .setBufferedPosition(pos)
                        .setState(
                            currentPlaybackState(latestIsPlaying, latestIsBuffering, latestTrackId),
                            pos,
                            1f,
                        )
                        .build(),
                )
            }
            kotlinx.coroutines.delay(250L)
        }
    }
}

/** MediaSession 对外暴露的能力位；与 `MediaSessionCompat.Callback` 里实现的动作一一对应。 */
private val MEDIA_ACTIONS: Long = PlaybackStateCompat.ACTION_PLAY or
    PlaybackStateCompat.ACTION_PAUSE or
    PlaybackStateCompat.ACTION_PLAY_PAUSE or
    PlaybackStateCompat.ACTION_STOP or
    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
    PlaybackStateCompat.ACTION_SEEK_TO

private fun currentPlaybackState(
    isPlaying: Boolean,
    isBuffering: Boolean,
    trackId: String?,
): Int = when {
    isBuffering -> PlaybackStateCompat.STATE_BUFFERING
    isPlaying -> PlaybackStateCompat.STATE_PLAYING
    trackId != null -> PlaybackStateCompat.STATE_PAUSED
    else -> PlaybackStateCompat.STATE_NONE
}
