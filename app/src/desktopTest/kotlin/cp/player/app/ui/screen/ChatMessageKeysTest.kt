package cp.player.app.ui.screen

import cp.player.core.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChatMessageKeysTest {
    private fun message(
        id: String,
        text: String,
        time: Long,
        fromUserId: Long = 42L,
    ) = Message(
        id = id,
        fromUserId = fromUserId,
        fromNickname = "Alice",
        fromAvatarUrl = "",
        text = text,
        time = time,
    )

    @Test
    fun `opaque IDs including leading zeroes and punctuation remain distinct`() {
        val messages = listOf(
            message(id = "0007", text = "first", time = 1),
            message(id = "7", text = "second", time = 2),
            message(id = "msg:part-1", text = "third", time = 3),
        )

        val keys = stableChatMessageKeys(messages)

        assertEquals(3, keys.toSet().size)
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `keys survive insertion of an older unrelated message`() {
        val older = message(id = "old", text = "older", time = 1)
        val first = message(id = "id:1", text = "first", time = 2)
        val second = message(id = "id:2", text = "second", time = 3)
        val original = stableChatMessageKeys(listOf(first, second))
        val afterInsertion = stableChatMessageKeys(listOf(older, first, second))

        assertEquals(original[0], afterInsertion[1])
        assertEquals(original[1], afterInsertion[2])
    }

    @Test
    fun `duplicate IDs remain unique and exact duplicate records remain deterministic`() {
        val first = message(id = "same", text = "one", time = 1)
        val second = message(id = "same", text = "two", time = 2)
        val duplicate = message(id = "same", text = "two", time = 2)
        val messages = listOf(first, second, duplicate)

        val keys = stableChatMessageKeys(messages)

        assertEquals(messages.size, keys.toSet().size)
        assertEquals(keys, stableChatMessageKeys(messages))
    }

    @Test
    fun `only strict appends are eligible for animated scrolling`() {
        val original = listOf("a", "b")
        assertTrue(isStrictlyAppendedChatList(original, listOf("a", "b", "c")))
        assertFalse(isStrictlyAppendedChatList(emptyList(), listOf("a")))
        assertFalse(isStrictlyAppendedChatList(original, original))
        assertFalse(isStrictlyAppendedChatList(original, listOf("x", "a", "b")))
        assertFalse(isStrictlyAppendedChatList(original, listOf("a")))
    }
}
