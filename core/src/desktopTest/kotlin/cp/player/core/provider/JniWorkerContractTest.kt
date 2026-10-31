package cp.player.core.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JniWorkerContractTest {
    @Test
    fun `worker preserves nested config types and credential escaping`() {
        val body = """{"id":"001","config":{"serverUrl":"https://nas.example","token":"a\"b\\c","enabled":true}}"""
        val result = dispatchWorkerRequest("song/url", body, { method, json ->
            assertEquals("song/url", method)
            assertEquals(body, json)
            assertIs<JsonObject>(Json.parseToJsonElement(json).jsonObject["config"])
            json
        }, { error("Unexpected audio analysis") })
        assertEquals(body, result)
    }

    @Test
    fun `empty worker requests become empty objects and analysis uses only path`() {
        assertEquals("{}", dispatchWorkerRequest("toplist", "", { _, json -> json }, { error("Unexpected analysis") }))
        assertEquals("C:\\audio.flac", dispatchWorkerRequest("analyzeAudio",
            """{"path":"C:\\audio.flac","config":{"quality":"lossless"}}""",
            { _, _ -> error("Unexpected API call") }, { it }))
    }
}
