package cp.player.core.lyrics

import cp.player.core.api.AmllTtmlClient
import cp.player.core.api.LyricsSourceMode
import cp.player.core.api.MusicApiService
import cp.player.core.lyricsplugin.LyricsPluginService
import cp.player.core.lyricsplugin.LyricsPluginSourceInfo
import cp.player.core.lyricsplugin.PluginCapability
import cp.player.core.lyricsplugin.PluginLyricsOutcome
import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 来源注册表的迁移与排序守卫测试。
 *
 * 重点钉死**老用户升级后的行为不变**：旧三档模式各自换算出的顺序必须与改造前的实际
 * 取词顺序一致，否则升级当天用户就会看到歌词来源变了。
 */
class LyricsSourceRegistryTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File =
        Files.createTempDirectory("lyrics-sources-test").toFile().also { tempDirs += it }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    /** 内存版 SettingsStorage —— 只为塞旧键。 */
    private class MemorySettings(private val map: MutableMap<String, String> = mutableMapOf()) : SettingsStorage {
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String) = key in map
        override fun clear() = map.clear()
    }

    private class FakePlugins(
        private val entries: List<LyricsPluginSourceInfo> = emptyList(),
    ) : LyricsPluginService {
        override suspend fun listSources() = entries
        override fun listSourcesSync() = entries
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun importPluginZip(zipPath: String) = emptyList<String>()
        override suspend fun deletePlugin(id: String) = false
        override suspend fun pluginConfig(id: String) = emptyMap<String, String>()
        override suspend fun setPluginConfigValue(id: String, key: String, value: String) = Unit
        @Suppress("DEPRECATION")
        override suspend fun fetchLyrics(title: String, artist: String, album: String, durationMs: Long): PluginLyricsOutcome? = null
        override suspend fun fetchLyricsFrom(
            id: String, title: String, artist: String, album: String, durationMs: Long,
        ): PluginLyricsOutcome? = null
        override fun close() = Unit
    }

    private fun plugin(id: String, enabled: Boolean = true) = LyricsPluginSourceInfo(
        id = id, name = id, author = "", version = "1.0", description = "",
        capabilities = setOf(PluginCapability.GET_LYRICS), enabled = enabled, bundled = false,
    )

    private fun registry(
        root: File,
        mode: LyricsSourceMode? = null,
        plugins: List<LyricsPluginSourceInfo> = emptyList(),
        withAmll: Boolean = true,
    ): FileLyricsSourceRegistry {
        val settings = MemorySettings().apply {
            mode?.let { putString(LyricsSourceMode.SETTINGS_KEY, it.key) }
        }
        return FileLyricsSourceRegistry(
            rootDir = root,
            plugins = FakePlugins(plugins),            amllClient = if (withAmll) FakeAmll else null,
            api = FakeApi,
            legacySettings = settings,
        )
    }

    @Test
    fun `amll_first migrates to sidecar amll provider`() = runBlocking {
        val r = registry(tempDir(), mode = LyricsSourceMode.AMLL_FIRST)
        assertEquals(
            listOf(
                BuiltinLyricsSourceIds.SIDECAR,
                BuiltinLyricsSourceIds.AMLL,
                BuiltinLyricsSourceIds.PROVIDER,
            ),
            r.allSources().map { it.id },
        )
        // 默认档下三条来源全部启用。
        assertTrue(r.allSources().all { it.enabled })
    }

    @Test
    fun `provider_only disables amll`() = runBlocking {
        val r = registry(tempDir(), mode = LyricsSourceMode.PROVIDER_ONLY)
        val byId = r.allSources().associateBy { it.id }
        // order 是全量的（含停用的 AMLL）—— 停用只体现在 enabled 上，
        // 否则用户再启用 AMLL 时会被排到最末尾，位置信息永久丢失。
        assertEquals(
            BuiltinLyricsSourceIds.DEFAULT_ORDER,
            r.allSources().map { it.id },
        )
        assertEquals(false, byId[BuiltinLyricsSourceIds.AMLL]!!.enabled)
        assertEquals(
            listOf(BuiltinLyricsSourceIds.SIDECAR, BuiltinLyricsSourceIds.PROVIDER),
            r.orderedSources().map { it.id },
            "取词顺序里不该出现被停用的 AMLL",
        )
    }

    @Test
    fun `amll_only disables provider but keeps plugins`() = runBlocking {
        val r = registry(
            tempDir(),
            mode = LyricsSourceMode.AMLL_ONLY,
            plugins = listOf(plugin("my-plugin")),
        )
        val byId = r.allSources().associateBy { it.id }
        assertEquals(false, byId[BuiltinLyricsSourceIds.PROVIDER]!!.enabled)
        // 改造前 AMLL_ONLY 也允许插件兜底 —— 迁移后插件必须仍然启用。
        assertEquals(true, byId["my-plugin"]!!.enabled)
        assertEquals(true, byId[BuiltinLyricsSourceIds.AMLL]!!.enabled)
    }

    @Test
    fun `plugins are appended after builtins on migration`() = runBlocking {
        val r = registry(
            tempDir(),
            mode = LyricsSourceMode.AMLL_FIRST,
            plugins = listOf(plugin("p1"), plugin("p2")),
        )
        assertEquals(
            listOf(
                BuiltinLyricsSourceIds.SIDECAR,
                BuiltinLyricsSourceIds.AMLL,
                BuiltinLyricsSourceIds.PROVIDER,
                "p1",
                "p2",
            ),
            r.allSources().map { it.id },
        )
    }

    @Test
    fun `no legacy key defaults to amll_first`() = runBlocking {
        val r = registry(tempDir(), mode = null)
        assertEquals(
            BuiltinLyricsSourceIds.DEFAULT_ORDER,
            r.allSources().map { it.id },
        )
    }

    @Test
    fun `migration writes sources_json once and does not rewrite it`() = runBlocking {
        val dir = tempDir()
        val r = registry(dir, mode = LyricsSourceMode.PROVIDER_ONLY)
        val stateFile = File(dir, "sources.json")
        assertTrue(stateFile.isFile, "迁移必须落盘")
        val firstWrite = stateFile.readText()

        // 用户改了排序 → 重新构造（模拟重启）不该被迁移覆盖。
        r.setOrder(listOf(BuiltinLyricsSourceIds.PROVIDER, BuiltinLyricsSourceIds.SIDECAR))
        val r2 = registry(dir, mode = LyricsSourceMode.AMLL_FIRST)
        assertEquals(
            listOf(
                BuiltinLyricsSourceIds.PROVIDER,
                BuiltinLyricsSourceIds.SIDECAR,
                BuiltinLyricsSourceIds.AMLL,
            ),
            r2.allSources().map { it.id },
            "已有 sources.json 时不得再次迁移（AMLL 未被列入，追加在末尾）",
        )
        assertTrue(firstWrite.isNotBlank())
    }

    @Test
    fun `new plugin not present in order still appears`() = runBlocking {
        val dir = tempDir()
        val r = registry(dir, mode = LyricsSourceMode.AMLL_FIRST)
        // 用户先定好顺序，之后才导入插件。
        r.setOrder(listOf(BuiltinLyricsSourceIds.AMLL, BuiltinLyricsSourceIds.SIDECAR))

        val r2 = registry(dir, plugins = listOf(plugin("late")))
        val ids = r2.allSources().map { it.id }
        assertTrue("late" in ids, "新导入的插件必须出现，否则用户导入后看不到任何变化")
        assertEquals("late", ids.last(), "未排序的新来源追加在末尾")
    }

    @Test
    fun `setEnabled only affects that source`() = runBlocking {
        val r = registry(tempDir(), mode = LyricsSourceMode.AMLL_FIRST)
        r.setEnabled(BuiltinLyricsSourceIds.AMLL, false)
        assertEquals(
            listOf(BuiltinLyricsSourceIds.SIDECAR, BuiltinLyricsSourceIds.PROVIDER),
            r.orderedSources().map { it.id },
        )
        r.setEnabled(BuiltinLyricsSourceIds.AMLL, true)
        assertTrue(BuiltinLyricsSourceIds.AMLL in r.orderedSources().map { it.id })
    }

    @Test
    fun `builtin sources are never removable`() = runBlocking {
        val r = registry(tempDir(), mode = LyricsSourceMode.AMLL_FIRST)
        val builtins = r.allSources().filter { it.bundled }
        assertEquals(3, builtins.size)
        assertTrue(builtins.none { it.removable }, "内置来源不可删除")
    }

    @Test
    fun `unknown ids in setOrder are dropped`() = runBlocking {
        val r = registry(tempDir(), mode = LyricsSourceMode.AMLL_FIRST)
        r.setOrder(listOf("nonexistent", BuiltinLyricsSourceIds.PROVIDER))
        assertEquals(
            listOf(BuiltinLyricsSourceIds.PROVIDER, BuiltinLyricsSourceIds.SIDECAR, BuiltinLyricsSourceIds.AMLL),
            r.allSources().map { it.id },
        )
    }

    @Test
    fun `without amll client the amll source is absent`() = runBlocking {
        val r = registry(tempDir(), mode = LyricsSourceMode.AMLL_FIRST, withAmll = false)
        assertEquals(
            listOf(BuiltinLyricsSourceIds.SIDECAR, BuiltinLyricsSourceIds.PROVIDER),
            r.allSources().map { it.id },
        )
    }

    private companion object {
        /** 迁移与排序都不需要真正联网，用最小可构造实例。 */
        val FakeAmll: AmllTtmlClient = AmllTtmlClient(diskCache = null)

        /** 只有 `getLyric` 会被真正调用；其余方法调用即抛，防止测试间互相污染。 */
        val FakeApi: MusicApiService = java.lang.reflect.Proxy.newProxyInstance(
            MusicApiService::class.java.classLoader,
            arrayOf(MusicApiService::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("stub api: ${method.name}")
        } as MusicApiService
    }
}
