package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.component.LocalMiniPlayerTailSpace
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.wall.PosterWallBottomOptions
import java.io.File
import kotlin.test.Test

/**
 * 墙底部选项 vs 小播放器的**离屏出图**。
 *
 * 钉的是一个曾经真实发生的重叠：宽屏（桌面）没下发 `LocalMiniPlayerTailSpace`，
 * [PosterWallBottomOptions] 读到的永远是 0 ⇒ 「叠放模式 / 无限滚动 / 缩放」整排压在
 * 小播放器上。编译与单测都量不到这件事（没有重叠断言可写），只能出图看。
 *
 * ⚠️ 这里渲的是**真实**的 [PosterWallBottomOptions]（与页面同一份代码），
 * 小播放器那条是**等高替身**（真实 `MiniPlayer` 要 AppModel，测试里起不来）。
 * 替身高度 / 底距与桌面分支一致（实测高度 + 24dp 底距）。
 *
 * 产物：`build-wall-bottom-preview/`（`build-*` 已被 `.gitignore` 覆盖）。
 */
class PosterWallBottomPreviewTest {

    /** 桌面小播放器的典型实测高度（封面 48dp + 上下内边距）。 */
    private val barHeight = 76.dp

    /** `MainScreen.WideMiniPlayerBottomGap` 的同一个值（那边是 private）。 */
    private val barBottomGap = 24.dp

    @Test
    fun `render bottom options against mini player`() {
        // 尾留白 = 实测高度 + 宽屏固定底距 + 12dp 呼吸缝（见 MainScreen 宽屏那处 provide）
        render("bottom-without-tail", tail = 0.dp)
        render("bottom-with-tail", tail = barHeight + barBottomGap + 12.dp)
    }

    private fun render(name: String, tail: androidx.compose.ui.unit.Dp) {
        val scene = ImageComposeScene(width = 1072, height = 816, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                CompositionLocalProvider(LocalMiniPlayerTailSpace provides tail) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101012))) {
                        FakeWall(Modifier.fillMaxSize())
                        PosterWallBottomOptions(
                            stacked = false,
                            onStackedChange = {},
                            infinite = true,
                            onInfiniteChange = {},
                            zoomPercent = { 72 },
                            onZoom = {},
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                        FakeMiniPlayer(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = barBottomGap),
                        )
                    }
                }
            }
        }
        try {
            val image = scene.render()
            File("build-wall-bottom-preview").mkdirs()
            File("build-wall-bottom-preview/$name.png").writeBytes(image.encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }

    /** 墙的占位：一堆方块，只为给出"内容一直铺到底"的视觉参照。 */
    @Composable
    private fun FakeWall(modifier: Modifier = Modifier) {
        Column(
            modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(5) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(7) {
                        Box(
                            Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF2A2A31))
                        )
                    }
                }
            }
        }
    }

    /** 小播放器替身：高度、圆角、底距与桌面分支一致。 */
    @Composable
    private fun FakeMiniPlayer(modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp).height(barHeight),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A3A44)))
                Column {
                    Box(Modifier.size(width = 160.dp, height = 12.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF55555F)))
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(width = 110.dp, height = 10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF3A3A44))
                    )
                }
            }
        }
    }
}
