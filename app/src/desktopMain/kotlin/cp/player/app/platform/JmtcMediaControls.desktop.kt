package cp.player.app.platform

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
import java.awt.EventQueue
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Uses JMTC for Windows SMTC and Linux MPRIS. */
internal class JmtcMediaControls private constructor(
    private val controller: PlaybackController,
) {
    private var jmtc: JMTC? = null
    private var started = false
    private var nativeDirectory: File? = null

    /**
     * SMTC 推送去抖状态。
     *
     * Windows SMTC 是跨进程 WinRT 调用（JNA → 系统 broker），代价远高于普通 setter。
     * 而 [PlaybackUiState] 在播放中每 200 ms 就会因 position 变化推出一个新对象——
     * 若每次都全量重推（元数据 + 封面 + 时间轴 + 位置 + 播放态 + updateDisplay），
     * 就会在合成线程上产生约 25 次/秒的 COM 调用，把出帧节奏打得不规则；在开启
     * VRR / 系统帧节奏控制的显示器上，这种不规则会直接表现为刷新率抖动与闪烁。
     *
     * 因此下面按「什么真的变了」分档推送，并在 [update] 里做位置节流。
     */
    private var pushedTrackId: String? = null
    private var pushedDurationMs = -1L
    private var pushedPlayingState: JMTCPlayingState? = null
    private var pushedPositionMs = -1L
    private var lastPositionPushAt = 0L

    fun start() {
        if (started || !isSupportedHost()) return
        if (isWindows()) loadWindowsNativeBridge()
        jmtc = runCatching {
            // JMTC's WinRT bridge must be initialized on the AWT UI thread.
            val result = AtomicReference<JMTC>()
            val failure = AtomicReference<Throwable>()
            val initialize: () -> Unit = {
                runCatching { JMTC.getInstance(JMTCSettings("cpplayer", "CPPlayer")) }
                    .onSuccess(result::set)
                    .onFailure(failure::set)
                Unit
            }
            if (EventQueue.isDispatchThread()) initialize() else EventQueue.invokeAndWait(initialize)
            failure.get()?.let { throw it }
            result.get() ?: error("JMTC returned no implementation")
        }.onFailure {
            System.err.println("[JMTC] initialization failed: ${it.stackTraceToString()}")
        }.getOrNull()?.also { media ->
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
            val enable = {
                media.setEnabled(true)
                println("[JMTC] enabled=${media.getEnabled()} host=${System.getProperty("os.name")}")
            }
            if (EventQueue.isDispatchThread()) enable() else EventQueue.invokeAndWait(enable)
            started = true
        }
    }

    fun update(state: PlaybackUiState) {
        val media = jmtc ?: return
        val track = state.currentTrack
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
                media.setMediaProperties(
                    JMTCMusicProperties(
                        track.name.ifBlank { "CPPlayer" },
                        track.artist,
                        track.album.orEmpty(),
                        track.artist,
                        emptyArray(),
                        0,
                        0,
                        coverFile(track.coverUrl),
                    ),
                )
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
        val media = jmtc ?: return
        runCatching {
            media.setEnabled(false)
            media.resetDisplay()
        }
        resetPushedState()
        jmtc = null
        started = false
    }

    private fun coverFile(url: String?): File? = url
        ?.takeIf { it.startsWith("file:") }
        ?.let { runCatching { File(java.net.URI(it)) }.getOrNull() }

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

        fun create(controller: PlaybackController) = JmtcMediaControls(controller)

        private fun isWindows(): Boolean =
            System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")

        private fun isSupportedHost(): Boolean {
            val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
            return os.contains("win") || os.contains("linux")
        }
    }
}
