package cp.player.core.lyricsplugin

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmPluginHostApiCacheTest {
    @Test
    fun `disk-backed cache survives host api recreation`() {
        val cacheDir = Files.createTempDirectory("cpplayer-plugin-cache-").toFile()
        try {
            val writer = JvmPluginHostApi(pluginId = "cache-test", cacheRootDir = cacheDir)
            writer.call("cache.set", """{"key":"lyrics","value":"[00:01.00]cached"}""")

            val reader = JvmPluginHostApi(pluginId = "cache-test", cacheRootDir = cacheDir)
            assertEquals(
                """{"value":"[00:01.00]cached"}""",
                reader.call("cache.get", """{"key":"lyrics"}"""),
            )
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
