package cp.player.core.provider

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class QuickJsSmokeTest {

    @Test
    fun `quickjs evaluates sync expression`() = runBlocking {
        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            val result = quickJs.evaluate<Int>("20 + 22")
            assertEquals(42, result)
        } finally {
            quickJs.close()
        }
    }

    @Test
    fun `quickjs evaluates async promise`() = runBlocking {
        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            val result = quickJs.evaluate<String>(
                """
                async function testAsync() {
                    return Promise.resolve("quickjs_async_success");
                }
                await testAsync();
                """.trimIndent()
            )
            assertEquals("quickjs_async_success", result)
        } finally {
            quickJs.close()
        }
    }

    @Test
    fun `quickjs supports custom native function bindings`() = runBlocking {
        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            quickJs.define("host") {
                function<String, String>("sayHello") { name ->
                    "Hello, $name from Kotlin!"
                }
            }
            val greeting = quickJs.evaluate<String>("host.sayHello('CPPlayer')")
            assertEquals("Hello, CPPlayer from Kotlin!", greeting)
        } finally {
            quickJs.close()
        }
    }
}
