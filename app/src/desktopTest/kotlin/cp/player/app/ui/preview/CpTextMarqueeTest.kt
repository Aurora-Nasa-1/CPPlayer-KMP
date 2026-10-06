package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpTextReveal
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 跑马灯（溢出即横向滚动）的守卫测试。
 *
 * ### 为什么必须有
 *
 * 2026-10-07 的 bug：`CpText` 的 `Marquee` 分支**从来没有被任何调用点触发** ——
 * `emphasized` 恒为 false ⇒ `Auto` 永远解析成 `Hover` ⇒ 长文本**依旧显示省略号**，
 * 治理形同虚设。这个 bug 编译得过、静态出图也看不出（静态图本来就不动），
 * 只有**沿时间轴渲染多帧**才能量到「它在滚 / 它没滚」。
 *
 * ### 两条断言
 *
 * 1. [emphasizedTextMarqueeActuallyScrolls]：焦点位（`emphasized = true`）在溢出时
 *    必须**逐帧变化**（= 真的在滚），且与「尾截」形态不同（= 不是换个省略号了事）；
 * 2. [nonEmphasizedTextDoesNotScroll]：非焦点位不得产生任何动画（帧间逐字节一致）——
 *    既证明 `emphasized` 是唯一的开关，也守住「列表里不要每个都滚」的限流约定。
 *
 * ⚠️ 必须显式推进 `render(nanoTime)` 的时间轴：跑马灯是 `withFrameNanos` 驱动的，
 * 连续两次不带参数的 `render()` 相隔只有几微秒，像素根本不会变，会假绿。
 */
class CpTextMarqueeTest {

    /** 一定会在 160dp 内溢出的长标题。 */
    private val longTitle = "一个非常非常长的歌名用来触发跑马灯滚动效果（Live 现场版）"

    @Composable
    private fun Row(emphasized: Boolean, reveal: CpTextReveal = CpTextReveal.Auto) {
        Box(Modifier.width(160.dp).height(28.dp)) {
            CpText(
                text = longTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                emphasized = emphasized,
                reveal = reveal,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    /** 对照组：治理前的写法（尾截 + 省略号），用来证明跑马灯**不是**省略号。 */
    @Composable
    private fun EllipsisBaseline() {
        Box(Modifier.width(160.dp).height(28.dp)) {
            androidx.compose.material3.Text(
                text = longTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    /**
     * 沿时间轴渲染 16 帧（每帧间隔 200ms，共 3s），返回各帧的 PNG 字节。
     *
     * 采样窗口刻意拉长到 3 秒：`basicMarquee` 有初始延迟与轮间停顿，
     * 只看头两帧可能整段落在「停」的区间里而误判成「没动」。
     */
    private fun renderTimeline(content: @Composable () -> Unit): List<ByteArray> {
        val scene = ImageComposeScene(width = 200, height = 40, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        return try {
            (0 until 16).map { i ->
                scene.render(i * 200_000_000L).encodeToData()?.bytes ?: error("encode failed")
            }
        } finally {
            scene.close()
        }
    }

    private fun writePng(name: String, bytes: ByteArray) {
        val out = File("build-verify/preview").apply { mkdirs() }
        File(out, "$name.png").writeBytes(bytes)
    }

    /** 焦点位：`emphasized = true` ⇒ 溢出即滚 —— 帧间必须出现差异。 */
    @Test
    fun emphasizedTextMarqueeActuallyScrolls() {
        val frames = renderTimeline { Row(emphasized = true) }
        writePng("cp-text-marquee-first", frames.first())
        writePng("cp-text-marquee-last", frames.last())

        val distinct = frames.map { it.toList() }.distinct().size
        assertTrue(
            distinct > 1,
            "emphasized=true 的溢出文本必须横向滚动（帧间像素应有变化），实际采样的 16 帧完全相同 ⇒ 跑马灯没生效",
        )

        // 与尾截对照组不同 ⇒ 确实是「滚动」而不是「换了种省略」。
        val baseline = renderTimeline { EllipsisBaseline() }
        assertTrue(
            !frames[frames.lastIndex].contentEquals(baseline.last()),
            "跑马灯末帧不应与尾截省略的静态形态逐字节一致（否则等于没滚）",
        )
    }

    /** 非焦点位：`emphasized = false` ⇒ 不得有任何动画（帧间逐字节一致）。 */
    @Test
    fun nonEmphasizedTextDoesNotScroll() {
        val frames = renderTimeline { Row(emphasized = false) }
        val first = frames.first()
        assertTrue(
            frames.all { it.contentEquals(first) },
            "emphasized=false 的文本不得产生跑马灯动画（列表里每行都滚会拖垮帧率）",
        )
    }

    /** 显式钉死 `reveal = Marquee` 时同样要滚（不依赖 `emphasized`）。 */
    @Test
    fun explicitMarqueeRevealAlsoScrolls() {
        val frames = renderTimeline { Row(emphasized = false, reveal = CpTextReveal.Marquee) }
        val distinct = frames.map { it.toList() }.distinct().size
        assertTrue(distinct > 1, "reveal = Marquee 必须无视 emphasized 直接滚动")
    }
}
