package cp.player.app.repository

import cp.player.sdk.PrivateMessage
import cp.player.sdk.SocialUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SocialRepositoryMappingTest {
    private fun privateMessage(id: String) = PrivateMessage(
        id = id,
        sender = SocialUser(id = "42", nickname = "Alice"),
        recipient = SocialUser(id = "7", nickname = "Me"),
        text = "hello",
        sentAtEpochMs = 1_700_000_000_000L,
    )

    @Test
    fun `preserves opaque provider IDs exactly`() {
        for (id in listOf("00042", "msg:abc-001", "CaseSensitive-ID")) {
            val mapped = privateMessage(id).toMessage(myUid = 7L)
            assertNotNull(mapped, "valid message ID '$id' must not make the message disappear")
            assertEquals(id, mapped.id)
        }
    }
}
