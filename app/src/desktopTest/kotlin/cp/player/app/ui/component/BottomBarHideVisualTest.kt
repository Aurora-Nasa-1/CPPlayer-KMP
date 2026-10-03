package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 底栏「收起」必须看**像素**和**命中测试** —— 只量 `onSizeChanged` 会漏掉整类 bug。
 *
 * 真实症状：「逻辑隐藏了，但还显示，还能触摸」。当年 `AppNavigationBar` 是
 * `Modifier.layout{}.clipToBounds()` 且把内容 `placeRelative(0, visible - height)`
 * 往上挪：`layout{}` 会把**它的整个子树（连同内侧的裁切节点）一起平移**，裁切框跟着
 * 内容走 ⇒ 永远裁不到东西；而「往上挪」又把底栏挪回了可视区。于是上报给 Scaffold 的
 * 高度已经是 0（Scaffold 以为底栏没了），画面上照画、点击照命中。
 *
 * 现在：`clipToBounds()` 在 `layout{}` **外侧**（裁切框固定在本节点左上角、尺寸取
 * 内侧量出的可视高度），内容**顶对齐**（往下越界被裁）—— 收起时底栏向下沉出屏幕。
 *
 * 用例把底栏渲成纯红色块，直接读 PNG 像素 + 在底栏原位置发点击。
 * 出图落在 `app/build-preview-bbar/`，可直接眼看。
 */
class BottomBarHideVisualTest {

    @Test
    fun `收起后底栏不该再被绘制 也不该再接收点击`() {
        // 全显：整条底栏（场景 400 高、底栏 80dp ⇒ 占 [320,400]）都该是红的、点得到。
        val shown = probe(fraction = 0f, "bottombar-full")
        println("[visual] fraction=0  $shown")
        assertTrue(shown.drawingAt(top = true), "全显时底栏上半部应该画着（$shown）")
        assertTrue(shown.drawingAt(bottom = true), "全显时底栏下半部应该画着（$shown）")
        assertTrue(shown.clickAtBar, "全显时底栏应该点得到")

        // 半收：只剩底栏**上半**那段落在屏内（[360,400]）—— 下半部已沉出屏幕。
        val half = probe(fraction = 0.5f, "bottombar-half")
        println("[visual] fraction=0.5 $half")
        assertTrue(half.drawingAt(bottom = true), "半收时可见段应该还画着（$half）")
        assertTrue(!half.drawingAt(top = true), "半收时已经沉出屏幕的那段不该再画（$half）")

        // 全收：不画、不响应点击。
        val hidden = probe(fraction = 1f, "bottombar-hidden")
        println("[visual] fraction=1  $hidden")
        assertTrue(!hidden.drawingAt(bottom = true), "收起后底栏仍然被绘制（$hidden）—— 裁切没生效")
        assertTrue(!hidden.drawingAt(top = true), "收起后底栏仍然被绘制（$hidden）")
        assertTrue(!hidden.clickAtBar, "收起后底栏仍然接收点击（$hidden）—— 命中测试没跟着收缩")
    }
}

private class VisualProbe(
    /** 底栏上半段（屏内 y=325 附近）的红值 0..255。 */
    val topRed: Int,
    /** 底栏下半段（屏内 y=395 附近）的红值 0..255。 */
    val bottomRed: Int,
    val clickAtBar: Boolean,
    /** 场景内多个 y 的红值，用来看清到底画在哪一段。 */
    val column: List<Pair<Int, Int>>,
    /** 底栏根节点上报给 Scaffold 的高度（px）。 */
    val reportedHeight: Int,
    /** Scaffold 给内容区的 bottom padding（px）。 */
    val contentBottomPadding: Int,
) {
    fun drawingAt(top: Boolean = false, bottom: Boolean = false) = if (top) topRed > 150 else bottomRed > 150

    override fun toString() =
        "上半段红=$topRed 下半段红=$bottomRed 点击命中=$clickAtBar " +
            "上报高度=$reportedHeight 内容底距=$contentBottomPadding 根=$dbgRoot 红块=$dbgChild " +
            "逐行(${column.joinToString { "${it.first}:${it.second}" }})"
}

