package cp.player.core.provider.typed

import cp.player.sdk.HttpRequest
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ScopedProviderRuntimeTest {
    @Test
    fun `allowed SDK origins reject path query and fragment`() {
        for (invalid in listOf(
            "https://music.example.test/path",
            "https://music.example.test/",
            "https://music.example.test?token=x",
            "https://music.example.test#section",
        )) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                ScopedProviderRuntime(setOf(invalid))
            }
        }
    }

    @Test
    fun `implicit and explicit default ports canonicalize to the same origin`() {
        assertEquals(
            parseSdkAllowedOrigin("https://music.example.test"),
            parseSdkAllowedOrigin("https://music.example.test:443"),
        )
        assertEquals(
            parseSdkAllowedOrigin("http://localhost"),
            parseSdkAllowedOrigin("http://localhost:80"),
        )
    }

    @Test
    fun `HTTP response redirects are returned without crossing the allowlisted origin`() = runBlocking {
        RedirectFixture().use { fixture ->
            val runtime = ScopedProviderRuntime(setOf(fixture.origin))
            try {
                val response = runtime.http(HttpRequest("${fixture.origin}/redirect", "GET"))
                assertEquals(302, response.status)
                assertEquals(0, fixture.destinationHits.get())
            } finally {
                runtime.close()
            }
        }
    }

    private class RedirectFixture : AutoCloseable {
        val destinationHits = AtomicInteger()
        private val destinationServer = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                get("/destination") {
                    destinationHits.incrementAndGet()
                    call.respondText("cross-origin destination")
                }
            }
        }.start(wait = false)
        private val destinationPort = runBlocking {
            destinationServer.engine.resolvedConnectors().single().port
        }
        private val redirectServer = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                get("/redirect") {
                    call.respondRedirect("http://127.0.0.1:$destinationPort/destination", permanent = false)
                }
            }
        }.start(wait = false)
        private val redirectPort = runBlocking {
            redirectServer.engine.resolvedConnectors().single().port
        }
        val origin = "http://127.0.0.1:$redirectPort"

        override fun close() {
            redirectServer.stop(0, 1_000)
            destinationServer.stop(0, 1_000)
        }
    }
}
