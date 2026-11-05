package cp.player.app.ui.component

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证播放队列（播放列表）拖拽重排的接线、手势响应与稳定键逻辑。
 */
class QueueDragReorderWiringTest {

    private fun readSource(relative: String): String {
        var dir: File? = File("").absoluteFile
        val found = buildList {
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { add(it) }
                val underApp = File(File(dir, "app"), relative)
                if (underApp.isFile) add(underApp)
                dir = dir.parentFile
            }
        }.firstOrNull()
        check(found != null) { "找不到 $relative" }
        return found.readText()
    }

    @Test
    fun `QueueBottomSheet 拖拽手柄使用即时拖拽而非长按`() {
        val source = readSource("src/commonMain/kotlin/cp/player/app/ui/component/QueueBottomSheet.kt")
        assertTrue("detectDragGestures(" in source, "拖拽手柄应使用即时响应的 detectDragGestures")
        assertFalse("detectDragGesturesAfterLongPress(" in source, "独立拖拽手柄不应使用 detectDragGesturesAfterLongPress，避免 500ms 延迟阻碍桌面与移动端拖拽")
    }

    @Test
    fun `QueueBottomSheet 列表项 key 必须稳定且不包含当前下标`() {
        val source = readSource("src/commonMain/kotlin/cp/player/app/ui/component/QueueBottomSheet.kt")
        assertTrue("stableKeys" in source, "必须计算稳定键映射")
        assertFalse("key = { _, (idx, it) -> \"\$idx:" in source, "key 严禁混入当前下标 \$idx，否则换位即导致组件销毁并打断拖拽手势")
        assertTrue("key = { _, (originalIndex, it) -> stableKeys.getOrNull(originalIndex) ?: it.mediaId }" in source, "key 必须使用稳定键")
    }

    @Test
    fun `QueueBottomSheet 具备搜索保护与桌面光标提示`() {
        val source = readSource("src/commonMain/kotlin/cp/player/app/ui/component/QueueBottomSheet.kt")
        assertTrue("canDrag = searchQuery.isBlank() && queue.size > 1" in source, "搜索过滤状态或单曲队列应禁用拖拽手柄")
        assertTrue("pointerHoverIcon(PointerIcon.Hand)" in source, "桌面端悬停拖拽手柄应呈现手型光标提示")
    }

    @Test
    fun `QueueBottomSheet 拖拽时提供视觉层级提升与边缘滚动联动`() {
        val source = readSource("src/commonMain/kotlin/cp/player/app/ui/component/QueueBottomSheet.kt")
        assertTrue("zIndex(if (dragging) 2f else 0f)" in source, "拖拽条目必须提升 zIndex 避免被相邻项遮挡")
        assertTrue("shadowElevation" in source, "拖拽条目应具备阴影与浮起反馈")
        assertTrue("onMoveAndScroll" in source, "拖拽换位应与视口边缘自动滚动联动")
    }
}
