package cp.player.app.shortcut

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 桌面快捷键模型的不变量。
 *
 * 这一层坏了不会有编译错误 —— 表现是「重启后键位回到默认」「按 Ctrl+Shift+← 却快进了 5 秒」
 * 「两个动作抢同一个键，其中一个永远按不到」。所以逐条钉住：
 * 落盘格式可往返、匹配是**全等**、默认键位互不冲突、解绑与恢复默认是两件事。
 */
class ShortcutBindingTest {

    // ---------------------------------------------------------------- 按键白名单

    @Test
    fun `every shortcut key has a unique token and a unique physical key`() {
        val tokens = ShortcutKey.entries.map { it.token }
        assertEquals(tokens.size, tokens.toSet().size, "token 重复会让落盘后的绑定解析到错误的键：$tokens")

        val keys = ShortcutKey.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "两个 ShortcutKey 指向同一个物理键，反查会拿到不确定的那个")
    }

    @Test
    fun `ofKey and ofToken round trip`() {
        ShortcutKey.entries.forEach { shortcutKey ->
            assertEquals(shortcutKey, ShortcutKey.ofKey(shortcutKey.key))
            assertEquals(shortcutKey, ShortcutKey.ofToken(shortcutKey.token))
        }
        assertNull(ShortcutKey.ofToken("no_such_key"))
    }

    // ---------------------------------------------------------------- 序列化

    @Test
    fun `serialize and parse round trip`() {
        val samples = listOf(
            ShortcutBinding(ShortcutKey.SPACE),
            ShortcutBinding(ShortcutKey.RIGHT, ctrl = true, shift = true),
            ShortcutBinding(ShortcutKey.LEFT, ctrl = true),
            ShortcutBinding(ShortcutKey.COMMA, ctrl = true),
            ShortcutBinding(ShortcutKey.F5, shift = true, alt = true),
        )
        samples.forEach { binding ->
            val raw = binding.serialize()
            assertEquals(binding, ShortcutBinding.parse(raw), "往返后变了：$raw")
        }
        assertEquals("ctrl+shift+right", ShortcutBinding(ShortcutKey.RIGHT, ctrl = true, shift = true).serialize())
        assertEquals("space", ShortcutBinding(ShortcutKey.SPACE).serialize())
    }

    @Test
    fun `parse rejects garbage instead of guessing`() {
        assertNull(ShortcutBinding.parse(null))
        assertNull(ShortcutBinding.parse(""))
        assertNull(ShortcutBinding.parse("ctrl+"))
        assertNull(ShortcutBinding.parse("ctrl+nosuchkey"))
        // 「已解绑」哨兵不是绑定：调用方要能把它与「没记录」分开处理。
        assertNull(ShortcutBinding.parse(UNBOUND_SHORTCUT_MARKER))
    }

    @Test
    fun `display name lists modifiers then the key`() {
        assertEquals("Ctrl + Shift + ←", ShortcutBinding(ShortcutKey.LEFT, ctrl = true, shift = true).displayName)
        assertEquals("Alt + F5", ShortcutBinding(ShortcutKey.F5, alt = true).displayName)
        assertEquals("空格", ShortcutBinding(ShortcutKey.SPACE).displayName)
    }

    // ---------------------------------------------------------------- 匹配

    @Test
    fun `matching is exact - a prefix binding does not swallow a longer one`() {
        val seekForward = ShortcutBinding(ShortcutKey.RIGHT, ctrl = true)
        val nextTrack = ShortcutBinding(ShortcutKey.RIGHT, ctrl = true, shift = true)

        assertTrue(seekForward.matches(Key.DirectionRight, ctrl = true, shift = false, alt = false))
        assertFalse(
            seekForward.matches(Key.DirectionRight, ctrl = true, shift = true, alt = false),
            "Ctrl+→ 不该被 Ctrl+Shift+→ 触发 —— 否则「下一首」永远按不到",
        )
        assertTrue(nextTrack.matches(Key.DirectionRight, ctrl = true, shift = true, alt = false))
        assertFalse(nextTrack.matches(Key.DirectionRight, ctrl = true, shift = false, alt = false))
    }

    @Test
    fun `an unmodified binding is not triggered while a modifier is held`() {
        val shuffle = ShortcutBinding(ShortcutKey.S)
        assertTrue(shuffle.matches(Key.S, ctrl = false, shift = false, alt = false))
        assertFalse(shuffle.matches(Key.S, ctrl = true, shift = false, alt = false))
        assertFalse(shuffle.matches(Key.S, ctrl = false, shift = true, alt = false))
    }

    // ---------------------------------------------------------------- 动作清单

    @Test
    fun `action ids are unique and every hint explains something`() {
        val ids = ShortcutAction.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "动作 id 重复会互相覆盖落盘值：$ids")
        ShortcutAction.entries.forEach { action ->
            assertTrue(action.label.isNotBlank(), "id=${action.id} 缺动作名")
            assertTrue(action.hint.isNotBlank(), "id=${action.id} 缺说明")
        }
        assertEquals(ShortcutAction.BACK, ShortcutAction.of("back"))
    }

    @Test
    fun `default bindings do not collide with each other`() {
        val byBinding = ShortcutAction.entries
            .mapNotNull { action -> action.defaultBinding?.let { it to action } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        assertTrue(
            byBinding.isEmpty(),
            "默认键位撞车会让其中一个动作永远按不到（编译器不会提醒）：$byBinding",
        )
    }

    @Test
    fun `every default binding respects the escape-is-back convention`() {
        // Esc 只给「返回上一级」用：其它动作抢走它，用户按 Esc 想退回时会莫名其妙切歌 / 收藏。
        val escapeUsers = ShortcutAction.entries.filter { it.defaultBinding?.key == ShortcutKey.ESCAPE }
        assertEquals(listOf(ShortcutAction.BACK), escapeUsers.map { it })
    }

    // ---------------------------------------------------------------- 冲突检测

    @Test
    fun `conflict detection reports exactly the actions sharing a binding`() {
        val bindings = mapOf(
            "a" to ShortcutBinding(ShortcutKey.K, ctrl = true),
            "b" to ShortcutBinding(ShortcutKey.K, ctrl = true),
            "c" to ShortcutBinding(ShortcutKey.J),
            "d" to null,
        )
        assertEquals(setOf("a", "b"), findShortcutConflicts(bindings))
    }

    @Test
    fun `unbound and distinct bindings never conflict`() {
        val bindings = mapOf(
            "a" to null,
            "b" to null,
            "c" to ShortcutBinding(ShortcutKey.K),
            "d" to ShortcutBinding(ShortcutKey.J),
        )
        assertTrue(findShortcutConflicts(bindings).isEmpty())
    }

    @Test
    fun `a parsed binding is comparable with a constructed one`() {
        // 冲突检测靠 data class 的 equals：解析出来的实例必须与构造出来的相等，
        // 否则「两个动作绑同一个键」永远检测不出来。
        val parsed = assertNotNull(ShortcutBinding.parse("ctrl+shift+right"))
        assertEquals(ShortcutBinding(ShortcutKey.RIGHT, ctrl = true, shift = true), parsed)
    }
}
