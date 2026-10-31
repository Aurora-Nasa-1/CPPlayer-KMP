package cp.player.core.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandardV3ResponseTest {
    @Test
    fun `only successful playback fields are accepted as audio`() {
        for (body in listOf(
            """{"code":500,"data":{"url":"https://auth.example/verify"}}""",
            """{"code":0,"data":{"coverUrl":"https://cdn.example/cover.jpg"}}""",
            """{"code":0,"message":"https://auth.example/verify"}""",
            """{"code":0,"data":[{"code":404,"url":"https://cdn.example/unavailable"}]}""",
            """{"redirectUrl":"http-not-a-url"}""",
        )) assertNull(playbackUrlOf(Json.parseToJsonElement(body)), body)
        for (body in listOf(
            """{"code":0,"data":{"url":"https://cdn.example/audio.flac"}}""",
            """{"code":200,"data":[{"url":"https://cdn.example/audio.flac"}]}""",
            """{"redirectUrl":"https://cdn.example/audio.flac"}""",
        )) assertEquals("https://cdn.example/audio.flac", playbackUrlOf(Json.parseToJsonElement(body)))
    }

    @Test
    fun `expiry accepts explicit milliseconds and legacy relative seconds`() {
        assertEquals(123456L, playbackExpiresAt(Json.parseToJsonElement("""{"data":{"expireAt":123456}}"""), 1000))
        assertEquals(61000L, playbackExpiresAt(Json.parseToJsonElement("""{"data":[{"expi":60}]}"""), 1000))
    }

    @Test
    fun `web login does not confuse request success with authorization`() {
        assertEquals(WebLoginState.PENDING, parseWebLoginState(Json.parseToJsonElement("""{"code":0,"data":{"status":"pending"}}""")))
        assertEquals(WebLoginState.FAILED, parseWebLoginState(Json.parseToJsonElement("""{"code":0,"data":{}}""")))
        assertEquals(WebLoginState.FAILED, parseWebLoginState(Json.parseToJsonElement("""{"code":500,"data":{"status":"authorized"}}""")))
        for ((status, expected) in listOf(
            "authorized" to WebLoginState.AUTHORIZED,
            "expired" to WebLoginState.EXPIRED,
            "denied" to WebLoginState.DENIED,
        )) assertEquals(expected, parseWebLoginState(Json.parseToJsonElement("""{"code":0,"data":{"status":"$status"}}""")))
        assertEquals(WebLoginSession("https://auth.example/login", "opaque-session"),
            parseWebLoginSession(Json.parseToJsonElement("""{"code":0,"data":{"url":"https://auth.example/login","sessionId":"opaque-session"}}""")))
        assertNull(parseWebLoginSession(Json.parseToJsonElement("""{"code":0,"data":{"url":"javascript:alert(1)"}}""")))
    }

    @Test
    fun `opaque account ids are preserved with a numeric compatibility accessor`() {
        val response = Json.parseToJsonElement("""{"code":0,"data":{"profile":{"userId":"user-001"}}}""")
        assertEquals("user-001", extractUserResourceId(response))
        assertNull(extractUidFromLoginStatus(response))
        assertTrue(isLoggedInStatus(response))
        val numeric = Json.parseToJsonElement("""{"code":200,"data":{"account":{"id":"00123"}}}""")
        assertEquals("00123", extractUserResourceId(numeric))
        assertEquals(123L, extractUidFromLoginStatus(numeric))
    }
}
