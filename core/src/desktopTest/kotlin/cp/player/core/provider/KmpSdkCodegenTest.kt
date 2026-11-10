package cp.player.core.provider

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KmpSdkCodegenTest {
    @Test
    fun `Kotlin JNI and TypeScript declarations match one schema`() {
        val process = ProcessBuilder("node", "provider-contract/generate.mjs", "--check")
            .directory(File(System.getProperty("cp.typed.repo", "..")))
            .redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Contract generator timeout")
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
        } finally { process.destroyForcibly() }
    }
}
