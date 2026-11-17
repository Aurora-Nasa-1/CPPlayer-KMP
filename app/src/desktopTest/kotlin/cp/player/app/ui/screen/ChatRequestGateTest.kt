package cp.player.app.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRequestGateTest {
    private fun session(
        peerUid: Long,
        providerId: String = "provider-a",
        sourceGeneration: Int = 0,
        myUid: Long = 99L,
    ) = ChatSessionIdentity(providerId, sourceGeneration, myUid, peerUid)

    @Test
    fun `switching conversations invalidates the previous request`() {
        val gate = ChatRequestGate()
        val firstSession = session(peerUid = 11L)
        val secondSession = session(peerUid = 22L)
        val first = gate.begin(firstSession)
        val second = gate.begin(secondSession)

        assertFalse(gate.isCurrent(firstSession, first))
        assertTrue(gate.isCurrent(secondSession, second))
        assertNull(gate.currentToken(firstSession))
        assertEquals(second, gate.currentToken(secondSession))
    }

    @Test
    fun `changing provider invalidates the same numeric conversation id`() {
        val gate = ChatRequestGate()
        val firstSession = session(peerUid = 11L, providerId = "provider-a")
        val nextSession = session(peerUid = 11L, providerId = "provider-b", sourceGeneration = 1)
        val first = gate.begin(firstSession)
        val next = gate.begin(nextSession)

        assertFalse(gate.isCurrent(firstSession, first))
        assertTrue(gate.isCurrent(nextSession, next))
    }

    @Test
    fun `a newer reload invalidates the earlier request for the same conversation`() {
        val gate = ChatRequestGate()
        val session = session(peerUid = 11L)
        val first = gate.begin(session)
        val reload = gate.begin(session)

        assertFalse(gate.isCurrent(session, first))
        assertTrue(gate.isCurrent(session, reload))
    }

    @Test
    fun `reset invalidates in flight work`() {
        val gate = ChatRequestGate()
        val session = session(peerUid = 11L)
        val token = gate.begin(session)
        gate.invalidate()

        assertFalse(gate.isCurrent(session, token))
        assertNull(gate.currentToken(session))
    }
}
