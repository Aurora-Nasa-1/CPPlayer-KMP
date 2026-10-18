package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.component.LyricContent
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.playback.LyricsState
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.SyncedLyricLine
import java.io.File
import kotlin.test.Test

/**
 * 临时出图核对：日语字体 + 注音（振り仮名）。
 *
 * 只用来人眼确认三件事：假名/汉字走的是 Noto Sans JP（不是中文字形回退）、
 * 注音确实画在汉字**上方**、注音字号是正文的 1/4。核完即删。
 */
class JpLyricsPreviewTest {

    private val lines = listOf(
        SyncedLyricLine(time = 0, text = "[夜|よる]に[駆|か]ける", endTime = 4_000, translation = "在夜里奔跑"),
        SyncedLyricLine(time = 4_000, text = "君の[名前|なまえ]を[呼|よ]ぶ", endTime = 8_000),
        SyncedLyricLine(time = 8_000, text = "沈むように溶けてゆくように", endTime = 12_000),
        SyncedLyricLine(time = 12_000, text = "中文对照：直 骨 次 海 说 门", endTime = 16_000),
    )

    @Test
    fun renderJapaneseLyrics() {
        // 焦点在第 2 行：看注音是否跟着走（判定「只画当前行」还是「每行都画」）。
        val bytes = render(900, 560, 12) {
            LyricContent(
                state = PlaybackUiState(
                    isPlaying = false,
                    lyrics = LyricsState.Success(lines),
                    positionMs = 5_000L,
                ),
                onSeek = {},
                immersiveEnabled = false,
            )
        }
        val out = File("build-verify/preview").apply { mkdirs() }
        File(out, "jp-lyrics-ruby.png").writeBytes(bytes)
    }

    @Test
    fun renderJapaneseLyricsWithRubyDisabled() {
        cp.player.app.AppModel.setShowRubyLyrics(false)
        try {
            val bytes = render(900, 560, 12) {
                LyricContent(
                    state = PlaybackUiState(
                        isPlaying = false,
                        lyrics = LyricsState.Success(lines),
                        positionMs = 5_000L,
                    ),
                    onSeek = {},
                    immersiveEnabled = false,
                )
            }
            val out = File("build-verify/preview").apply { mkdirs() }
            File(out, "jp-lyrics-ruby-disabled.png").writeBytes(bytes)
        } finally {
            cp.player.app.AppModel.setShowRubyLyrics(true)
        }
    }

    private fun render(width: Int, height: Int, frames: Int, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        return try {
            var last: ByteArray? = null
            repeat(frames) { i ->
                // 字体资源在桌面端是异步读的：给足时间，并推进动画时钟。
                if (i == 0) Thread.sleep(400)
                last = scene.render(i * 16_000_000L).encodeToData()?.bytes ?: error("encode failed")
            }
            last ?: error("encode failed")
        } finally {
            scene.close()
        }
    }
}
