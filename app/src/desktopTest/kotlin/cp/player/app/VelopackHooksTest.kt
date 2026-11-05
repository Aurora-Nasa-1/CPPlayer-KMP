package cp.player.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Velopack 钩子参数的识别。
 *
 * 这一层坏了不会编译报错，只会以「Windows 安装/卸载报『安装未完成』」的形式出现在
 * **真机装包之后** —— 本地 `desktopRun` 永远复现不了。所以在这里钉住三条不变量：
 * 四条钩子都能认出来、版本号取的是紧随其后的那个参数、正常启动不被误判。
 */
class VelopackHooksTest {

    @Test
    fun `all four hooks are recognised with their version`() {
        VelopackHookArgs.forEach { arg ->
            val hook = velopackHookOf(arrayOf(arg, "1.6.1"))
            assertEquals(arg, hook?.arg, "$arg 没被认出来")
            assertEquals("1.6.1", hook?.version, "$arg 的版本号取错了")
        }
    }

    @Test
    fun `hook is found regardless of position among other args`() {
        val hook = velopackHookOf(arrayOf("--mobile", "--veloapp-updated", "1.6.1", "-m"))
        assertEquals("--veloapp-updated", hook?.arg)
        assertEquals("1.6.1", hook?.version)
    }

    @Test
    fun `normal launches are not treated as hooks`() {
        assertNull(velopackHookOf(emptyArray()))
        assertNull(velopackHookOf(arrayOf("--mobile", "-m")))
        // 子串/前缀不算：只有全等的单个参数才是钩子。
        assertNull(velopackHookOf(arrayOf("--veloapp-install-later", "1.6.1")))
        assertNull(velopackHookOf(arrayOf("foo--veloapp-install", "1.6.1")))
    }

    @Test
    fun `missing version degrades to empty string instead of failing`() {
        assertEquals("", velopackHookOf(arrayOf("--veloapp-uninstall"))?.version)
    }
}
