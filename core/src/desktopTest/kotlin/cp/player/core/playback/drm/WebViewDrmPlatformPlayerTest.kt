package cp.player.core.playback.drm

import cp.player.core.music.DrmPlaybackInfo
import cp.player.core.playback.PlatformPlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WebViewDrmPlatformPlayerTest {

    private class FakeTransport : WebViewDrmTransport {

        // replay = 1：事件协程在 Dispatchers.Default 上启动，测试线程可能先发出事件；
        // 重放最后一条确保 hello 不会因订阅竞速被丢掉。
        private val _events = MutableSharedFlow<WebViewPlayerEvent>(replay = 1, extraBufferCapacity = 64)
        override val events = _events.asSharedFlow()

        override val pageUrl = "http://127.0.0.1:0/player?token=fake"

        override fun playlistUrl(upstreamUrl: String): String =
            "http://127.0.0.1:0/manifest?token=fake&url=$upstreamUrl"

        @Volatile
        override var isPageConnected: Boolean = false

        @Volatile
        var pageReadyResult: Boolean = true

        @Volatile
        var capturedExchange: (suspend (ByteArray) -> ByteArray)? = null

        val sentCommands = java.util.Collections.synchronizedList(mutableListOf<WebViewPlayerCommand>())

        override suspend fun awaitPageConnected(timeoutMs: Long): Boolean = pageReadyResult

        override suspend fun send(command: WebViewPlayerCommand) {
            sentCommands.add(command)
        }

        override fun setLicenseExchange(exchange: (suspend (ByteArray) -> ByteArray)?) {
            capturedExchange = exchange
        }

        suspend fun emit(event: WebViewPlayerEvent) = _events.emit(event)

        fun commandOfFirst(type: String): WebViewPlayerCommand? =
            sentCommands.firstOrNull { commandKind(it) == type }
    }

    private fun transport(): FakeTransport = FakeTransport()

    private suspend fun FakeTransport.waitEmeObservedBy(player: WebViewDrmPlatformPlayer, eme: Boolean) {
        withTimeout(2_000) {
            while (player.isEmeReady != eme) delay(20)
        }
    }

    @Test
    fun `loadDrm registers license exchange and sends load command`() = runBlocking {
        val t = transport()
        val player = WebViewDrmPlatformPlayer(t, launchBrowser = null, onRelease = null)
        t.emit(WebViewPlayerEvent.Hello(eme = true, keySystem = "com.widevine.alpha"))
        t.waitEmeObservedBy(player, eme = true)
        var registered: (suspend (ByteArray) -> ByteArray)? = null
        val drm = DrmPlaybackInfo(
            keySystem = "widevine",
            exchangeLicense = { challenge -> challenge.reversedArray() },
        )
        player.loadDrm("https://cdn.example.test/media.m3u8", drm, startPositionMs = 800)
        val load = t.commandOfFirst("load") as? WebViewPlayerCommand.Load
        assertEquals(
            "http://127.0.0.1:0/manifest?token=fake&url=https://cdn.example.test/media.m3u8",
            load?.url,
            "DRM 清单必须经代理归一化后装载",
        )
        assertEquals(800L, load?.startPositionMs)
        val exchange = t.capturedExchange
        assertTrue(exchange != null, "loadDrm 必须注册 license 交换回调")
        val license = exchange!!.invoke(byteArrayOf(9, 8, 7))
        assertEquals(byteArrayOf(7, 8, 9).toList(), license.toList())
        player.release()
    }

    @Test
    fun `loadDrm rejects when eme probe failed`() = runBlocking {
        val t = transport()
        val player = WebViewDrmPlatformPlayer(t, launchBrowser = null, onRelease = null)
        t.emit(WebViewPlayerEvent.Hello(eme = false, keySystem = null))
        t.waitEmeObservedBy(player, eme = false)
        val drm = DrmPlaybackInfo(keySystem = "widevine", exchangeLicense = { it })
        val error = runCatching {
            player.loadDrm("https://cdn.example.test/media.m3u8", drm, 0)
        }.exceptionOrNull()
        assertTrue(error is UnsupportedOperationException, "EME 不可用应抛 UnsupportedOperationException: $error")
        player.release()
    }

    @Test
    fun `loadDrm rejects non widevine key systems`() = runBlocking {
        val t = transport()
        val player = WebViewDrmPlatformPlayer(t, launchBrowser = null, onRelease = null)
        val drm = DrmPlaybackInfo(keySystem = "fairplay", exchangeLicense = { it })
        assertFailsWith<UnsupportedOperationException> {
            player.loadDrm("https://cdn.example.test/media.m3u8", drm, 0)
        }
        player.release()
    }

    @Test
    fun `page events map to platform state and progress`() = runBlocking {
        val t = transport()
        val player = WebViewDrmPlatformPlayer(t, launchBrowser = null, onRelease = null)
        t.emit(WebViewPlayerEvent.State(state = "buffering", message = null))
        withTimeout(2_000) {
            while (player.state.value != PlatformPlaybackState.Buffering) delay(20)
        }
        t.emit(WebViewPlayerEvent.Progress(positionMs = 4_200, durationMs = 180_000))
        withTimeout(2_000) {
            while (player.positionMs.value != 4_200L) delay(20)
        }
        assertEquals(180_000L, player.durationMs.value)
        t.emit(WebViewPlayerEvent.State(state = "error", message = "keySystemNoAccess"))
        withTimeout(2_000) {
            while (player.state.value !is PlatformPlaybackState.Error) delay(20)
        }
        assertEquals("keySystemNoAccess", (player.state.value as PlatformPlaybackState.Error).message)
        player.release()
    }

    @Test
    fun `plain load also goes through the manifest proxy`() = runBlocking {
        val t = transport()
        val player = WebViewDrmPlatformPlayer(t, launchBrowser = null, onRelease = null)
        player.load("https://cdn.example.test/plain.m3u8", 0)
        withTimeout(2_000) {
            while (t.commandOfFirst("load") == null) delay(20)
        }
        val load = t.commandOfFirst("load") as? WebViewPlayerCommand.Load
        assertTrue(load?.url?.contains("/manifest?token=") == true, "明文清单也应归一化: ${load?.url}")
        player.release()
    }

    @Test
    fun `control methods forward commands to the page`() = runBlocking {
        val t = transport()
        val player = WebViewDrmPlatformPlayer(t, launchBrowser = null, onRelease = null)
        player.play()
        player.pause()
        player.seekTo(30_000)
        player.setVolume(0.5f)
        player.setPlaybackSpeed(1.25f)
        player.stop()
        withTimeout(2_000) {
            while (t.sentCommands.size < 6) delay(20)
        }
        val types = t.sentCommands.map { commandKind(it) }
        assertEquals(listOf("play", "pause", "seek", "volume", "rate", "stop"), types)
        assertEquals(1.25f, player.playbackSpeed)
        assertEquals(0.5f, player.getVolume())
        player.release()
    }
}

private fun commandKind(command: WebViewPlayerCommand): String = when (command) {
    is WebViewPlayerCommand.Load -> "load"
    WebViewPlayerCommand.Play -> "play"
    WebViewPlayerCommand.Pause -> "pause"
    is WebViewPlayerCommand.Seek -> "seek"
    is WebViewPlayerCommand.Volume -> "volume"
    is WebViewPlayerCommand.Rate -> "rate"
    WebViewPlayerCommand.Stop -> "stop"
}
