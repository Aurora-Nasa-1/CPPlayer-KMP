package cp.player.kmp.playback

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import cp.player.kmp.util.PlatformContext
import cp.player.kmp.util.androidContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Media3-backed player used by the Android shared PlaybackController. */
/** Shared process player used by PlaybackController and MediaSessionService. */
object SharedMedia3Player {
    /** 音频流磁盘缓存目录（位于 `cacheDir/media`，系统清理缓存时可能被回收）。 */
    private const val CACHE_DIR_NAME = "media"

    /** 磁盘缓存上限：512 MiB，按 LRU 淘汰。 */
    private const val CACHE_SIZE_BYTES = 512L * 1024L * 1024L

    @Volatile
    private var instance: ExoPlayer? = null

    @Volatile
    private var mediaCache: SimpleCache? = null

    /**
     * 音频流磁盘缓存。
     *
     * 收益：seek 回退到已下载区间不再重新拉流；重播同一 URL 直接命中缓存；
     * 配合 [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR]，缓存损坏时自动回退网络，
     * 不会因为缓存问题导致播不出来。
     */
    @Synchronized
    fun getCache(context: android.content.Context): SimpleCache =
        mediaCache ?: SimpleCache(
            File(context.cacheDir, CACHE_DIR_NAME),
            LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { mediaCache = it }

    @Synchronized
    fun get(context: android.content.Context): ExoPlayer = instance ?: ExoPlayer.Builder(
        context,
        // 解码器回退：首选解码器不支持该编码时自动换一个，显著提升格式兼容性。
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
    )
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

    /** 释放播放器与缓存。由 [Media3PlatformPlayer.release] 调用，避免留下已释放的单例。 */
    @Synchronized
    fun release() {
        instance?.release()
        instance = null
        runCatching { mediaCache?.release() }
        mediaCache = null
    }
}

private class Media3PlatformPlayer(context: android.content.Context) : PlatformPlayer {
    private val player = SharedMedia3Player.get(context)

    /** 供缓存目录等使用；持有 application context 不泄漏 Activity。 */
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
    override val state: StateFlow<PlatformPlaybackState> = _state.asStateFlow()
    private val _position = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _duration.asStateFlow()
    private val _format = MutableStateFlow<AudioFormatInfo?>(null)
    override val formatInfo: StateFlow<AudioFormatInfo?> = _format.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var pollJob: Job? = null

    /**
     * 乐观 seek 目标：在引擎位置追上目标之前，对外始终汇报该值，
     * 避免 UI 松手后先回弹到旧位置、再跳到新位置。
     */
    @Volatile private var pendingSeekMs: Long? = null
    @Volatile private var pendingSeekAtMs: Long = 0L

    /** seek 发起时 UI 显示的位置，用于区分「引擎已按 seek 移动」与「引擎还没动」。 */
    @Volatile private var pendingSeekFromMs: Long = 0L

    /**
     * 已向引擎补发过几次待定 seek。
     *
     * ExoPlayer 在 media item 尚未设入、或 seek 目标落在未缓冲的流媒体区间时，
     * `seekTo` 会先被记成 pending 或直接无效。补发必须限量，否则每 200ms
     * 重发会一直跟引擎打架。
     */
    @Volatile private var pendingSeekAttempts = 0

    /**
     * ExoPlayer **不会**主动推送播放位置（只在状态变化时回调），
     * 因此必须自行轮询 `currentPosition`，否则 [positionMs] 会一直冻结，
     * 进度条、歌词高亮、媒体通知位置全部不动。
     */
    private val listener = object : Player.Listener {
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
    }

