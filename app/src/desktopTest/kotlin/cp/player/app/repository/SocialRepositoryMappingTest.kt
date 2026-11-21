package cp.player.app.repository

import cp.player.core.BackendResult
import cp.player.core.music.UnifiedMusicSource
import cp.player.sdk.ConversationPage
import cp.player.sdk.PrivateMessage
import cp.player.sdk.SocialUser
import cp.player.sdk.SocialUserPage
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `empty conversations remain empty when contacts are unsupported`() {
        runBlocking {
            val repo = repository(BackendResult.Success(ConversationPage()), BackendResult.Unsupported("no contacts"))
            assertEquals(BackendResult.Success(emptyList()), repo.getContacts())
        }
    }

    @Test
    fun `contacts failure is retryable when conversations are unsupported`() {
        runBlocking {
            val repo = repository(BackendResult.Unsupported("no conversations"), BackendResult.Error("network"))
            assertEquals(BackendResult.Error("network"), repo.getContacts())
        }
    }

    private fun repository(conversations: BackendResult<ConversationPage>, contacts: BackendResult<SocialUserPage>) =
        SocialRepository(Proxy.newProxyInstance(
            UnifiedMusicSource::class.java.classLoader,
            arrayOf(UnifiedMusicSource::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPrivateMessages" -> conversations
                "getRecentContacts" -> contacts
                else -> error("Unexpected call: ${method.name}")
            }
        } as UnifiedMusicSource)
}
