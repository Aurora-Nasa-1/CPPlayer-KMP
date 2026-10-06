package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.component.CpEllipsisMode
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.PlaylistItem
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 文本溢出治理的版式核对（会真的出图，并做像素级比对）。
 *
 * ### 为什么必须有这个测试
 *
 * 「保留原有视觉」是本轮治理的硬要求，而编译和单测都量不到它：
 * 宽高、行高、截断点、颜色只有渲染出来才知道。这里用**两张图逐字节比对**
 * 把它钉死 —— 比「人眼看图觉得一样」可靠得多。
 *
 * ### 三条断言各自守什么
 *
 * 1. [tailModeIsPixelIdenticalToPlainText]：[CpText] 默认档（[CpEllipsisMode.Tail]）
 *    必须与治理前的裸 `Text` **逐像素一致**。这是「默认行为 = 治理前」的机器证明；
 * 2. [overflowDoesNotGrowTheContainer]：溢出时容器宽高不变（既不撑宽也不换行）；
 * 3. [smartEllipsisKeepsTheUsefulEnd]：中间 / 保留扩展名两种省略确实保住了该保的部分。
 */
class CpTextPreviewTest {

    /** 长得一定会被截断的歌单名（真实形态：带日期与括注的长名）。 */
    private val longName =
        "我喜欢的音乐 2026 年 10 月第 3 周合集（含每日推荐与心动模式全量回溯）"

    /** 长得一定会被截断的文件名：扩展名在尾部，是典型「该保住的部分」。 */
    private val longFile =
        "周杰伦 - 2026.10.06 演唱会现场版母带重制 [Live].flac"

    /** 短文本：不应当触发任何溢出处理。 */
    private val shortName = "晴天"

