package cp.player.core.provider

import cp.player.core.provider.typed.ManagedProviderRuntime
import cp.player.core.provider.typed.ProviderSdkSessions
import cp.player.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ProviderSdkSessionsLifecycleTest {
    @Test
    fun `retirement listener registration failure closes provider and runtime once`() = runBlocking {
        val providerClosed = AtomicInteger()
        val runtimeClosed = AtomicInteger()
        val registrationFailure = IllegalStateException("listener registration failed")
        val provider = provider(providerClosed) { throw registrationFailure }
        val sessions = sessions(provider, runtimeClosed)

        try {
            val thrown = assertFailsWith<IllegalStateException> { sessions.current() }
            assertEquals(registrationFailure.message, thrown.message)
        } finally {
            sessions.close()
        }

        assertEquals(1, providerClosed.get())
        assertEquals(1, runtimeClosed.get())
    }

    @Test
    fun `session invalidated during registration closes provider and runtime once`() = runBlocking {
        val providerClosed = AtomicInteger()
        val runtimeClosed = AtomicInteger()
        val provider = provider(providerClosed) { listener ->
            listener()
            val unsubscribe: () -> Unit = {}
            unsubscribe
        }
        val sessions = sessions(provider, runtimeClosed)

        try {
            assertFailsWith<CancellationException> { sessions.current() }
        } finally {
            sessions.close()
        }

        assertEquals(1, providerClosed.get())
        assertEquals(1, runtimeClosed.get())
    }

    private fun sessions(provider: BackendProvider, runtimeClosed: AtomicInteger) = ProviderSdkSessions(
        active = { provider },
        cookie = { _, _ -> null },
        credentialVersion = { _, _ -> 0L },
        runtimeFactory = { CountingRuntime(runtimeClosed) },
    )

    private fun provider(
        providerClosed: AtomicInteger,
        registration: (() -> Unit) -> (() -> Unit),
    ): BackendProvider {
        val delegate = SdkBackendProvider(
            id = "lifecycle-test",
            name = "Lifecycle Test",
            version = "1",
            type = ProviderType.JNI,
            factory = {
                object : MusicProvider {
                    override suspend fun close() { providerClosed.incrementAndGet() }
                }
            },
        )
        return object : BackendProvider by delegate {
            override fun onSdkSessionRetired(listener: () -> Unit): () -> Unit = registration(listener)
        }
    }

    private class CountingRuntime(private val closed: AtomicInteger) : ManagedProviderRuntime {
        override suspend fun http(request: HttpRequest): HttpResponse = error("not used")
        override suspend fun sha256(bytes: ByteArray): ByteArray = error("not used")
        override suspend fun read(key: String): ByteArray? = error("not used")
        override suspend fun write(key: String, value: ByteArray?) = error("not used")
        override suspend fun close() { closed.incrementAndGet() }
    }
}
