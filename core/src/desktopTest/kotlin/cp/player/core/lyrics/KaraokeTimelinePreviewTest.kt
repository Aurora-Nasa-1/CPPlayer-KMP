package cp.player.core.lyrics

import cp.player.core.playback.SyncedLyricLine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 逐字时序的**度量与出图**（Tier 2）。
 *
 * 编译和单测都量不到「这个字拖了 1.8 秒看着假不假」，所以这里做两件事：
 * 1. 对四类样曲（抒情慢歌含长过门 / 快歌 rap / 英文 / 日文）跑合成，断言客观指标；
 * 2. 把结果画成**时间条**写进 `build/karaoke-preview/timeline.html` ——
 *    灰底是该行的跨度，蓝块是每个字的亮区，末端剩下的灰色就是被正确留白的伴奏。
 *
 * 出图不是为了好看，是为了「一眼看出哪一句唱完之后还留了一大截」这类问题。
 */
class KaraokeTimelinePreviewTest {

    private data class Sample(val name: String, val lines: List<SyncedLyricLine>)

    // ============ 样曲 ============

    private val ballad = Sample(
        "抒情慢歌（26s 处有一段 16s 过门）",
        listOf(
            line(10_000, "风吹过你的脸庞"),
            line(14_200, "带走了一整个夏天"),
            line(18_600, "我还站在这里等"),
            line(22_800, "等一句答案"),
            // ↓ 这句之后是长过门：跨度 16 秒，但真实只唱 4 秒左右
            line(26_000, "我们说好的永远"),
            line(42_000, "怎么就散了"),
            line(46_400, "只剩下雨声"),
            line(50_800, "在耳边慢慢落下"),
        ),
    )

    private val rap = Sample(
        "快歌 / Rap（第 4 句之后有 6 秒过门）",
        listOf(
            line(5_000, "快使用双截棍哼哼哈兮"),
            line(7_600, "习武之人切记仁者无敌"),
            line(10_200, "是谁在练太极风生水起"),
            line(12_800, "快使用双截棍哼哼哈兮"),
            // ↓ 6 秒跨度，10 个字
            line(15_400, "如果我有轻功飞檐走壁"),
            line(21_400, "为人耿直不屈一身正气"),
            line(24_000, "哼"),
            line(26_600, "快使用双截棍"),
        ),
    )

    private val english = Sample(
        "英文",
        listOf(
            line(0, "I want it that way"),
            line(3_200, "tell me why"),
            line(5_600, "ain't nothing but a heartache"),
            line(9_800, "tell me why"),
            line(12_200, "ain't nothing but a mistake"),
            line(16_400, "I want it that way"),
        ),
    )

    private val japanese = Sample(
        "日文（含促音 / 长音）",
        listOf(
            line(1_000, "涙が溢れた"),
            line(5_200, "君の声がきこえる"),
            line(9_600, "がっこうの帰り道"),
            line(14_000, "ずっとー続いてる"),
            line(18_400, "忘れないよ"),
        ),
    )

    private fun line(time: Long, text: String) = SyncedLyricLine(time = time, text = text)

    // ============ 指标 + 出图 ============

    @Test
    fun `synthesized timelines look sane and keep interlude silence blank`() {
        val samples = listOf(ballad, rap, english, japanese)
        val report = StringBuilder()
        val svg = StringBuilder()

        var worstWord = 0L
        var outlierPairs = 0
        var totalPairs = 0

        for (sample in samples) {
            val result = SmartKaraokeLrcSynthesizer.synthesize(sample.lines)
            for (index in result.indices) {
                val words = result[index].words
                assertTrue(words.isNotEmpty(), "${sample.name} 第 $index 行应有逐字")
                assertTrue(
                    words.joinToString("") { it.text } == sample.lines[index].text,
                    "${sample.name} 第 $index 行切分必须守恒",
                )
                val nextStart = sample.lines.getOrNull(index + 1)?.time
                if (nextStart != null) {
                    assertTrue(
                        words.last().endTime <= nextStart,
                        "${sample.name} 第 $index 行末字 ${words.last().endTime} 越过下一行 $nextStart",
                    )
                }
                for (word in words) {
                    val duration = word.endTime - word.beginTime
                    worstWord = maxOf(worstWord, duration)
                }
                for (i in 0 until words.size - 1) {
                    val a = words[i].endTime - words[i].beginTime
                    val b = words[i + 1].endTime - words[i + 1].beginTime
                    totalPairs++
                    if (a > 0 && b > 0 && maxOf(a, b) > minOf(a, b) * 4) outlierPairs++
                }
            }
            report.appendLine(describe(sample, result))
            svg.append(renderSvg(sample, result))
        }

        // 单字不超过 2.4 秒（再长就是「拖住不动」而不是「唱」）
        assertTrue(worstWord <= 2_400L, "存在过长的字：$worstWord ms")
        // 相邻字时长比 >4 的占比要很低（促音/末字延音会造成个别极端值）
        val outlierRatio = outlierPairs.toFloat() / totalPairs.coerceAtLeast(1)
        assertTrue(outlierRatio <= 0.12f, "相邻字时长比过于悬殊：$outlierPairs/$totalPairs = $outlierRatio")

        // 过门那一行：唱的时间必须明显短于它的跨度
        val balladResult = SmartKaraokeLrcSynthesizer.synthesize(ballad.lines)
        val interludeIndex = 4
        val interludeWords = balladResult[interludeIndex].words
        val sung = interludeWords.last().endTime - ballad.lines[interludeIndex].time
        val span = ballad.lines[interludeIndex + 1].time - ballad.lines[interludeIndex].time
        assertTrue(
            sung < span * 0.6,
            "16 秒跨度里只该唱一小段，实际唱了 ${sung}ms / 跨度 ${span}ms",
        )
        report.appendLine("过门行：跨度 ${span}ms，实际唱 ${sung}ms，留白 ${span - sung}ms")

        writeArtifacts(svg.toString(), report.toString())
        println(report)
    }

