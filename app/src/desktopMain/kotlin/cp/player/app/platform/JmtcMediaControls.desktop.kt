package cp.player.app.platform

import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState
import io.github.selemba1000.JMTC
import io.github.selemba1000.JMTCEnabledButtons
import io.github.selemba1000.JMTCMediaType
import io.github.selemba1000.JMTCMusicProperties
import io.github.selemba1000.JMTCPlayingState
import io.github.selemba1000.JMTCSettings
import io.github.selemba1000.JMTCCallbacks
import io.github.selemba1000.JMTCTimelineProperties
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Uses JMTC for Windows SMTC and Linux MPRIS. */
internal class JmtcMediaControls private constructor(
    private val controller: PlaybackController,
) {
    private var jmtc: JMTC? = null

    /** SMTC 线程上写、Compose 线程上读，必须有可见性保证。 */
    @Volatile
    private var started = false
    private var nativeDirectory: File? = null

    /**
     * JMTC 的全部调用都收束到这条专用线程。
     *
     * ⚠️ 刻意**不用 AWT EDT**（旧实现在 EDT 上 `EventQueue.invokeAndWait` 初始化，必崩）：
     * SMTCAdapter.dll 的 `init()` 内部 `winrt::init_apartment()` 默认请求 **MTA** 套间，
     * 而真实运行的 Compose 应用中 AWT EDT 已被 OLE（拖放等）初始化为 **STA** ——
     * `CoInitializeEx` 返回 `RPC_E_CHANGED_MODE`，原生代码访问违例，JNA 报
     * `Invalid memory access`。已用独立探针实测复现：**线程 COM 套间为 STA 时
     * init 必崩；MTA / 未初始化线程正常**（裸 Swing 探针的 EDT 恰好还不是 STA，
     * 单独测 EDT 测不出来）。
     *
     * 单线程同时保证所有 JMTC 调用串行、推送节流字段无需加锁。
     */
    private val smtcExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cpplayer-smtc").apply { isDaemon = true }
    }

    /** 远程封面下载用的独立作用域：[stop] 时随实例一起取消，不会留下悬挂协程。 */
    private val coverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 远程封面的本地缓存目录（系统临时目录下），按 URL 哈希落盘。
     * 刻意**不用** lazy：[stop] 里要删它，lazy 会让「从未播过流媒体」的进程
     * 在 stop 时凭空建一个空目录。
     */
    private var coverCacheDir: File? = null

    /** 最近一次见到的曲目（SMTC 线程上读写）：异步封面下载完成后重建元数据要用。 */
    private var latestTrack: TrackSummary? = null

    /**
     * SMTC 推送去抖状态。
     *
     * Windows SMTC 是跨进程 WinRT 调用（JNA → 系统 broker），代价远高于普通 setter。
     * 而 [PlaybackUiState] 在播放中每 200 ms 就会因 position 变化推出一个新对象——
     * 若每次都全量重推（元数据 + 封面 + 时间轴 + 位置 + 播放态 + updateDisplay），
     * 就会在推送线程上产生约 25 次/秒的 COM 调用，把出帧节奏打得不规则；在开启
     * VRR / 系统帧节奏控制的显示器上，这种不规则会直接表现为刷新率抖动与闪烁。
     *
     * 因此按「什么真的变了」分档推送，并在 [pushState] 里做位置节流。
     * 这些字段只在 [smtcExecutor] 线程上读写，无需加锁。
     */
    private var pushedTrackId: String? = null
    private var pushedDurationMs = -1L
    private var pushedPlayingState: JMTCPlayingState? = null
    private var pushedPositionMs = -1L
    private var lastPositionPushAt = 0L

    fun start() {
        if (started || !isSupportedHost()) return
        smtcExecutor.execute {
            if (started) return@execute
            if (isWindows()) {
                loadWindowsNativeBridge()
                // 身份注册（来源应用名字 + 图标）必须发生在 JMTC init 之前，
                // 且与 init 同线程（MTA 套间约束，见 WindowsSmtcIdentity KDoc）。
                WindowsSmtcIdentity.ensure()
            }
            jmtc = runCatching { JMTC.getInstance(JMTCSettings("cpplayer", "CPPlayer")) }
                .onFailure {
                    System.err.println("[JMTC] initialization failed: ${it.stackTraceToString()}")
                }
                .getOrNull()?.also { media ->
                    media.setCallbacks(JMTCCallbacks().apply {
                        onPlay = { controller.resume() }
                        onPause = { controller.pause() }
                        onStop = { controller.pause() }
                        onNext = { controller.skipNext() }
                        onPrevious = { controller.skipPrevious() }
                        onSeek = { position -> controller.seekTo(position) }
                        onShuffle = { enabled -> if (enabled != controller.state.value.shuffleEnabled) controller.toggleShuffle() }
                    })
                    media.setEnabledButtons(JMTCEnabledButtons(true, true, true, true, true))
                    media.setMediaType(JMTCMediaType.Music)
                    media.setEnabled(true)
                    println("[JMTC] enabled=${media.getEnabled()} host=${System.getProperty("os.name")}")
                    started = true
                }
        }
    }

    /**
     * 由 Compose 侧高频调用；真正的推送逻辑在 [smtcExecutor] 线程上按 [pushState] 执行。
     * 调用方是 conflate 的 StateFlow 收集器，这里排队只是搬运，不需要再合并。
     */
    fun update(state: PlaybackUiState) {
        try {
            smtcExecutor.execute { pushState(state) }
        } catch (_: RejectedExecutionException) {
            // stop() 之后到达的残留提交：忽略。
        }
    }

    private fun pushState(state: PlaybackUiState) {
        val media = jmtc ?: return
        val track = state.currentTrack
        latestTrack = track
        if (track == null) {
            if (pushedTrackId != null) {
                resetPushedState()
                runCatching { media.resetDisplay() }
            }
            return
        }

        val duration = state.durationMs.coerceAtLeast(track.durationMs).coerceAtLeast(0L)
        val playingState = when {
            state.isBuffering -> JMTCPlayingState.CHANGING
            state.isPlaying -> JMTCPlayingState.PLAYING
            else -> JMTCPlayingState.PAUSED
        }
        val position = state.positionMs.coerceIn(0L, duration)
        val now = System.currentTimeMillis()
        var dirty = false

        // 1) 只有曲目身份变了才重推元数据与封面——封面是文件路径，重推代价最高。
        if (pushedTrackId != track.id) {
            pushedTrackId = track.id
            pushedDurationMs = duration
            pushedPositionMs = position
            lastPositionPushAt = now
            runCatching {
                media.setMediaProperties(musicProperties(track, coverFile(track.id, track.coverUrl)))
                media.setTimelineProperties(JMTCTimelineProperties(0L, duration, 0L, duration))
                media.setPosition(position)
            }
            dirty = true
        } else if (pushedDurationMs != duration) {
            // 2) 时长后知后觉（先起播、后探到时长）：只补时间轴，不动元数据。
            pushedDurationMs = duration
            runCatching { media.setTimelineProperties(JMTCTimelineProperties(0L, duration, 0L, duration)) }
            dirty = true
        }

        // 3) 播放态只在真的翻转时推。
        if (pushedPlayingState != playingState) {
            pushedPlayingState = playingState
            runCatching { media.setPlayingState(playingState) }
            dirty = true
        }

        // 4) 位置按节流推：系统会按时间轴自行插值，没必要跟随 200 ms 的 UI 节奏。
        //    跨度大（用户拖动进度条）则立即推，避免系统媒体面板显示滞后。
        val jumped = kotlin.math.abs(position - pushedPositionMs) > POSITION_JUMP_TOLERANCE_MS
        if (jumped || now - lastPositionPushAt >= POSITION_PUSH_INTERVAL_MS) {
            pushedPositionMs = position
            lastPositionPushAt = now
            runCatching { media.setPosition(position) }
            dirty = true
        }

        // 5) 只有真的改过东西才让系统刷新显示，否则纯属白跑一次跨进程调用。
        if (dirty) runCatching { media.updateDisplay() }
    }

    private fun resetPushedState() {
        pushedTrackId = null
        pushedDurationMs = -1L
        pushedPlayingState = null
        pushedPositionMs = -1L
        lastPositionPushAt = 0L
    }

    fun stop() {
        if (!started && jmtc == null) {
            coverScope.cancel()
            return
        }
        started = false
        coverScope.cancel()
        runCatching {
            smtcExecutor.execute {
                jmtc?.let { media ->
                    runCatching {
                        media.setEnabled(false)
                        media.resetDisplay()
                    }
                }
                resetPushedState()
                jmtc = null
                latestTrack = null
                // 缓存目录是本实例私有的临时目录，整个删掉；删除失败交给系统临时目录清理兜底。
                coverCacheDir?.deleteRecursively()
                coverCacheDir = null
                nativeDirectory = null
            }
            smtcExecutor.shutdown()
        }
    }

    /** 组装系统媒体面板的曲目元数据。 */
    private fun musicProperties(track: TrackSummary, cover: File?) = JMTCMusicProperties(
        track.name.ifBlank { "CPPlayer" },
        track.artist,
        track.album.orEmpty(),
        track.artist,
        emptyArray(),
        0,
        0,
        cover,
    )

    /**
     * 解析封面为系统媒体面板可用的本地文件。
     *
     * Windows SMTC / Linux MPRIS 的封面**只认本地文件**：`file:` URI 直接映射；
     * 流媒体的 http(s) 封面则查本地缓存，命中即用，未命中时后台下载
     * （见 [requestRemoteCover]），本次先不带封面推出去，下载完成后再补推。
     */
    private fun coverFile(trackId: String, url: String?): File? {
        val dir = ensureCoverCacheDir() ?: return null
        if (url == null) {
            // 本地曲目没有 coverUrl（桌面本地音源不解析标签），从音频文件懒提取内嵌封面。
            return LocalArtwork.extract(trackId, dir)
        }
        if (url.startsWith("file:")) {
            return runCatching { File(java.net.URI(url)) }.getOrNull()
        }
        if (!url.startsWith("http")) return null
        val cached = File(dir, "${sha256(url)}.img")
        if (cached.isFile) return cached
        requestRemoteCover(trackId, url, cached)
        return null
    }

    /** 后台下载 http(s) 封面到本地缓存，完成后若还没切歌就把含封面的元数据补推一次。 */
    private fun requestRemoteCover(trackId: String, url: String, target: File) {
        coverScope.launch {
            val downloaded = runCatching { downloadCover(url, target); target }
                .onFailure { runCatching { partialOf(target).delete() } }
                .getOrNull() ?: return@launch // 封面是锦上添花，失败静默放弃，不打日志刷屏
            applyRemoteCover(trackId, downloaded)
        }
    }

    private fun applyRemoteCover(trackId: String, cover: File) {
        try {
            smtcExecutor.execute {
                // 下载期间已经切歌了：这张封面与新曲目无关，丢弃。
                if (pushedTrackId != trackId) return@execute
                val media = jmtc ?: return@execute
                val track = latestTrack ?: return@execute
                runCatching {
                    media.setMediaProperties(musicProperties(track, cover))
                    media.updateDisplay()
                }
            }
        } catch (_: RejectedExecutionException) {
            // stop() 之后下载才完成：忽略。
        }
    }

    private fun downloadCover(url: String, target: File) {
        val connection = (java.net.URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 10_000
            instanceFollowRedirects = true
        }
        if (connection.responseCode !in 200..299) {
            error("cover download HTTP ${connection.responseCode}")
        }
        val partial = partialOf(target)
        connection.inputStream.use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_COVER_BYTES) error("cover too large: $total bytes")
                    output.write(buffer, 0, read)
                }
            }
        }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun ensureCoverCacheDir(): File? {
        coverCacheDir?.let { return it }
        val created = runCatching { Files.createTempDirectory("cpplayer-cover-").toFile() }
            .getOrNull() ?: return null
        coverCacheDir = created
        return created
    }

    private fun partialOf(target: File) = File(target.parentFile, "${target.name}.part")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun loadWindowsNativeBridge() {
        val resource = if (System.getProperty("os.arch", "").contains("64")) {
            "/win32-x86-64/SMTCAdapter.dll"
        } else {
            "/win32-x86/SMTCAdapter.dll"
        }
        runCatching {
            val directory = Files.createTempDirectory("cpplayer-smtc-").toFile()
            directory.deleteOnExit()
            val target = File(directory, "SMTCAdapter.dll")
            target.deleteOnExit()
            JmtcMediaControls::class.java.getResourceAsStream(resource)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("JMTC native resource not found: $resource")
            nativeDirectory = directory
            System.setProperty("jna.library.path", directory.absolutePath)
            println("[JMTC] Windows native bridge staged: ${target.absolutePath}")
        }.onFailure {
            System.err.println("[JMTC] Windows native bridge load failed: ${it.stackTraceToString()}")
        }
    }

    companion object {
        /** SMTC 位置推送最小间隔。系统按时间轴自行插值，无需跟随 200 ms 的 UI 节奏。 */
        private const val POSITION_PUSH_INTERVAL_MS = 1_000L

        /** 位置跳变超过该阈值视为用户 seek，立即推送而不是等节流。 */
        private const val POSITION_JUMP_TOLERANCE_MS = 3_000L

        /** 远程封面大小上限：正常专辑图都在几百 KB 量级，超限的基本是错误响应页。 */
        private const val MAX_COVER_BYTES = 20L * 1024 * 1024

        fun create(controller: PlaybackController) = JmtcMediaControls(controller)

        private fun isWindows(): Boolean =
            System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")

        private fun isSupportedHost(): Boolean {
            val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
            return os.contains("win") || os.contains("linux")
        }
    }
}
