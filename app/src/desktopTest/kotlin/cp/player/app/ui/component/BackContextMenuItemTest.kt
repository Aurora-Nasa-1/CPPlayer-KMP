package cp.player.app.ui.component

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import cp.player.app.platform.DesktopBackDispatcher
import cp.player.app.ui.util.DesktopShell
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「返回上一级」右键菜单项的**判据与动作**回归。
 *
 * 这一项坏掉的方式非常隐蔽：编译过、也不报错，只是**某些页面右键时菜单里没有它**，
 * 或者**点了没反应**。用户报的正是「设置界面 / 歌单界面的右键菜单里没有返回」——
 * 那两个位置在桌面端是**内嵌面板**，能退，但既没有 push 路由、也不在页面自己的判据里。
 *
 * 所以这里逐一钉住三条判据来源（Navigator 栈深 / 壳层面板状态 / 页面自己的处理器）
 * 与「先派发给页面处理器」的动作顺序。
 */
class BackContextMenuItemTest {

    @AfterTest
    fun tearDown() {
        // 这两个是全局单向指令状态，测试之间必须复位，否则会串味。
        DesktopShell.pageCanGoBack = false
        DesktopShell.backRequested = false
    }

    /** 在一个离屏场景里组合一次，取出当前的菜单项。 */
    private fun resolveItem(): CpContextMenuItem? {
        var captured: CpContextMenuItem? = null
        val scene = ImageComposeScene(width = 160, height = 160, density = Density(1f)) {
            // ⚠️ 组合调用要写在 content 体里，不能塞进 SideEffect 的 lambda
            //（那不是 @Composable 上下文）。
            val item = rememberBackContextMenuItem()
            SideEffect { captured = item }
        }
        try {
            scene.render()
            return captured
        } finally {
            scene.close()
        }
    }

    @Test
    fun `退无可退时不显示这一项`() {
        // 场景里没有 Navigator（栈深 1）、壳层也没报可退、也没有页面处理器。
        assertFalse(DesktopBackDispatcher.hasHandlers, "前置条件：测试开始前不该有页面处理器")
        assertNull(resolveItem(), "退无可退时应当返回 null，而不是摆一个点了没反应的灰项")
    }

    @Test
    fun `壳层可退时出现 设置与歌单这类内嵌面板就是这条`() {
        DesktopShell.pageCanGoBack = true
        val item = assertNotNull(resolveItem(), "壳层可退时右键菜单里必须有「返回上一级」")
        assertEquals("返回上一级", item.label)
    }

    @Test
    fun `页面自己的处理器也算能退 且优先于壳层`() {
        var pageHandled = false
        val token = DesktopBackDispatcher.register { pageHandled = true }
        try {
            DesktopShell.pageCanGoBack = true
            val item = assertNotNull(resolveItem(), "页面注册了返回处理器时也必须有这一项")
            item.onClick()
            assertTrue(pageHandled, "动作必须先派发给页面自己的处理器（播放页展开态 / 歌单多选）")
            assertFalse(DesktopShell.backRequested, "页面处理器接手后不该再动壳层")
        } finally {
            DesktopBackDispatcher.unregister(token)
        }
    }

    @Test
    fun `没有页面处理器时动作落到壳层`() {
        DesktopShell.pageCanGoBack = true
        DesktopShell.backRequested = false
        val item = assertNotNull(resolveItem())
        item.onClick()
        assertTrue(
            DesktopShell.backRequested,
            "没有页面处理器接手时，「返回上一级」应当请壳层收面板 / 弹内嵌栈",
        )
    }
}
