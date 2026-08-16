package cp.player.kmp.playback

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import cp.player.kmp.util.PlatformContext
import cp.player.kmp.util.androidContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Media3-backed player used by the Android shared PlaybackController. */
/** Shared process player used by PlaybackController and MediaSessionService. */
object SharedMedia3Player {
    @Volatile
    private var instance: ExoPlayer? = null

    @Synchronized
    fun get(context: android.content.Context): ExoPlayer = instance ?: ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory()))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also { instance = it }

    @Synchronized
    fun release() {
        instance?.release()
        instance = null
    }
}

private class Media3PlatformPlayer(context: android.content.Context) : PlatformPlayer {
    private val player = SharedMedia3Player.get(context)

    private val _state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
    override val state: StateFlow<PlatformPlaybackState> = _state.asStateFlow()
    private val _position = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _duration.asStateFlow()
    private val _format = MutableStateFlow<AudioFormatInfo?>(null)
    override val formatInfo: StateFlow<AudioFormatInfo?> = _format.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = when (playbackState) {
                    Player.STATE_BUFFERING -> PlatformPlaybackState.Buffering
                    Player.STATE_READY -> if (player.isPlaying) PlatformPlaybackState.Playing else PlatformPlaybackState.Paused
                    Player.STATE_ENDED -> PlatformPlaybackState.Ended
                    else -> PlatformPlaybackState.Idle
                }
                publishPosition()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = if (isPlaying) PlatformPlaybackState.Playing else PlatformPlaybackState.Paused
                publishPosition()
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.value = PlatformPlaybackState.Error(error.message ?: "Media playback error")
            }
        })
    }

    override suspend fun load(url: String, startPositionMs: Long, headers: Map<String, String>) {
        val dataSource = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        player.setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(MediaItem.fromUri(Uri.parse(url))))
        player.prepare()
        player.seekTo(startPositionMs)
        player.play()
    }

    override fun play() { player.play() }
    override fun pause() { player.pause() }
    override fun seekTo(positionMs: Long) { player.seekTo(positionMs); publishPosition() }
    override fun stop() { player.stop(); _state.value = PlatformPlaybackState.Idle }
    override fun release() { player.release() }
    override fun setVolume(volume: Float) { player.volume = volume.coerceIn(0f, 1f) }
    override fun getVolume(): Float = player.volume

    private fun publishPosition() {
        _position.value = player.currentPosition.coerceAtLeast(0L)
        _duration.value = player.duration.takeIf { it > 0 } ?: 0L
    }
}

actual fun createPlatformPlayer(context: PlatformContext): PlatformPlayer =
    context.androidContext()?.let(::Media3PlatformPlayer) ?: AudioPlayerImpl()
