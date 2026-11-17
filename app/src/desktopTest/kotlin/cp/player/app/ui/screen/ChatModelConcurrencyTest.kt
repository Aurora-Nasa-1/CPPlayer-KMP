package cp.player.app.ui.screen

import cp.player.app.i18n.CpStringsZh
import cp.player.core.BackendResult
import cp.player.core.model.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatModelConcurrencyTest {
    private fun message(peerUid: Long) = Message(
        id = "message-$peerUid",
        fromUserId = peerUid,
        fromNickname = "peer-$peerUid",
        fromAvatarUrl = "",
        text = "peer-$peerUid message",
        time = peerUid,
    )

    @Test
    fun `peer switch synchronously resets sending and ignores late send completion`() = runBlocking {
        val sendResult = CompletableDeferred<Boolean>()
        val model = ChatModel(
            fetchMessages = { peerUid, _ -> BackendResult.Success(listOf(message(peerUid))) },
            sendText = { _, _ -> sendResult.await() },
            requestScope = this,
        )

        model.load(peerUid = 11L, myUid = 99L, strings = CpStringsZh)
        yield()
        assertEquals("peer-11 message", model.state.value.messages.single().text)

        var callbackResult: Boolean? = null
        model.send(11L, 99L, " hello ", CpStringsZh) { callbackResult = it }
        assertTrue(model.state.value.sending)
        yield() // Let the send request suspend on sendResult.

        model.load(peerUid = 22L, myUid = 99L, strings = CpStringsZh)
        assertTrue(model.state.value.loading)
        assertTrue(model.state.value.messages.isEmpty(), "A peer switch must clear old messages synchronously")
        assertFalse(model.state.value.sending, "A peer switch must not retain the previous peer's sending state")
        yield()
        assertEquals("peer-22 message", model.state.value.messages.single().text)

        sendResult.complete(true)
        yield()
        assertEquals("peer-22 message", model.state.value.messages.single().text)
        assertFalse(model.state.value.sending)
        assertNull(callbackResult, "A stale send must not report a result into the new conversation")
    }

    @Test
    fun `switching peer cancels an old history request and preserves the new result`() = runBlocking {
        val oldRequestStarted = CompletableDeferred<Unit>()
        val oldRequestResult = CompletableDeferred<BackendResult<List<Message>>>()
        var oldRequestCancelled = false
        val model = ChatModel(
            fetchMessages = { peerUid, _ ->
                if (peerUid == 11L) {
                    oldRequestStarted.complete(Unit)
                    try {
                        oldRequestResult.await()
                    } catch (cancel: CancellationException) {
                        oldRequestCancelled = true
                        throw cancel
                    }
                } else {
                    BackendResult.Success(listOf(message(peerUid)))
                }
            },
            requestScope = this,
        )

        model.load(peerUid = 11L, myUid = 99L, strings = CpStringsZh)
        oldRequestStarted.await()
        model.load(peerUid = 22L, myUid = 99L, strings = CpStringsZh)
        yield()
        assertTrue(oldRequestCancelled, "Changing peers should cancel the previous history request")

        oldRequestResult.complete(BackendResult.Success(listOf(message(11L))))
        yield()
        assertEquals("peer-22 message", model.state.value.messages.single().text)
        assertNull(model.state.value.error)
    }

    @Test
    fun `cancelling a current send clears its sending indicator`() = runBlocking {
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sendStarted = CompletableDeferred<Unit>()
        val sendResult = CompletableDeferred<Boolean>()
        val model = ChatModel(
            fetchMessages = { peerUid, _ -> BackendResult.Success(listOf(message(peerUid))) },
            sendText = { _, _ ->
                sendStarted.complete(Unit)
                sendResult.await()
            },
            requestScope = requestScope,
        )
        try {
            model.load(peerUid = 11L, myUid = 99L, strings = CpStringsZh)
            model.send(11L, 99L, "hello", CpStringsZh) { }
            sendStarted.await()
            assertTrue(model.state.value.sending)

            requestScope.cancel()
            assertFalse(model.state.value.sending)
        } finally {
            requestScope.cancel()
        }
    }

    @Test
    fun `same peer under a different provider clears the old conversation synchronously`() = runBlocking {
        var fetchCount = 0
        val model = ChatModel(
            fetchMessages = { peerUid, _ ->
                val providerLabel = if (++fetchCount == 1) "provider-a" else "provider-b"
                BackendResult.Success(listOf(message(peerUid).copy(text = providerLabel)))
            },
            requestScope = this,
        )

        model.load(
            peerUid = 11L,
            myUid = 99L,
            force = true,
            strings = CpStringsZh,
            providerId = "provider-a",
            sourceGeneration = 0,
        )
        yield()
        assertEquals("provider-a", model.state.value.messages.single().text)

        val nextSession = ChatSessionIdentity("provider-b", 1, myUid = 99L, peerUid = 11L)
        assertTrue(
            model.stateFor(nextSession, model.state.value).messages.isEmpty(),
            "the first composition for another provider must not expose the old conversation",
        )

        model.load(
            peerUid = 11L,
            myUid = 99L,
            force = true,
            strings = CpStringsZh,
            providerId = "provider-b",
            sourceGeneration = 1,
        )
        assertTrue(model.state.value.messages.isEmpty(), "provider-local conversation data must clear synchronously")
        yield()
        assertEquals("provider-b", model.state.value.messages.single().text)
    }
}
