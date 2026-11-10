package cp.player.core.provider

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.JsObject
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the resolved engine, not the legacy provider's JSON dispatcher. */
class TypedQuickJsProbeTest {
    @Test
    fun `values promises bytes errors and interruption cross the actual binding`() {
        Executors.newSingleThreadExecutor { Thread(it, "typed-js-probe") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    val js = QuickJs.create(dispatcher)
                    try {
                        js.memoryLimit = 16 * 1024 * 1024
                        js.evaluationTimeoutMillis = 200
                        js.function<JsObject>("input") {
                            JsObject(mapOf("id" to "001", "items" to arrayOf("A", "a")))
                        }
                        js.asyncFunction<ByteArray>("bytes") {
                            withContext(Dispatchers.IO) { byteArrayOf(0, 127, -1) }
                        }
                        // Replacing both functions proves that this result path cannot use JSON.
                        val value = js.evaluate<JsObject>(
                            """
                            JSON.parse = JSON.stringify = () => { throw Error("JSON bridge forbidden"); };
                            const request = input();
                            const binary = await bytes();
                            ({ id: request.id, items: request.items, byte: binary[2],
                               nested: { available: true }, onOwner: true });
                            """.trimIndent(),
                        )
                        assertEquals("001", value["id"])
                        // ByteArray is Int8Array, not Uint8Array, in this binding.
                        assertEquals(-1, (value["byte"] as Number).toInt())
                        assertEquals(true, (value["nested"] as JsObject)["available"])
                        assertEquals(listOf("A", "a"), cp.player.sdk.valueList(value["items"]))
                        assertFailsWith<QuickJsException> {
                            js.evaluate<Any?>("await Promise.reject(new Error('probe rejection'));")
                        }
                        assertFailsWith<QuickJsInterruptedException> {
                            withTimeout(5_000) { js.evaluate<Any?>("while (true) {}") }
                        }
                        assertEquals(42, js.evaluate<Int>("6 * 7"))
                    } finally {
                        js.close()
                        js.close()
                    }
                }
            }
    }
}
