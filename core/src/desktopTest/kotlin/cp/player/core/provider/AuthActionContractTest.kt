package cp.player.core.provider

import cp.player.core.provider.typed.ProviderCallContext
import cp.player.core.provider.typed.ProviderHost
import cp.player.core.provider.typed.ProviderSessionKey
import cp.player.sdk.*
import cp.player.sdk.jni.NativeWireCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthActionContractTest {
    @Test
    fun `typed auth request preserves optional country code in KMP values and wire model`() {
        val request = AuthActionRequest(
            action = AuthAction.LOGIN_PHONE,
            phone = "447361501642",
            md5 = false,
            countryCode = "44",
        )

        assertEquals("44", request.values()["countryCode"])
        assertEquals("44", AuthActionRequest.fromValues(request.values()).countryCode)

        val reader = WireReader(WireWriter().also(request::write).finish())
        assertEquals(request, AuthActionRequest.read(reader))
        reader.finish()

        val omitted = AuthActionRequest(action = AuthAction.CAPTCHA_SEND, phone = "13800138000", md5 = false)
        assertEquals(null, omitted.countryCode)
        assertEquals(null, AuthActionRequest.fromValues(omitted.values()).countryCode)
    }

    @Test
    fun `host rejects non digit or out of range auth country codes before provider dispatch`() = runBlocking {
        val provider = RecordingAuthProvider()
        val host = authHost(provider)
        try {
            for (invalid in listOf("", "+44", "12345", "１２")) {
                var rejected = false
                try {
                    host.authenticate(AuthActionRequest(
                        action = AuthAction.LOGIN_PHONE,
                        phone = "447361501642",
                        md5 = false,
                        countryCode = invalid,
                    ))
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                assertTrue(rejected, "Expected $invalid to be rejected")
            }
            assertEquals(0, provider.invocations)
        } finally {
            host.close()
        }
    }

    @Test
    fun `host awaits asynchronous authentication and preserves challenge response`() = runBlocking {
        val provider = RecordingAuthProvider()
        val host = authHost(provider)
        val request = AuthActionRequest(
            action = AuthAction.LOGIN_PHONE,
            phone = "447361501642",
            md5 = false,
            countryCode = "44",
        )
        val challenge = AuthActionResult(
            success = false,
            status = 201,
            verifyUrl = "https://verify.example.test/session",
            challenge = true,
            message = "Complete verification",
        )
        try {
            val pending = async { host.authenticate(request) }
            assertEquals(request, withTimeout(2_000) { provider.received.await() })
            assertFalse(pending.isCompleted)
            provider.response.complete(challenge)
            assertEquals(challenge, withTimeout(2_000) { pending.await() })
            assertEquals(1, provider.invocations)
        } finally {
            if (!provider.response.isCompleted) provider.response.complete(challenge)
            host.close()
        }
    }

    @Test
    fun `native v1 auth encoding retains legacy layout and bridges only explicit country code`() {
        val omitted = AuthActionRequest(action = AuthAction.LOGIN_PHONE, phone = "13800138000", md5 = false)
        val omittedLegacy = readLegacyAuthRequest(
            NativeWireCodec.encodeRequest(ProviderProtocol.AUTHENTICATE, omitted),
        )
        assertEquals(omitted.action, omittedLegacy.action)
        assertEquals(omitted.phone, omittedLegacy.phone)
        assertEquals(null, omittedLegacy.extraJson)

        val explicit = omitted.copy(
            countryCode = "44",
            extraJson = """{"source":"existing","countryCode":"86"}""",
        )
        val explicitLegacy = readLegacyAuthRequest(
            NativeWireCodec.encodeRequest(ProviderProtocol.AUTHENTICATE, explicit),
        )
        val extra = assertNotNull(explicitLegacy.extraJson)
        val parsed = Json.parseToJsonElement(extra).jsonObject
        assertEquals("existing", parsed["source"]?.jsonPrimitive?.content)
        assertEquals("44", parsed["countryCode"]?.jsonPrimitive?.content)
    }

    private fun authHost(provider: RecordingAuthProvider) = ProviderHost(
        ProviderCallContext(ProviderSessionKey("auth-contract", "account", 0)),
        provider,
    )

    private class RecordingAuthProvider : MusicProvider {
        val received = CompletableDeferred<AuthActionRequest>()
        val response = CompletableDeferred<AuthActionResult>()
        var invocations = 0

        override suspend fun capabilities() = Capabilities(listOf(Capability.AUTH_ACTIONS))

        override suspend fun authenticate(request: AuthActionRequest): AuthActionResult {
            invocations += 1
            received.complete(request)
            return response.await()
        }
    }

    private data class LegacyAuthRequest(
        val action: String,
        val phone: String?,
        val extraJson: String?,
    )

    /** Decode exactly the pre-countryCode Native v1 positional request shape. */
    private fun readLegacyAuthRequest(bytes: ByteArray): LegacyAuthRequest {
        val reader = WireReader(bytes)
        val action = reader.string()
        reader.optional { reader.string() } // email
        val phone = reader.optional { reader.string() }
        reader.optional { reader.string() } // password
        reader.optional { reader.string() } // captcha
        reader.bool() // md5
        reader.optional { reader.string() } // imageCaptcha
        reader.optional { reader.string() } // challengeCookie
        reader.optional { reader.string() } // key
        reader.optional { reader.string() } // sessionId
        reader.optional { reader.string() } // verifyId
        val extraJson = reader.optional { reader.string() }
        reader.finish()
        return LegacyAuthRequest(action, phone, extraJson)
    }
}
