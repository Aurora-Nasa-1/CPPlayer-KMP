package cp.player.core.lyricsplugin

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 歌词源插件宿主（Lyrico Plugin API 兼容）的端到端测试。
 *
 * 覆盖三件最容易悄悄坏掉的事：
 * 1. Rhino 里 Lyrico 的 `Platform` / `__invoke` 契约真的成立（不是「编译过就算」）；
 * 2. 插件返回的原始 LRC / TTML 能被本仓库的解析器吃下并变成 [cp.player.core.playback.SyncedLyricLine]；
 * 3. 存储 → 启用 → 取词整条链路在真实目录上跑得通。
 *
 * 全部用本地假插件，不联网。
 */
class LyricsPluginTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun writePlugin(root: File, id: String, script: String, manifest: String) {
        val dir = File(root, id).apply { mkdirs() }
        File(dir, "manifest.json").writeText(manifest)
        File(dir, "source.js").writeText(script)
    }

    private fun pluginManifest(id: String) = """
        {
          "id": "$id",
          "name": "Test Source",
          "author": "tests",
          "versionName": "1.0.0",
          "apiVersion": 3,
          "minHostApiVersion": 1,
          "entry": "source.js",
          "capabilities": ["searchSongs", "getLyrics"]
        }
    """.trimIndent()

    // ======================== Rhino 宿主契约 ========================

    @Test
    fun `rhino exposes the lyrico Platform surface and invoke dispatcher`() {
        val runtime = RhinoPluginRuntime(JvmPluginHostApi(pluginId = "t"))
        try {
            runtime.eval(
                """
                globalThis.searchSongs = function (request) {
                  var digest = Platform.crypto.sha256('abc');
                  var locale = Platform.i18n.getLocale();
                  return [{ id: '1', title: request.keyword, artist: 'A', album: 'B', digest: digest, locale: locale }];
                };
                """.trimIndent(),
                "source.js",
            )
            val out = runtime.call("searchSongs", """{"keyword":"hello"}""")
            assertTrue(out.contains("hello"), "插件应拿到 request.keyword，实际：$out")
            assertTrue(out.contains("ba7816bf"), "Platform.crypto.sha256('abc') 应返回正确摘要，实际：$out")
            assertTrue(out.contains("zh-CN"), "Platform.i18n.getLocale 应可用，实际：$out")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `missing entry function returns null instead of throwing`() {
        val runtime = RhinoPluginRuntime(JvmPluginHostApi(pluginId = "t"))
        try {
            runtime.eval("globalThis.onlySongs = function () { return []; };", "source.js")
            assertEquals("null", runtime.call("getLyrics", "{}"))
        } finally {
            runtime.close()
        }
    }

    // ======================== 解析 ========================

    @Test
    fun `raw lrc payload is converted into synced lines`() {
        val parser = LyricsPluginJsonParser()
        val result = parser.parseLyrics("""{"type":"rawPlainLrc","rawPlainLrc":"[00:01.00]hello\n[00:03.50]world"}""")
        assertNotNull(result)
        val lines = LyricsPluginJsonParser.toSyncedLines(result)
        assertEquals(2, lines.size)
        assertEquals("hello", lines[0].text)
        assertEquals(1000L, lines[0].time)
        assertEquals(3500L, lines[1].time)
    }

    @Test
    fun `structured payload maps translation and romanization by nearest time`() {
        val parser = LyricsPluginJsonParser()
        val raw = """
            {
              "type": "structured",
              "original": [[1000, 2000, [[1000, 1500, "你"], [1500, 2000, "好"]]]],
              "translated": [[1000, 2000, "Hello"]],
              "romanization": [[1000, 2000, [[1000, 2000, "ni hao"]]]]
            }
        """.trimIndent()
        val result = parser.parseLyrics(raw)
        assertNotNull(result)
        val lines = LyricsPluginJsonParser.toSyncedLines(result)
        assertEquals(1, lines.size)
        assertEquals("你好", lines[0].text)
        assertEquals("Hello", lines[0].translation)
        assertEquals("ni hao", lines[0].romanization)
        assertEquals(2, lines[0].words.size, "逐字时间戳应保留")
    }

    // ======================== 端到端 ========================

    @Test
    fun `bundled lunabeat plugin loads but stays disabled by default`() {
        val root = tempDir("lyrico-bundled")
        val service = LyricoLyricsPluginService(root, null)
        try {
            val bundled = kotlinx.coroutines.runBlocking { service.listSources() }
                .firstOrNull { it.id == "lunabeat-ttml-hub" }
            assertNotNull(bundled, "内置 LunaBeat 插件应随宿主一起加载")
            assertTrue(bundled.bundled)
            assertFalse(bundled.enabled, "内置插件默认不启用 —— 启用即代表用户同意访问其数据源")
        } finally {
            service.close()
        }
    }

    @Test
    fun `service searches an enabled plugin and returns lyrics`() {
        val root = tempDir("lyrico-store")
        val cache = tempDir("lyrico-cache")
        writePlugin(
            root = root,
            id = "local-test",
            manifest = pluginManifest("local-test"),
            script = """
                globalThis.searchSongs = function (request) {
                  return [{ id: '42', title: request.keyword, artist: 'Singer', album: 'Album', duration: 1000 }];
                };
                globalThis.getLyrics = function (request) {
                  return { type: 'rawPlainLrc', rawPlainLrc: '[00:01.00]first\n[00:02.00]second' };
                };
            """.trimIndent(),
        )

        val service = LyricoLyricsPluginService(root, cache)
        try {
            val imported = kotlinx.coroutines.runBlocking { service.listSources() }
                .firstOrNull { it.id == "local-test" }
            assertNotNull(imported, "导入的插件应被发现")
            assertFalse(imported.enabled, "新导入的插件默认不启用")

            // 未启用 → 不参与检索
            assertEquals(null, kotlinx.coroutines.runBlocking { service.fetchLyrics("hello", "Singer", "Album") })

            kotlinx.coroutines.runBlocking { service.setEnabled("local-test", true) }
            val outcome = kotlinx.coroutines.runBlocking { service.fetchLyrics("hello", "Singer", "Album") }
            assertNotNull(outcome, "启用后应取到歌词")
            assertEquals("local-test", outcome.sourceId)
            assertEquals(2, outcome.lines.size)
            assertEquals("first", outcome.lines[0].text)
        } finally {
            service.close()
        }
    }

    @Test
    fun `unsupported plugin api version is rejected at load time`() {
        val root = tempDir("lyrico-bad")
        writePlugin(
            root = root,
            id = "too-new",
            manifest = pluginManifest("too-new").replace("\"apiVersion\": 3", "\"apiVersion\": 99"),
            script = "globalThis.searchSongs = function () { return []; };",
        )
        val sources = kotlinx.coroutines.runBlocking { LyricoLyricsPluginService(root, null).listSources() }
        assertTrue(
            sources.none { it.id == "too-new" },
            "apiVersion 超出支持区间应被拒绝，而不是静默加载",
        )
    }
}
