package cp.player.core.playback

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
import cp.player.core.util.PlatformContext
import cp.player.core.util.androidContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        // 熄屏后仍持有部分唤醒锁（需 manifest 声明 WAKE_LOCK 权限）：
        // 不设这个，锁屏几分钟后 WiFi 休眠断流，在线播放卡住且不切下一首。
        // NETWORK 模式同时覆盖解码时钟源与网络拉流；纯本地文件无副作用。
        .setWakeMode(C.WAKE_MODE_NETWORK)
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
     * 待定 seek 的状态机：**安卓与桌面共用 [PendingSeekTracker]**，不再各写一份同构逻辑。
     *
     * ⚠️ `enginePositionMs` 必须读**引擎真实位置**。传 `_position.value` 是错的——
     * 那是乐观值，会让「引擎一步没动就补发」的判定永久失效（详见该类 KDoc）。
     */
    private val pendingSeek = PendingSeekTracker(
        enginePositionMs = { runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L) },
        dispatchSeek = ::applySeekToEngine,
    )

    /** seek 失败事件：见 [SeekFailure]。轮询线程 `tryEmit`，缓冲满时丢弃（不阻塞轮询）。 */
    private val _seekFailures = MutableSharedFlow<SeekFailure>(extraBufferCapacity = 4)
    override val seekFailures: SharedFlow<SeekFailure> = _seekFailures.asSharedFlow()

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
            // ⚠️ ExoPlayer 播完时，同一事件批内先回调 onPlaybackStateChanged(STATE_ENDED)
            // （写入 Ended），紧接着回调 onIsPlayingChanged(false)。这里若无条件覆写，
            // Ended 会在主线程上被 Paused 同步吞掉 —— 消费端（PlaybackControllerImpl.
            // observePlatform，同样挂在主线程）恢复执行时两次赋值都已完成，
            // StateFlow 只能看到 Paused ⇒ onTrackEnded() 永远不触发，播完不切下一首。
            // 所以 STATE_ENDED 时保持 Ended 不动，等 load() 下一首时状态自然翻转。
            val ended = runCatching { player.playbackState }
                .getOrDefault(Player.STATE_IDLE) == Player.STATE_ENDED
            if (ended) {
                publishPosition()
                return
            }
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
        pendingSeek.cancel()
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
            // 刚 setMediaSource + prepare，media item 虽已设入但还没准备好，
            // 这里按「未就绪」处理，交给轮询补发更稳。
            pendingSeek.request(startPositionMs, engineReady = false)
            _position.value = startPositionMs
        } else {
            // 从头播放不需要乐观值（目标就是引擎当前位置），直接定位即可。
            // 走 tracker 反而会挂一个「目标 0」的待定 seek，把位置冻在 0 直到宽限期结束。
            applySeekToEngine(0L)
        }
        player.play()
    }

    override fun play() { player.play() }
    override fun pause() { player.pause() }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        // 乐观更新：立即把目标位置推给 UI，随后由轮询确认引擎是否已追上。
        // 基线捕获、补发、落定全部交给 [PendingSeekTracker]，本类不再内联这套逻辑。
        // engineReady 必须是**引擎真值**：media item 没设入时下发是空操作，需要轮询补发。
        val engineReady = runCatching { player.mediaItemCount }.getOrDefault(0) > 0
        pendingSeek.request(target, engineReady = engineReady)
        _position.value = target
    }

    /**
     * 把一次 seek 交给 ExoPlayer，**不让异常冒到 UI 线程**。
     *
     * 两种「静默失效」都要挡住：
     * 1. 还没设入 media item（load 在途 / 刚 stop）——此时 `seekTo` 是空操作，
     *    乐观值会一直挂着直到超时再回弹。直接返回，交由轮询在装载完成后补发；
     * 2. 播放器已释放 —— 读位置/seek 会抛 `IllegalStateException`。
     */
    private fun applySeekToEngine(target: Long): Boolean {
        val mediaItemCount = runCatching { player.mediaItemCount }.getOrDefault(0)
        if (mediaItemCount <= 0) return false
        return runCatching { player.seekTo(target) }.isSuccess
    }

    override fun stop() {
        // 停止后引擎位置无意义，残留的待定 seek 只会让进度条停在旧目标上。
        pendingSeek.cancel()
        player.stop()
        _state.value = PlatformPlaybackState.Idle
    }

    override fun release() {
        pollJob?.cancel()
        pollJob = null
        pendingSeek.cancel()
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
        // 引擎是否**真的建好了**：media item 已设入才算。
        // 未设入时 `seekTo` 是空操作（见 applySeekToEngine），必须靠轮询补发。
        // 不能改用「位置有没有动」去反推——见 PendingSeekTracker 的 KDoc。
        val engineReady = runCatching { player.mediaItemCount }.getOrDefault(0) > 0
        // 推进待定 seek：落定则释放乐观值；刚就绪 / 未就绪 / 就绪后没动 都要补发。
        val tick = pendingSeek.tick(
            enginePositionMs = actual,
            engineReady = engineReady,
            engineLoading = engineLoading,
        )
        // 宽限期过完仍没追上 ⇒ 上报失败，让 UI 能提示用户，
        // 而不是让进度条静默弹回原位（那正是「拖了没反应」的观感）。
        if (tick is PendingSeekTracker.Tick.GaveUp) {
            _seekFailures.tryEmit(SeekFailure(tick.targetMs, tick.actualMs))
        }
        _position.value = pendingSeek.displayMs() ?: actual
        _duration.value = runCatching { player.duration }.getOrNull()?.takeIf { it > 0 } ?: 0L
    }

    private companion object {
        /** 位置轮询间隔：ExoPlayer 无位置回调，需自行拉取。 */
        const val POSITION_POLL_MS = 200L
    }
}

actual fun createPlatformPlayer(context: PlatformContext): PlatformPlayer =
    context.androidContext()?.let(::Media3PlatformPlayer) ?: AudioPlayerImpl()
