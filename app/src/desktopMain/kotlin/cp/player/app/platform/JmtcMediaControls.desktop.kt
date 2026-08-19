package cp.player.app.platform

import cp.player.kmp.playback.PlaybackController
import cp.player.kmp.playback.PlaybackUiState
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
        val track = state.currentTrack ?: run {
            runCatching { media.resetDisplay() }
            return
        }
        runCatching {
            val duration = state.durationMs.coerceAtLeast(track.durationMs).coerceAtLeast(0L)
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
            media.setPosition(state.positionMs.coerceIn(0L, duration))
            media.setPlayingState(
                when {
                    state.isBuffering -> JMTCPlayingState.CHANGING
                    state.isPlaying -> JMTCPlayingState.PLAYING
                    else -> JMTCPlayingState.PAUSED
                },
            )
            media.updateDisplay()
        }
    }

    fun stop() {
        val media = jmtc ?: return
        runCatching {
            media.setEnabled(false)
            media.resetDisplay()
        }
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
        fun create(controller: PlaybackController) = JmtcMediaControls(controller)

        private fun isWindows(): Boolean =
            System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")

        private fun isSupportedHost(): Boolean {
            val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
            return os.contains("win") || os.contains("linux")
        }
    }
}
