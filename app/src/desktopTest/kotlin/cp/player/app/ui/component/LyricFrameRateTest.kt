package cp.player.app.ui.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.playback.LyricsState
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.SyncedLyricLine
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 逐字（Karaoke）歌词的**帧率**回归守卫。
 *
 * 背景（这个 bug 跨两轮才定位到，别再改回去）：
 * `KaraokeLyricsView` 把 `currentPosition: () -> Int` 当每帧回调用，但它**自己不会
 * 请求重绘**。Compose 只在「上一次绘制里读过的 State 变了」时才重绘 ——
 * 若 provider 读的是**纯字段**（例如直接读 `SmoothPositionSource`），绘制阶段就没有
 * 任何 State 被登记观察，Compose 判定该节点永远不必重画，歌词只剩 `state.positionMs`
 * 那 200ms 一次的台阶（**5 Hz**），观感就是「逐字变色一格一格跳」。
 *
 * 修复：provider 必须读一个**每帧被写入的 State**，且该 State 只在绘制阶段被读
 * （这样每帧只重绘、不重组）。实现见 `LyricContent` 里的 `smoothPosition` 与其 ticker。
 *
 * ⚠️ 阈值刻意放宽：播放态只要求「多数帧有失效」而不是 60/60，避免把偶发的
 * 调度抖动判成失败（这里测的是「有没有逐帧驱动」，不是精确帧率）。
 */
class LyricFrameRateTest {

    /** 播放中：歌词必须逐帧重绘（>= 3/4 的帧有失效）。 */
    @Test
    fun redrawsEveryFrameWhilePlaying() {
        val hits = measure(playing = true)
        assertTrue(
            hits >= 45,
            "播放中歌词应当逐帧重绘，实测 $hits/60 帧有失效。" +
                "掉到个位数说明 provider 又变成读纯字段、Compose 观察不到位置变化了。",
        )
    }

    /** 暂停中：一帧都不该重绘 —— 静止画面还在逐帧重绘就是白烧 CPU。 */
    @Test
    fun doesNotRedrawWhilePaused() {
        val hits = measure(playing = false)
        assertTrue(
            hits == 0,
            "暂停中歌词不该重绘，实测 $hits/60 帧有失效。" +
                "说明逐帧 ticker 没有随暂停停下。",
        )
    }

    /** 渲染 60 帧，返回「存在待处理失效」的帧数。 */
    private fun measure(playing: Boolean): Int {
        val scene = ImageComposeScene(width = 600, height = 400, density = Density(1f)) {
            CpTheme(ThemeMode.LIGHT) {
                LyricContent(
                    state = PlaybackUiState(
                        isPlaying = playing,
                        positionMs = 0L,
                        durationMs = 20_000L,
                        lyrics = LyricsState.Success(List(20) { line(it) }),
                    ),
                    onSeek = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return try {
            var t = 0L
            val step = 16_666_667L
            // 预热：让 LaunchedEffect 起来、首帧完成组合。
            repeat(10) { scene.render(t); t += step }
            var hits = 0
            repeat(60) {
                scene.render(t)
                t += step
                if (scene.hasInvalidations()) hits++
            }
            hits
        } finally {
            scene.close()
        }
    }

    private fun line(index: Int): SyncedLyricLine {
        val text = "春眠不觉晓第${index}行"
        return SyncedLyricLine(
            time = index * 2000L,
            text = text,
            endTime = index * 2000L + 2000L,
            words = text.mapIndexed { i, ch ->
                SyncedLyricLine.SyncedWord(
                    ch.toString(),
                    index * 2000L + i * 200L,
                    index * 2000L + (i + 1) * 200L,
                )
            },
        )
    }
}