/** 场景 200x400、底栏 80dp ⇒ 全显时底栏占 [320,400]。 */
private const val SAMPLE_X = 100
private const val BAR_TOP_SAMPLE_Y = 325
private const val BAR_BOTTOM_SAMPLE_Y = 395

/** 诊断用：根节点 / 红块在根坐标系里的 (top, height)。 */
private var dbgRoot = "-"
private var dbgChild = "-"

private fun probe(fraction: Float, fileName: String): VisualProbe {
    val clicked = booleanArrayOf(false)
    val reported = intArrayOf(-1)
    val contentBottom = intArrayOf(-1)
    val scene = ImageComposeScene(width = 200, height = 400, density = Density(1f)) {
        Scaffold(
            bottomBar = {
                // 外面再包一层只为量「底栏根节点上报给 Scaffold 的高度」——
                // `onSizeChanged` 报的是它**内侧**量出来的尺寸。
                Box(Modifier.onSizeChanged { reported[0] = it.height }) {
                    BottomBarClone(hideFraction = fraction, onClick = { clicked[0] = true })
                }
            },
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding).background(Color.White)
                    // 内容区实测高度 ⇒ Scaffold 实际给底栏留了多少（场景高 400）。
                    .onSizeChanged { contentBottom[0] = 400 - it.height },
            )
        }
    }
    try {
        scene.render()
        val data = scene.render().encodeToData() ?: error("encode failed")
        val png = File("build-preview-bbar/$fileName.png").apply { parentFile.mkdirs() }
        png.writeBytes(data.bytes)
        val img = ImageIO.read(png)

        // ⚠️ 判「红」必须同时看红与**绿**通道：只判红通道的话白色（RRGGBB=FFFFFF）也是 255，
        // 会把「没画」误判成「画着」（这一版测试第一稿就栽在这）。
        fun barRedAt(y: Int): Int {
            val rgb = img.getRGB(SAMPLE_X, y)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            return if (r > 150 && g < 80) 255 else 0
        }

        // 底栏全显时占 [320,400]：逐行采样看清「到底画在哪一段」。
        val column = listOf(325, 345, 365, 385, 395).map { y -> y to barRedAt(y) }

        scene.sendPointerEvent(
            PointerEventType.Press,
            Offset(SAMPLE_X.toFloat(), BAR_BOTTOM_SAMPLE_Y.toFloat()),
            Offset.Zero,
            0L,
            PointerType.Touch,
        )
        scene.sendPointerEvent(
            PointerEventType.Release,
            Offset(SAMPLE_X.toFloat(), BAR_BOTTOM_SAMPLE_Y.toFloat()),
            Offset.Zero,
            16L,
            PointerType.Touch,
        )
        scene.render()
        return VisualProbe(
            topRed = column.first { it.first == 325 }.second,
            bottomRed = column.first { it.first == 395 }.second,
            clickAtBar = clicked[0],
            column = column,
            reportedHeight = reported[0],
            contentBottomPadding = contentBottom[0],
        )
    } finally {
        scene.close()
    }
}

/** 与 `MainScreen.AppNavigationBar` 同款：裁切在外、内容顶对齐、槽位高度 = 可视高度。 */
@Composable
private fun BottomBarClone(hideFraction: Float, onClick: () -> Unit) {
    Box(
        Modifier
            .onGloballyPositioned { dbgRoot = "top=${it.positionInRoot().y.toInt()} h=${it.size.height}" }
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val visible = (placeable.height * (1f - hideFraction.coerceIn(0f, 1f)))
                    .roundToInt()
                    .coerceAtLeast(0)
                layout(placeable.width, visible) { placeable.placeRelative(0, 0) }
            },
    ) {
        Box(
            Modifier.fillMaxWidth().height(80.dp)
                .background(Color.Red)
                .onGloballyPositioned { dbgChild = "top=${it.positionInRoot().y.toInt()} h=${it.size.height}" }
                .clickable { onClick() },
        )
    }
}
