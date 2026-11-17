package cp.player.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageDeepLinkRoutingTest {
    @Test
    fun `非 Main 时保留待处理请求，进入 Main 后只处理一次`() {
        var pendingRequest: String? = "request"
        var handled = 0

        fun handle(isMain: Boolean) {
            val hasPending = pendingRequest != null
            if (!MessageDeepLinkRouting.shouldHandle(isMain, hasPending)) return
            handled++
            pendingRequest = null
        }

        handle(isMain = false)
        assertEquals("request", pendingRequest)
        assertEquals(0, handled)

        handle(isMain = true)
        handle(isMain = true)
        assertNull(pendingRequest)
        assertEquals(1, handled)
    }

    @Test
    fun `当前音源 B 时请求 A 精确选择 A 而不回退到 B`() {
        data class Provider(val id: String)
        val providers = listOf(Provider("A"), Provider("B"))
        val activeId = "B"
        val selected = MessageDeepLinkRouting.resolveProvider("A", providers) { it.id }

        assertNotNull(selected)
        assertEquals("A", selected.id)
        assertFalse(MessageDeepLinkRouting.isAlreadyActive("A", activeId))
        assertTrue(MessageDeepLinkRouting.isAlreadyActive("B", activeId))
        assertNull(MessageDeepLinkRouting.resolveProvider("missing", providers) { it.id })
    }
}
