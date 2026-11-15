package cp.player.app.ui.a2ui

import ai.littlepress.a2ui.compat.android.content.Context
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.model.processor.processInput
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientToServerMessage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.material3.a2ui.catalog.A2uiImageRenderer
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.Density
import cp.player.app.i18n.AiStringsZh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class A2uiRuntimeTest {

    @Test
    fun framer_extracts_complete_objects_and_keeps_partial() {
        val input = """
            text before
            {"version":"v0.9","createSurface":{"surfaceId":"s","catalogId":"c"}}
            {"value":"brace } inside string",
             "nested":{"ok":true}}
            {"version":"v0.9","updateDataModel":{"surfaceId":"s","path":"/x","value":1}}
            {"partial":true
        """.trimIndent()

        val batch = A2uiJsonFramer.drain(input)

        assertEquals(3, batch.objects.size)
        assertTrue(batch.objects[1].contains("brace } inside string"))
        assertTrue(batch.remainder.startsWith("{\"partial\":true"))
    }

    @Test
    fun dj_messages_are_real_v09_messages_accepted_by_official_parser() {
        val surface = A2uiTrackRef(
            mediaId = "netease://song/123",
            title = "真实曲目",
            artist = "真实歌手",
            album = "真实专辑",
            coverUrl = null,
            durationMs = 245_000,
        )
        val messages = A2uiAgent.djMessages(surface, emptyList(), AiStringsZh)
        val parser = A2uiMessageParser()

        val parsed = messages.map(parser::parse)

        assertEquals(2, parsed.size)
        assertTrue(parsed[0] is androidx.a2ui.model.protocol.A2uiCreateSurfaceMessage)
        assertTrue(parsed[1] is androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage)
        assertTrue(messages.all { it.contains("\"version\":\"v0.9\"") })
        assertTrue(messages.none { it.contains("mock") })
    }

    @Test
    fun local_surface_is_processed_by_official_material3_catalog() = runBlocking {
        val processor = createProcessor()
        val runtime = A2uiRuntime(processor)
        val outbound = mutableListOf<A2uiClientToServerMessage>()

        val processing = launch(Dispatchers.Default) { processor.collectMessages() }
        val events = launch { processor.outboundEvents.collect(outbound::add) }
        try {
            val track = A2uiTrackRef(
                mediaId = "netease://song/456",
                title = "可播放曲目",
                artist = "歌手",
                album = null,
                coverUrl = null,
                durationMs = 180_000,
            )
            runtime.feed(
                A2uiAgent.djMessages(track, listOf(track), AiStringsZh).joinToString("\n"),
            )

            val surfaces = withTimeout(5_000) {
                processor.activeSurfaces.first { it.isNotEmpty() }
            }
            delay(250)

            assertEquals(1, surfaces.size)
            assertTrue(surfaces.single().id.startsWith("cp_dj_"))
            assertNull(runtime.errorText)
            val errors = outbound.filterIsInstance<A2uiClientErrorMessage>()
            assertTrue(errors.isEmpty(), errors.joinToString { "${it.code}: ${it.message}" })
        } finally {
            events.cancel()
            processing.cancel()
        }
    }

    @Test
    fun local_surface_renders_with_material3_offscreen() = runBlocking<Unit> {
        val processor = createProcessor()
        val processing = launch(Dispatchers.Default) { processor.collectMessages() }
        try {
            val track = A2uiTrackRef(
                mediaId = "netease://song/789",
                title = "离屏绘制曲目",
                artist = "离屏歌手",
                album = "离屏专辑",
                coverUrl = null,
                durationMs = 210_000,
            )
            val messages = A2uiAgent.djMessages(track, emptyList(), AiStringsZh)
            val parser = A2uiMessageParser()
            messages.forEach { processor.processInput(parser, it) }
            val surface = withTimeout(5_000) {
                processor.activeSurfaces.first { it.isNotEmpty() }.single()
            }
            delay(250)

            val scene = ImageComposeScene(width = 900, height = 1200, density = Density(1f)) {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        A2uiSurface(
                            surfaceModel = surface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            try {
                repeat(8) { frame ->
                    scene.render(frame * 16_666_667L)
                }
                val png = scene.render(8 * 16_666_667L).encodeToData()?.bytes
                assertNotNull(png)
                assertTrue(png.isNotEmpty())
            } finally {
                scene.close()
            }
        } finally {
            processing.cancel()
        }
    }

    private fun createProcessor(): androidx.a2ui.model.processor.A2uiMessageProcessor {
        val catalog = materialA2uiBasicCatalogV1(
            image = MaterialA2uiBasicCatalogV1Defaults.image(
                A2uiImageRenderer { _, _, _, _, _ -> },
            ),
            video = MaterialA2uiBasicCatalogV1Defaults.video { _, _, _ -> },
            audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer { _, _, _, _ -> },
            functions = A2uiBasicCatalogV1.Functions(Context()),
        )
        return androidx.a2ui.compose.ui.A2uiMessageProcessor(catalogs = listOf(catalog))
    }
}