    // ============ 文本报告 ============

    private fun describe(sample: Sample, result: List<SyncedLyricLine>): String {
        val sb = StringBuilder("── ${sample.name}\n")
        for (index in result.indices) {
            val line = sample.lines[index]
            val next = sample.lines.getOrNull(index + 1)?.time
            val words = result[index].words
            val sung = if (words.isEmpty()) 0 else words.last().endTime - words.first().beginTime
            val span = if (next == null) sung else next - line.time
            sb.append(
                "   %-14s 跨度 %5d  唱 %5d  留白 %5d  首字 %4dms 末字 %4dms\n".format(
                    line.text.take(14), span, sung, span - sung,
                    words.first().let { it.endTime - it.beginTime },
                    words.last().let { it.endTime - it.beginTime },
                ),
            )
        }
        return sb.toString()
    }

    // ============ 出图 ============

    private fun renderSvg(sample: Sample, result: List<SyncedLyricLine>): String {
        val start = sample.lines.first().time
        val end = result.last().words.lastOrNull()?.endTime ?: sample.lines.last().time + 4_000
        val tail = 3_000L
        val totalMs = (end + tail - start).coerceAtLeast(5_000)
        val width = 1_180
        val pxPerMs = width.toFloat() / totalMs
        val rowHeight = 26
        val headerHeight = 26
        val height = headerHeight + result.size * rowHeight + 8
        val sb = StringBuilder()

        sb.append("<h3>${sample.name}</h3>")
        sb.append("<svg width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\" ")
        sb.append("xmlns=\"http://www.w3.org/2000/svg\" font-family=\"system-ui, sans-serif\">")

        for (index in result.indices) {
            val line = sample.lines[index]
            val next = sample.lines.getOrNull(index + 1)?.time ?: (end + tail)
            val y = headerHeight + index * rowHeight
            val x = ((line.time - start) * pxPerMs).toInt()
            val w = ((next - line.time) * pxPerMs).toInt().coerceAtLeast(2)

            // 行跨度（灰底）：空白区一眼可见
            sb.append("<rect x=\"$x\" y=\"$y\" width=\"$w\" height=\"16\" rx=\"3\" fill=\"#F1EFE8\" stroke=\"#D3D1C7\" stroke-width=\"0.5\"/>")
            for (word in result[index].words) {
                val wx = ((word.beginTime - start) * pxPerMs).toInt()
                val ww = ((word.endTime - word.beginTime) * pxPerMs).toInt().coerceAtLeast(1)
                sb.append("<rect x=\"$wx\" y=\"$y\" width=\"$ww\" height=\"16\" rx=\"2\" fill=\"#B5D4F4\" stroke=\"#378ADD\" stroke-width=\"0.5\"/>")
            }
            sb.append(
                "<text x=\"$x\" y=\"${y + 11}\" font-size=\"8\" fill=\"#2C2C2A\" opacity=\"0.75\">" +
                    escape(line.text.take(10)) + "</text>",
            )
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun writeArtifacts(svg: String, report: String) {
        val dir = File(System.getProperty("user.dir"), "build/karaoke-preview")
        dir.mkdirs()
        File(dir, "timeline.html").writeText(
            """
            <!doctype html>
            <html lang="zh">
            <head>
            <meta charset="utf-8">
            <title>智能逐字 · 行内时间条</title>
            <style>
              body { background:#fff; color:#1F2328; font:14px/1.7 system-ui, "Segoe UI", sans-serif; margin:28px; }
              h1 { font-size:18px; font-weight:600; margin:0 0 4px; }
              p.lead { color:#5F5E5A; margin:0 0 18px; font-size:13px; }
              h3 { font-size:14px; font-weight:600; margin:22px 0 8px; }
              pre { background:#F6F8FA; border:1px solid #E5E7EB; border-radius:8px; padding:12px; font-size:12px; overflow:auto; }
              .legend { font-size:12px; color:#5F5E5A; margin-bottom:6px; }
              .sw { display:inline-block; width:22px; height:10px; border-radius:2px; vertical-align:middle; margin-right:6px; }
            </style>
            </head>
            <body>
            <h1>智能逐字 · 行内时间条</h1>
            <p class="lead">灰条是一行的跨度（上一行的开始到下一行的开始），蓝块是每个字的亮区。
            灰条末端剩下的一大截，就是被正确判成「伴奏留白」的部分 —— 它不会再被摊到每个字上。</p>
            <div class="legend">
              <span class="sw" style="background:#F1EFE8;border:1px solid #D3D1C7"></span>行跨度（含留白）
              <span class="sw" style="background:#B5D4F4;border:1px solid #378ADD;margin-left:14px"></span>字亮区
            </div>
            $svg
            <h3>度量报告</h3>
            <pre>${escape(report)}</pre>
            </body>
            </html>
            """.trimIndent(),
        )
        println("时间条已写出：${File(dir, "timeline.html").absolutePath}")
    }
}