    init {
        player.addListener(listener)
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                publishPosition()
                delay(POSITION_POLL_MS)
            }
        }
    }

    override suspend fun load(url: String, startPositionMs: Long, headers: Map<String, String>, metadata: PlaybackMetadata?) {
        // 换曲：清掉上一首遗留的待定 seek，避免污染新曲目的位置。
        pendingSeekMs = null
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        val isRemote = scheme == "http" || scheme == "https"

        // 上游按 scheme 分派：http/https 走带 Cookie 的 HTTP 源，本地
        // （SAF 的 `content://`、`file://`、裸绝对路径）由 DefaultDataSource 分派到
        // ContentDataSource / FileDataSource —— 旧项目 BackendDataSource 就是这么做的。
        // 移植版一律用 DefaultHttpDataSource，导致本地文件压根打不开。
        val upstream: DataSource.Factory = DefaultDataSource.Factory(
            appContext,
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                // CDN 常见 http→https 跳转，允许跨协议重定向，减少加载失败。
                .setAllowCrossProtocolRedirects(true),
        )

        // 磁盘流缓存只对远程有意义：本地文件本来就在磁盘上，再缓存一份纯属双倍占用，
        // 而且缓存是以 URL 为键的，包一层只会多一个出错的环节。
        val dataSource: DataSource.Factory = if (isRemote) {
            CacheDataSource.Factory()
                .setCache(SharedMedia3Player.getCache(appContext))
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            upstream
        }
        val mediaMetadata = metadata?.let {
            MediaMetadata.Builder()
                .setTitle(it.title)
                .setArtist(it.artist)
                .setAlbumTitle(it.album)
                .setArtworkUri(it.coverUrl?.let(Uri::parse))
                .build()
        }
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(metadata?.id?.takeIf { it.isNotBlank() } ?: url)
            // 稳定的缓存键（mediaId@音质），避免 CDN 每次刷新鉴权 token 都新建一份磁盘缓存，
            // 也让 seek 回退到已下载区间时能真正命中缓存而不是重新拉流。本地文件不走缓存，无需设键。
            .apply {
                if (isRemote) metadata?.cacheKey?.takeIf { it.isNotBlank() }?.let(::setCustomCacheKey)
            }
            .setMediaMetadata(mediaMetadata ?: MediaMetadata.Builder().build())
            .build()
        player.setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(mediaItem))
        player.prepare()
        if (startPositionMs > 0) {
            // 起始位置同样纳入乐观值：prepare 尚未完成时引擎位置还是 0，
            // 不接管的话进度条会先显示 0 再跳到目标。
            pendingSeekFromMs = 0L
            pendingSeekMs = startPositionMs
            pendingSeekAtMs = System.currentTimeMillis()
            pendingSeekAttempts = 0
            _position.value = startPositionMs
        }
        applySeekToEngine(startPositionMs)
        player.play()
    }

    override fun play() { player.play() }
    override fun pause() { player.pause() }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        // 乐观更新：立即把目标位置推给 UI，随后由轮询确认引擎是否已追上。
        pendingSeekFromMs = _position.value
        pendingSeekMs = target
        pendingSeekAtMs = System.currentTimeMillis()
        pendingSeekAttempts = 0
        _position.value = target
        applySeekToEngine(target)
    }

    /**
     * 把一次 seek 交给 ExoPlayer，**不让异常冒到 UI 线程**。
     *
     * 两种「静默失效」都要挡住：
     * 1. 还没设入 media item（load 在途 / 刚 stop）——此时 `seekTo` 是空操作，
     *    乐观值会一直挂着直到超时再回弹。直接返回，交由轮询在装载完成后补发；
     * 2. 播放器已释放 —— 读位置/seek 会抛 `IllegalStateException`。
     */
    private fun applySeekToEngine(target: Long) {
        val mediaItemCount = runCatching { player.mediaItemCount }.getOrDefault(0)
        if (mediaItemCount <= 0) return
        runCatching { player.seekTo(target) }
    }

    override fun stop() {
        // 停止后引擎位置无意义，残留的待定 seek 只会让进度条停在旧目标上。
        pendingSeekMs = null
        player.stop()
        _state.value = PlatformPlaybackState.Idle
    }

    override fun release() {
        pollJob?.cancel()
        pollJob = null
        pendingSeekMs = null
        runCatching { player.removeListener(listener) }
        // 走单例的统一释放：既释放 ExoPlayer 也清掉缓存句柄，
        // 避免 SharedMedia3Player.instance 指向一个已释放的播放器。
        SharedMedia3Player.release()
    }

    override fun setVolume(volume: Float) { player.volume = volume.coerceIn(0f, 1f) }
    override fun getVolume(): Float = player.volume

    private fun publishPosition() {
        // 轮询可能与 release() 竞态；播放器已释放时读位置会抛异常，直接忽略这一轮。
        val actual = runCatching { player.currentPosition }.getOrNull()?.coerceAtLeast(0L) ?: return
        val playbackState = runCatching { player.playbackState }.getOrDefault(Player.STATE_IDLE)
        // 装载/缓冲中：seek 到未缓冲区间要先建连拿首包，宽限期必须放宽，
        // 否则 800ms 一到就把乐观值丢掉，进度条回弹——用户看到的就是「seek 没生效」。
        val engineLoading = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
        val target = pendingSeekMs
        if (target != null) {
            val settled = SeekSettle.isSettled(
                enginePositionMs = actual,
                targetMs = target,
                fromPositionMs = pendingSeekFromMs,
                elapsedMs = System.currentTimeMillis() - pendingSeekAtMs,
                engineLoading = engineLoading,
            )
            if (settled) {
                pendingSeekMs = null
            } else if (actual == pendingSeekFromMs && pendingSeekAttempts < MAX_SEEK_RETRIES) {
                // 引擎**一步没动**：这次定位多半是在 prepare/缓冲期被丢掉了，补发一次。
                // 若引擎已经在移动（只是还没到目标），补发反而会打断它，所以不补。
                pendingSeekAttempts++
                applySeekToEngine(target)
            }
        }
        _position.value = pendingSeekMs ?: actual
        _duration.value = runCatching { player.duration }.getOrNull()?.takeIf { it > 0 } ?: 0L
    }

    private companion object {
        /** 位置轮询间隔：ExoPlayer 无位置回调，需自行拉取。 */
        const val POSITION_POLL_MS = 200L

        /** 待定 seek 最多向引擎补发几次（每次间隔一个轮询周期）。 */
        const val MAX_SEEK_RETRIES = 3
    }
}

actual fun createPlatformPlayer(context: PlatformContext): PlatformPlayer =
    context.androidContext()?.let(::Media3PlatformPlayer) ?: AudioPlayerImpl()
