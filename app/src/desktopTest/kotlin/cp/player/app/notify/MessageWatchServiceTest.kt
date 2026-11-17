package cp.player.app.notify

import cp.player.core.model.Contact
import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageWatchServiceTest {
    private class MapSettings : SettingsStorage {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = values[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
        override fun remove(key: String) { values.remove(key) }
        override fun contains(key: String): Boolean = key in values
        override fun clear() { values.clear() }
    }

    private fun readyPrefs(): MessageNotifyPrefs = MessageNotifyPrefs(MapSettings()).also {
        it.setMasterEnabled(true)
        it.setSubscribed("provider", 17L, true)
    }

    private val contact = Contact(
        userId = 17L,
        nickname = "peer",
        avatarUrl = "",
        lastMessage = "hello",
        lastMessageTime = 1_000L,
        unreadCount = 1,
    )

    @Test
    fun `overlapping ticks serialize fetching and post one notification`() = runBlocking {
        val prefs = readyPrefs()
        val activeFetches = AtomicInteger()
        val peakFetches = AtomicInteger()
        val fetchCalls = AtomicInteger()
        val firstFetchStarted = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        val notifications = mutableListOf<MessageNotification>()
        val service = MessageWatchService(
            prefs = prefs,
            providerId = { "provider" },
            isReady = { true },
            fetchContacts = {
                val active = activeFetches.incrementAndGet()
                peakFetches.updateAndGet { peak -> maxOf(peak, active) }
                try {
                    if (fetchCalls.incrementAndGet() == 1) {
                        firstFetchStarted.complete(Unit)
                        releaseFirstFetch.await()
                    }
                    listOf(contact)
                } finally {
                    activeFetches.decrementAndGet()
                }
            },
            post = { notifications += it },
            now = { 1_001L },
        )

        val first = async(Dispatchers.Default) { service.runOnce() }
        withTimeout(2_000L) { firstFetchStarted.await() }
        // UNDISPATCHED runs until runOnceMutex suspends, so the second request is definitely queued.
        val second = async(start = CoroutineStart.UNDISPATCHED) { service.runOnce() }
        assertEquals(1, peakFetches.get(), "second tick must not enter fetch while first is held")
        releaseFirstFetch.complete(Unit)
        val results = awaitAll(first, second)

        assertEquals(1, peakFetches.get(), "轮询请求不得重叠")
        assertEquals(1, results.sum(), "同一条新消息只能触发一次通知")
        assertEquals(1, notifications.size)
        assertEquals(1_000L, prefs.cursor("provider", 17L))
    }

    @Test
    fun `stop invalidates a tick whose request is still in flight`() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val notifications = mutableListOf<MessageNotification>()
        val service = MessageWatchService(
            prefs = readyPrefs(),
            providerId = { "provider" },
            isReady = { true },
            fetchContacts = {
                requestStarted.complete(Unit)
                releaseFetch.await()
                listOf(contact)
            },
            post = { notifications += it },
            now = { 1_001L },
        )

        val pending = async(Dispatchers.Default) { service.runOnce() }
        withTimeout(2_000L) { requestStarted.await() }
        service.stop()
        releaseFetch.complete(Unit)

        assertEquals(0, pending.await())
        assertTrue(notifications.isEmpty(), "停用后不应发送迟到的通知")
    }

    @Test
    fun `concurrent starts create one polling job`() = runBlocking {
        val fetchCount = AtomicInteger()
        val firstFetch = CompletableDeferred<Unit>()
        val service = MessageWatchService(
            prefs = readyPrefs(),
            providerId = { "provider" },
            isReady = { true },
            fetchContacts = {
                if (fetchCount.incrementAndGet() == 1) firstFetch.complete(Unit)
                emptyList()
            },
            post = {},
            intervalMs = Long.MAX_VALUE,
        )
        val pollingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            awaitAll(*(1..8).map { async(Dispatchers.Default) { service.start(pollingScope) } }.toTypedArray())
            awaitAll(*(1..8).map { async(Dispatchers.Default) { service.sync(pollingScope) } }.toTypedArray())
            withTimeout(2_000L) { firstFetch.await() }
            assertTrue(service.isRunning)
            assertEquals(1, fetchCount.get(), "并发 start 不得创建多个轮询循环")

            service.stop()
            assertFalse(service.isRunning)
        } finally {
            service.stop()
            pollingScope.cancel()
        }
    }

    @Test
    fun `stop waits for an active notification delivery`() = runBlocking {
        val postEntered = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        val notifications = mutableListOf<MessageNotification>()
        val service = MessageWatchService(
            prefs = readyPrefs(),
            providerId = { "provider" },
            isReady = { true },
            fetchContacts = { listOf(contact) },
            post = {
                postEntered.countDown()
                check(releasePost.await(2, TimeUnit.SECONDS)) { "test did not release the post callback" }
                notifications += it
            },
            now = { 1_001L },
        )

        val pending = async(Dispatchers.Default) { service.runOnce() }
        assertTrue(postEntered.await(2, TimeUnit.SECONDS), "notification callback did not start")

        val stopStarted = CompletableDeferred<Unit>()
        val stopReturned = CompletableDeferred<Unit>()
        val stopThread = AtomicReference<Thread>()
        val stopping = async(Dispatchers.Default) {
            stopThread.set(Thread.currentThread())
            stopStarted.complete(Unit)
            service.stop()
            stopReturned.complete(Unit)
        }
        withTimeout(2_000L) { stopStarted.await() }
        withTimeout(2_000L) {
            while (!stopReturned.isCompleted && stopThread.get()?.state != Thread.State.BLOCKED) delay(1)
        }
        assertFalse(stopReturned.isCompleted, "stop must not return while an old post is still running")

        releasePost.countDown()
        stopping.await()
        assertEquals(1, pending.await())
        assertEquals(1, notifications.size)
        assertTrue(stopReturned.isCompleted)
    }

    @Test
    fun `post cancellation propagates and does not advance the cursor`() = runBlocking {
        val prefs = readyPrefs()
        val service = MessageWatchService(
            prefs = prefs,
            providerId = { "provider" },
            isReady = { true },
            fetchContacts = { listOf(contact) },
            post = { throw CancellationException("posting cancelled") },
            now = { 1_001L },
        )

        assertFailsWith<CancellationException> { service.runOnce() }
        assertEquals(null, prefs.cursor("provider", 17L))
    }

    @Test
    fun `master disabled during fetch prevents a late notification`() = runBlocking {
        val prefs = readyPrefs()
        val requestStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<List<Contact>>()
        val notifications = mutableListOf<MessageNotification>()
        val service = MessageWatchService(
            prefs = prefs,
            providerId = { "provider" },
            isReady = { true },
            fetchContacts = {
                requestStarted.complete(Unit)
                releaseFetch.await()
            },
            post = { notifications += it },
            now = { 1_001L },
        )

        val pending = async(Dispatchers.Default) { service.runOnce() }
        withTimeout(2_000L) { requestStarted.await() }
        service.setMasterEnabled(false)
        releaseFetch.complete(listOf(contact))

        assertEquals(0, pending.await())
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `unsubscribe during fetch prevents a late notification`() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<List<Contact>>()
        val notifications = mutableListOf<MessageNotification>()
        val service = MessageWatchService(
            prefs = readyPrefs(),
            providerId = { "provider" },
            isReady = { true },
            fetchContacts = {
                requestStarted.complete(Unit)
                releaseFetch.await()
            },
            post = { notifications += it },
            now = { 1_001L },
        )

        val pending = async(Dispatchers.Default) { service.runOnce() }
        withTimeout(2_000L) { requestStarted.await() }
        service.setSubscription("provider", 17L, enabled = false)
        releaseFetch.complete(listOf(contact))

        assertEquals(0, pending.await())
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `master toggle waits for an active post callback`() = runBlocking {
        val prefs = readyPrefs()
        val postEntered = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        val service = MessageWatchService(
            prefs = prefs,
            providerId = { "provider" },
            isReady = { true },
            fetchContacts = { listOf(contact) },
            post = {
                postEntered.countDown()
                check(releasePost.await(2, TimeUnit.SECONDS)) { "test did not release the post callback" }
            },
            now = { 1_001L },
        )

        val pending = async(Dispatchers.Default) { service.runOnce() }
        assertTrue(postEntered.await(2, TimeUnit.SECONDS), "notification callback did not start")
        val setterStarted = CompletableDeferred<Unit>()
        val setterReturned = CompletableDeferred<Unit>()
        val setterThread = AtomicReference<Thread>()
        val setting = async(Dispatchers.Default) {
            setterThread.set(Thread.currentThread())
            setterStarted.complete(Unit)
            service.setMasterEnabled(false)
            setterReturned.complete(Unit)
        }
        withTimeout(2_000L) { setterStarted.await() }
        withTimeout(2_000L) {
            while (!setterReturned.isCompleted && setterThread.get()?.state != Thread.State.BLOCKED) delay(1)
        }
        assertFalse(setterReturned.isCompleted, "the preference update must serialize behind the active post")

        releasePost.countDown()
        setting.await()
        assertEquals(1, pending.await())
        assertFalse(prefs.isMasterEnabled())
    }
}
