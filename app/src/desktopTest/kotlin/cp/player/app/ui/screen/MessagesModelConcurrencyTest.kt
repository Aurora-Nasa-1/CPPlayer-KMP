package cp.player.app.ui.screen

import cp.player.app.i18n.CpStringsZh
import cp.player.core.BackendResult
import cp.player.core.model.Contact
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessagesModelConcurrencyTest {
    private fun contact(uid: Long) = Contact(
        userId = uid,
        nickname = "peer-$uid",
        avatarUrl = "",
        lastMessage = "message-$uid",
        lastMessageTime = uid,
        unreadCount = 0,
    )

    private fun session(
        providerId: String,
        sourceGeneration: Int,
        accountUid: Long,
    ) = MessagesSessionKey(providerId, sourceGeneration, accountUid)

    private suspend fun awaitContact(model: MessagesModel, uid: Long) {
        withTimeout(2_000L) {
            while (model.state.value.contacts.singleOrNull()?.userId != uid) yield()
        }
    }

    @Test
    fun `provider and account switch clears contacts and ignores a late old response`() = runBlocking {
        val oldRequestStarted = CompletableDeferred<Unit>()
        val releaseOldRequest = CompletableDeferred<BackendResult<List<Contact>>>()
        var fetchCount = 0
        val model = MessagesModel(
            fetchContacts = {
                if (++fetchCount == 1) {
                    oldRequestStarted.complete(Unit)
                    withContext(NonCancellable) { releaseOldRequest.await() }
                } else {
                    BackendResult.Success(listOf(contact(22L)))
                }
            },
            subscribedUids = { emptySet() },
            requestScope = this,
        )

        model.load(CpStringsZh, session("provider-a", sourceGeneration = 0, accountUid = 99L))
        oldRequestStarted.await()

        model.load(CpStringsZh, session("provider-b", sourceGeneration = 1, accountUid = 100L))
        assertTrue(model.state.value.loading)
        assertTrue(model.state.value.contacts.isEmpty(), "old provider/account contacts must clear synchronously")
        awaitContact(model, 22L)

        releaseOldRequest.complete(BackendResult.Success(listOf(contact(11L))))
        yield()
        assertEquals(22L, model.state.value.contacts.single().userId)
    }

    @Test
    fun `newer retry in the same session wins over a late response`() = runBlocking {
        val firstRequestStarted = CompletableDeferred<Unit>()
        val releaseFirstRequest = CompletableDeferred<BackendResult<List<Contact>>>()
        var fetchCount = 0
        val model = MessagesModel(
            fetchContacts = {
                if (++fetchCount == 1) {
                    firstRequestStarted.complete(Unit)
                    withContext(NonCancellable) { releaseFirstRequest.await() }
                } else {
                    BackendResult.Success(listOf(contact(22L)))
                }
            },
            subscribedUids = { emptySet() },
            requestScope = this,
        )
        val key = session("provider-a", sourceGeneration = 0, accountUid = 99L)

        model.load(CpStringsZh, key)
        firstRequestStarted.await()
        model.load(CpStringsZh, key)
        awaitContact(model, 22L)

        releaseFirstRequest.complete(BackendResult.Success(listOf(contact(11L))))
        yield()
        assertEquals(22L, model.state.value.contacts.single().userId)
    }

    @Test
    fun `state for a new provider masks previous contacts before its effect starts`() = runBlocking {
        val model = MessagesModel(
            fetchContacts = { BackendResult.Success(listOf(contact(11L))) },
            subscribedUids = { emptySet() },
            requestScope = this,
        )
        val oldSession = session("provider-a", sourceGeneration = 0, accountUid = 99L)
        val newSession = session("provider-b", sourceGeneration = 1, accountUid = 99L)

        model.load(CpStringsZh, oldSession)
        awaitContact(model, 11L)
        assertTrue(model.stateFor(newSession, model.state.value).contacts.isEmpty())
    }
}