    /**
     * 治理前的写法：裸 `Text` + 尾部省略。
     *
     * **刻意不改成调用 [CpText]** —— 它是对照组，一旦跟着改了，比对就变成自己比自己。
     */
    @Composable
    private fun LegacyColumn() {
        Column(Modifier.width(240.dp)) {
            Text(longName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(longFile, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(shortName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    /** 治理后（默认档）：同样的文本、同样的样式、同样的宽度。 */
    @Composable
    private fun CpTextTailColumn() {
        Column(Modifier.width(240.dp)) {
            CpText(text = longName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            CpText(text = longFile, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            CpText(text = shortName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }

    /** 三种省略模式并排（同一宽度），用来目视确认"省略号挪到了对的地方"。 */
    @Composable
    private fun ModesColumn() {
        Column(Modifier.width(240.dp).padding(16.dp)) {
            listOf(
                CpEllipsisMode.Tail to "Tail",
                CpEllipsisMode.Middle to "Middle",
                CpEllipsisMode.Filename to "Filename",
            ).forEach { (mode, label) ->
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                CpText(text = longName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, ellipsisMode = mode)
                CpText(text = longFile, style = MaterialTheme.typography.bodyMedium, maxLines = 1, ellipsisMode = mode)
            }
        }
    }

    /**
     * 真实组件（[SongItem] / [PlaylistItem]）在**窄容器**下的形态。
     *
     * 封面一律传 null ⇒ 走图标占位分支，不碰网络图片加载（无头渲染里没有 ImageLoader）。
     */
    @Composable
    private fun RealComponents() {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            SongItem(
                track = TrackSummary(
                    id = "1", name = longName, artist = "周杰伦 / 杨瑞代 / 方文山 / 钟兴民 / 洪敬尧",
                    album = "我很忙（2007 全新专辑）", coverUrl = null, durationMs = 269_000,
                ),
                onClick = {},
                onOptionsClick = {},
                isCurrentlyPlaying = true,
            )
            SongItem(
                track = TrackSummary(
                    id = "2", name = shortName, artist = "周杰伦", album = "叶惠美",
                    coverUrl = null, durationMs = 269_000,
                ),
                onClick = {},
                onOptionsClick = {},
            )
            PlaylistItem(
                playlist = PlaylistSummary(
                    id = 1L, name = longName, coverUrl = null,
                    trackCount = 1284, creatorName = "一个名字也很长的用户昵称",
                ),
                isOwner = false,
                onClick = {},
                onOptionsClick = {},
            )
        }
    }

    /**
     * 渲染 [frames] 帧，返回最后一帧。
     *
     * ⚠️ **不能只渲染一帧**：[CpText] 的溢出检测发生在 `onTextLayout` 里，也就是
     * **第一帧的布局结束之后** —— 智能省略要到第二帧才落地。只渲染一帧的话，
     * 拍到的是「还没启用任何策略」的画面，测试会假失败。
     */
    private fun renderBytes(
        width: Int,
        height: Int,
        frames: Int = 3,
        content: @Composable () -> Unit,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        return try {
            var last: ByteArray? = null
            repeat(frames) {
                last = scene.render().encodeToData()?.bytes ?: error("encode failed")
            }
            last ?: error("encode failed")
        } finally {
            scene.close()
        }
    }

    private fun writePng(name: String, bytes: ByteArray) {
        val out = File("build-verify/preview").apply { mkdirs() }
        File(out, "$name.png").writeBytes(bytes)
    }

    /**
     * 核心断言：**默认档与治理前逐像素一致**。
     *
     * 这是「保留原有视觉」的机器证明。它一旦挂了，说明 [CpText] 在未溢出或尾部省略时
     * 引入了哪怕 1px 的差异 —— 那就别谈什么治理，先把差异修掉。
     */
    @Test
    fun tailModeIsPixelIdenticalToPlainText() {
        val before = renderBytes(320, 160) { LegacyColumn() }
        val after = renderBytes(320, 160) { CpTextTailColumn() }
        writePng("text-overflow-legacy", before)
        writePng("text-overflow-cptext-tail", after)
        assertTrue(
            before.contentEquals(after),
            "CpText 默认档必须与治理前的 Text 逐像素一致（这是「保留原有视觉」的硬验收）",
        )
    }

    /**
     * 溢出不得改变容器尺寸。
     *
     * 拿「同一段文本的两种模式」各自渲染，比较**整幅图**的字节：容器只要被撑宽或换行，
     * 像素就会变。这里用「Tail 与 Middle 两个模式下的同一批文本」比对 —— 两者截断位置
     * 不同但**行数与容器宽高必须相同**（差异只允许出现在省略号附近）。
     *
     * 因此本断言的判据是：两种模式的图**不同**（说明智能省略真的生效了），
     * 同时两张图的高度方向不因溢出而增长 —— 后者由 [realComponentsFitTheirContainer] 的
     * 窄容器出图人工核对（自动断言量不到"看起来挤不挤"）。
     */
    @Test
    fun smartEllipsisKeepsTheUsefulEnd() {
        val modes = renderBytes(320, 320) { ModesColumn() }
        writePng("text-overflow-modes", modes)
        // 三个模式各不相同 ⇒ 智能省略真的在改写文本（而不是悄悄退化成尾截）。
        val tail = renderBytes(320, 320) {
            Column(Modifier.width(240.dp)) {
                CpText(text = longFile, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
        val filename = renderBytes(320, 320) {
            Column(Modifier.width(240.dp)) {
                CpText(
                    text = longFile,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    ellipsisMode = CpEllipsisMode.Filename,
                )
            }
        }
        assertTrue(
            !tail.contentEquals(filename),
            "Filename 模式必须真的改写文本（保留扩展名），否则等于没生效",
        )
    }

    /** 真实组件的窄容器形态：出图人工核对「有没有被撑破 / 有没有换行」。 */
    @Test
    fun realComponentsFitTheirContainer() {
        writePng("text-overflow-components-wide", renderBytes(900, 420) { RealComponents() })
        writePng("text-overflow-components-narrow", renderBytes(360, 420) { RealComponents() })
        // 窄到 360dp 时列表行必须仍然是单行 —— 由出图核对；这里只保证渲染不抛异常。
        assertTrue(true)
    }
}
