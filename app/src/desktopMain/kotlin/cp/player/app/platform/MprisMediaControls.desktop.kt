package cp.player.app.platform

import cp.player.kmp.playback.PlaybackController
import cp.player.kmp.playback.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface

/** Lightweight Linux MPRIS adapter. It is never initialized on non-Linux hosts. */
internal class MprisMediaControls private constructor(
    private val controller: PlaybackController,
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var connection: DBusConnection? = null

    fun start() {
        if (!System.getProperty("os.name", "").contains("linux", ignoreCase = true)) return
        runCatching {
            val bus = DBusConnectionBuilder.forSessionBus().build()
            bus.requestBusName(BUS_NAME)
            bus.exportObject(PATH, RootObject())
            bus.exportObject(PATH, PlayerObject())
            connection = bus
        }
    }

    fun update(state: PlaybackUiState) {
        // State is consumed by the next D-Bus property read. Keeping this adapter
        // non-blocking avoids coupling D-Bus threads to playback/network work.
        latestState = state
    }

    fun stop() {
        connection?.disconnect()
        connection = null
    }

    private var latestState = PlaybackUiState()

    private inner class RootObject : RootApi {
        override fun getObjectPath() = PATH
        override fun Raise() = Unit
        override fun Quit() = stop()
        override fun getIdentity() = "CPPlayer"
    }

    private inner class PlayerObject : PlayerApi {
        override fun getObjectPath() = PATH
        override fun Play() { scope.launch { controller.resume() } }
        override fun Pause() = controller.pause()
        override fun PlayPause() = controller.togglePlayPause()
        override fun Stop() = controller.pause()
        override fun Next() = controller.skipNext()
        override fun Previous() = controller.skipPrevious()
        override fun Seek(offset: Long) = controller.seekTo((latestState.positionMs + offset / 1000).coerceAtLeast(0))
        override fun getPlaybackStatus() = if (latestState.isPlaying) "Playing" else "Paused"
        override fun getPosition() = latestState.positionMs * 1000
    }

    companion object {
        private const val BUS_NAME = "org.mpris.MediaPlayer2.cpplayer"
        private const val PATH = "/org/mpris/MediaPlayer2"
        fun create(controller: PlaybackController) = MprisMediaControls(controller)
    }
}

@DBusInterfaceName("org.mpris.MediaPlayer2")
private interface RootApi : DBusInterface {
    fun Raise()
    fun Quit()
    fun getIdentity(): String
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
private interface PlayerApi : DBusInterface {
    fun Play()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Next()
    fun Previous()
    fun Seek(offset: Long)
    fun getPlaybackStatus(): String
    fun getPosition(): Long
}
