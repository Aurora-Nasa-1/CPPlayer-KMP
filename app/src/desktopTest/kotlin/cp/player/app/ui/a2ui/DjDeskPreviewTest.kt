package cp.player.app.ui.a2ui

import ai.littlepress.a2ui.compat.android.content.Context
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.model.processor.processInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.material3.a2ui.catalog.A2uiImageRenderer
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AiStringsZh
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.component.CpSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test

class DjDeskPreviewTest {

    @Test
    fun `render dj desk`() = runBlocking {
        val processor = createProcessor()
        val processing = launch(Dispatchers.Default) { processor.collectMessages() }
        try {
            val track = A2uiTrackRef(
                mediaId = "netease://song/1",
                title = "雨夜缓慢行驶",
                artist = "示例歌手",
                album = "夜间驾驶",
                coverUrl = null,
                durationMs = 245_000,
            )
            val queue = listOf(
                A2uiTrackRef("netease://song/2", "霓虹之后", "另一位歌手", "夜航", null, 198_000),
                A2uiTrackRef("netease://song/3", "凌晨三点", "第三位歌手", null, null, 213_000),
            )
            val parser = A2uiMessageParser()
            A2uiAgent.djMessages(track, queue, AiStringsZh)
                .forEach { processor.processInput(parser, it) }
            val surface = withTimeout(5_000) {
                processor.activeSurfaces.first { it.isNotEmpty() }.single()
            }
            delay(300)
            write("build/dj-phone.png", render(390, 844, surface, phone = true))
            write("build/dj-desktop.png", render(900, 1100, surface, phone = false))
        } finally {
            processing.cancel()
        }
    }

    private fun render(
        width: Int,
        height: Int,
        surface: androidx.a2ui.model.processor.A2uiSurfaceModel,
        phone: Boolean,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    // ⚠️ 不要再包一层无参 `MaterialTheme {}`：那会用默认浅色方案覆盖
                    // `CpTheme` 提供的深色色板，出图看着「发白、borderless 几乎看不见」，
                    // 与真机完全不是一回事（这个坑已经踩过一次）。
                    A2uiSurface(
                        surfaceModel = surface,
                        modifier = Modifier
                            // 复刻 `LivingDjDeskSheet`：弹层内容有页级水平内边距，
                            // 宽度上限是 pageMaxWidth，手机端则由窗口宽度决定。
                            .then(
                                if (phone) {
                                    Modifier.width(390.dp)
                                } else {
                                    Modifier.widthIn(max = CpSpacing.pageMaxWidth)
                                },
                            )
                            .fillMaxWidth()
                            .padding(horizontal = CpSpacing.pageHorizontal),
                    )
                }
            }
        }
        return try {
            repeat(6) { scene.render(it * 16_666_667L) }
            scene.render(6 * 16_666_667L).encodeToData()?.bytes ?: error("encode failed")
        } finally {
            scene.close()
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

    private fun write(path: String, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }
}
