package cp.player.app.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CpContextMenuPositionTest {

    private val windowSize = IntSize(1000, 800)
    private val menuSize = IntSize(200, 300)
    private val gap = 4
    private val margin = 8

    @Test
    fun `居中右键 默认向右下展开`() {
        val provider = CpContextMenuPositionProvider(
            cursorOffset = Offset(300f, 200f),
            gapPx = gap,
            marginPx = margin,
        )
        val pos = provider.calculatePosition(
            anchorBounds = IntRect(0, 0, 1000, 800),
            windowSize = windowSize,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = menuSize,
        )
        assertEquals(IntOffset(300 + gap, 200 + gap), pos)
        assertTrue(pos.x >= margin)
        assertTrue(pos.x + menuSize.width <= windowSize.width - margin)
        assertTrue(pos.y >= margin)
        assertTrue(pos.y + menuSize.height <= windowSize.height - margin)
    }

    @Test
    fun `靠右右键 水平动态向左翻转`() {
        val provider = CpContextMenuPositionProvider(
            cursorOffset = Offset(900f, 200f),
            gapPx = gap,
            marginPx = margin,
        )
        val pos = provider.calculatePosition(
            anchorBounds = IntRect(0, 0, 1000, 800),
            windowSize = windowSize,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = menuSize,
        )
        // 右侧空间 100px 不足 200px 菜单，应翻转至左侧：900 - 4 - 200 = 696
        assertEquals(900 - gap - menuSize.width, pos.x)
        assertEquals(200 + gap, pos.y)
        assertTrue(pos.x >= margin)
        assertTrue(pos.x + menuSize.width <= windowSize.width - margin)
    }

    @Test
    fun `靠底右键 垂直动态向上翻转`() {
        val provider = CpContextMenuPositionProvider(
            cursorOffset = Offset(300f, 750f),
            gapPx = gap,
            marginPx = margin,
        )
        val pos = provider.calculatePosition(
            anchorBounds = IntRect(0, 0, 1000, 800),
            windowSize = windowSize,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = menuSize,
        )
        // 下方空间 50px 不足 300px 菜单，应翻转至上方：750 - 4 - 300 = 446
        assertEquals(300 + gap, pos.x)
        assertEquals(750 - gap - menuSize.height, pos.y)
        assertTrue(pos.y >= margin)
        assertTrue(pos.y + menuSize.height <= windowSize.height - margin)
    }

    @Test
    fun `靠右下角右键 水平与垂直同时动态翻转`() {
        val provider = CpContextMenuPositionProvider(
            cursorOffset = Offset(950f, 750f),
            gapPx = gap,
            marginPx = margin,
        )
        val pos = provider.calculatePosition(
            anchorBounds = IntRect(0, 0, 1000, 800),
            windowSize = windowSize,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = menuSize,
        )
        assertEquals(950 - gap - menuSize.width, pos.x)
        assertEquals(750 - gap - menuSize.height, pos.y)
        assertTrue(pos.x >= margin)
        assertTrue(pos.x + menuSize.width <= windowSize.width - margin)
        assertTrue(pos.y >= margin)
        assertTrue(pos.y + menuSize.height <= windowSize.height - margin)
    }

    @Test
    fun `极值边界坐标 自动钳制在安全边距内`() {
        val provider = CpContextMenuPositionProvider(
            cursorOffset = Offset(1000f, 800f),
            gapPx = gap,
            marginPx = margin,
        )
        val pos = provider.calculatePosition(
            anchorBounds = IntRect(0, 0, 1000, 800),
            windowSize = windowSize,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = menuSize,
        )
        // 必须完全落在 [margin, windowSize - menuSize - margin] 内
        assertEquals(windowSize.width - menuSize.width - margin, pos.x)
        assertEquals(windowSize.height - menuSize.height - margin, pos.y)
    }

    @Test
    fun `左上角极值坐标 钳制在安全边距内`() {
        val provider = CpContextMenuPositionProvider(
            cursorOffset = Offset(0f, 0f),
            gapPx = gap,
            marginPx = margin,
        )
        val pos = provider.calculatePosition(
            anchorBounds = IntRect(0, 0, 1000, 800),
            windowSize = windowSize,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = menuSize,
        )
        assertEquals(margin, pos.x)
        assertEquals(margin, pos.y)
    }

    @Test
    fun `RTL 环境下优先向左展开 空间不足时向右翻转`() {
        val providerNormal = CpContextMenuPositionProvider(
            cursorOffset = Offset(500f, 200f),
            gapPx = gap,
            marginPx = margin,
        )
        val posNormal = providerNormal.calculatePosition(
            anchorBounds = IntRect(0, 0, 1000, 800),
            windowSize = windowSize,
            layoutDirection = LayoutDirection.Rtl,
            popupContentSize = menuSize,
        )
        // RTL 下优先向左：500 - 4 - 200 = 296
        assertEquals(500 - gap - menuSize.width, posNormal.x)

        val providerLeftEdge = CpContextMenuPositionProvider(
            cursorOffset = Offset(100f, 200f),
            gapPx = gap,
            marginPx = margin,
        )
        val posLeftEdge = providerLeftEdge.calculatePosition(
            anchorBounds = IntRect(0, 0, 1000, 800),
            windowSize = windowSize,
            layoutDirection = LayoutDirection.Rtl,
            popupContentSize = menuSize,
        )
        // 左侧空间不足，翻转至右侧：100 + 4 = 104
        assertEquals(100 + gap, posLeftEdge.x)
    }

    @Test
    fun `锚定菜单下方空间不足时动态翻转至上方`() {
        val provider = CpAnchoredMenuPositionProvider(
            gapPx = gap,
            marginPx = margin,
        )
        // 按钮在 (800, 750)，高度 40
        val pos = provider.calculatePosition(
            anchorBounds = IntRect(800, 750, 840, 790),
            windowSize = windowSize,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = menuSize,
        )
        // 下方 790 到 800 只有 10px，不足 300px，翻转至上方：750 - 4 - 300 = 446
        assertEquals(750 - gap - menuSize.height, pos.y)
        // 水平右对齐按钮：840 - 200 = 640
        assertEquals(640, pos.x)
        assertTrue(pos.y >= margin)
        assertTrue(pos.y + menuSize.height <= windowSize.height - margin)
    }
}
