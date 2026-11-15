package cp.player.app.update

import cp.player.core.provider.BackendProvider
import cp.player.core.provider.ProviderType
import cp.player.core.util.SettingsStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderUpdateManagerTest {

    private class MockSettings : SettingsStorage {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() { map.clear() }
    }

    private class TestProvider(
        override val id: String = "test-provider",
        override val name: String = "Test Provider",
        override val version: String = "1.0.0",
        override val type: ProviderType = ProviderType.SCRIPT,
        override val updateUrl: String? = "https://manifest.example.com/update.json",
        override val targetAppPackage: String? = null,
    ) : BackendProvider

    @Test
    fun `parseUpdateResponse parses standard json correctly`() {
        val manager = ProviderUpdateManager(settings = MockSettings())
        val json = """
            {
                "version": "1.2.0",
                "downloadUrl": "https://example.com/provider-v1.2.0.zip",
                "changelog": "修复部分问题",
                "sha256": "abc12345"
            }
        """.trimIndent()

        val info = manager.parseUpdateResponse(json, "test-provider")
        assertNotNull(info)
        assertEquals("1.2.0", info.version)
        assertEquals("https://example.com/provider-v1.2.0.zip", info.downloadUrl)
        assertEquals("修复部分问题", info.changelog)
        assertEquals("abc12345", info.sha256)
    }

    @Test
    fun `parseUpdateResponse parses github releases format correctly`() {
        val manager = ProviderUpdateManager(settings = MockSettings())
        val json = """
            {
                "tag_name": "v2.1.0",
                "name": "Release v2.1.0",
                "body": "大幅提升音源响应速度",
                "published_at": "2026-11-10T10:00:00Z",
                "assets": [
                    {
                        "name": "provider-source.txt",
                        "browser_download_url": "https://github.com/foo/bar/releases/download/v2.1.0/source.txt"
                    },
                    {
                        "name": "provider-v2.1.0.zip",
                        "browser_download_url": "https://github.com/foo/bar/releases/download/v2.1.0/provider.zip"
                    }
                ]
            }
        """.trimIndent()

        val info = manager.parseUpdateResponse(json, "test-provider")
        assertNotNull(info)
        assertEquals("2.1.0", info.version)
        assertEquals("https://github.com/foo/bar/releases/download/v2.1.0/provider.zip", info.downloadUrl)
        assertEquals("大幅提升音源响应速度", info.changelog)
        assertEquals("2026-11-10T10:00:00Z", info.publishedAt)
    }

    @Test
    fun `parseUpdateResponse supports lenient field naming`() {
        val manager = ProviderUpdateManager(settings = MockSettings())
        val json = """
            {
                "version_name": "v1.5.0",
                "download_url": "https://example.com/dl.zip",
                "notes": "宽松字段测试"
            }
        """.trimIndent()

        val info = manager.parseUpdateResponse(json, "test-provider")
        assertNotNull(info)
        assertEquals("1.5.0", info.version)
        assertEquals("https://example.com/dl.zip", info.downloadUrl)
        assertEquals("宽松字段测试", info.changelog)
    }

    @Test
    fun `parseUpdateResponse parses minimum usable version`() {
        val manager = ProviderUpdateManager(settings = MockSettings())
        val json = """
            {
                "version": "1.4.0",
                "downloadUrl": "https://example.com/provider-v1.4.0.zip",
                "minUsableVersion": "v1.2.0",
                "changelog": "上游接口升级"
            }
        """.trimIndent()

        val info = manager.parseUpdateResponse(json, "test-provider")
        assertNotNull(info)
        assertEquals("1.2.0", info.minUsableVersion)
        assertTrue(info.requiresUpdate("1.1.9"))
        assertTrue(!info.requiresUpdate("1.2.0"))
    }

    @Test
    fun `parseUpdateResponse accepts snake case and unusable-before aliases`() {
        val manager = ProviderUpdateManager(settings = MockSettings())
        val snake = manager.parseUpdateResponse(
            """
                {
                    "version": "2.0.0",
                    "downloadUrl": "https://example.com/dl.zip",
                    "min_usable_version": "1.8.0"
                }
            """.trimIndent(),
            "test-provider",
        )
        val alias = manager.parseUpdateResponse(
            """
                {
                    "version": "2.0.0",
                    "downloadUrl": "https://example.com/dl.zip",
                    "unusableBeforeVersion": "1.9.0"
                }
            """.trimIndent(),
            "test-provider",
        )

        assertEquals("1.8.0", snake?.minUsableVersion)
        assertEquals("1.9.0", alias?.minUsableVersion)
    }

    @Test
    fun `invalid minimum usable version is ignored`() {
        val manager = ProviderUpdateManager(settings = MockSettings())
        val info = manager.parseUpdateResponse(
            """
                {
                    "version": "2.0.0",
                    "downloadUrl": "https://example.com/dl.zip",
                    "minUsableVersion": "soon"
                }
            """.trimIndent(),
            "test-provider",
        )

        assertNotNull(info)
        assertNull(info.minUsableVersion)
        assertTrue(!info.requiresUpdate("1.0.0"))
        assertTrue(
            !ProviderUpdateInfo(
                version = "2.0.0",
                downloadUrl = "https://example.com/dl.zip",
                minUsableVersion = "soon",
            ).requiresUpdate("1.0.0"),
        )
    }

    @Test
    fun `update required takes precedence over normal update state`() {
        val manager = ProviderUpdateManager(settings = MockSettings())
        val info = ProviderUpdateInfo(
            version = "1.4.0",
            downloadUrl = "https://example.com/dl.zip",
            minUsableVersion = "1.2.0",
        )

        assertTrue(manager.evaluateUpdateState("1.1.0", info) is ProviderUpdateState.UpdateRequired)
        assertTrue(manager.evaluateUpdateState("1.2.0", info) is ProviderUpdateState.UpdateAvailable)
        assertTrue(manager.evaluateUpdateState("1.4.0", info) is ProviderUpdateState.UpToDate)
    }

    @Test
    fun `custom update url overrides manifest url`() {
        val settings = MockSettings()
        val manager = ProviderUpdateManager(settings = settings)
        val provider = TestProvider()

        // 默认返回 manifest 的 updateUrl
        assertEquals("https://manifest.example.com/update.json", manager.getEffectiveUpdateUrl(provider))

        // 设置自定义源后返回自定义源
        manager.setCustomUpdateUrl(provider.id, "https://custom.example.com/release.json")
        assertEquals("https://custom.example.com/release.json", manager.getEffectiveUpdateUrl(provider))
        assertEquals("https://custom.example.com/release.json", manager.getCustomUpdateUrl(provider.id))

        // 清空自定义源后回退到 manifest
        manager.setCustomUpdateUrl(provider.id, null)
        assertEquals("https://manifest.example.com/update.json", manager.getEffectiveUpdateUrl(provider))
        assertNull(manager.getCustomUpdateUrl(provider.id))
    }

    @Test
    fun `auto check preference defaults to true and can be toggled`() {
        val settings = MockSettings()
        val manager = ProviderUpdateManager(settings = settings)

        assertTrue(manager.isAutoCheckEnabled())
        manager.setAutoCheckEnabled(false)
        assertEquals(false, manager.isAutoCheckEnabled())
        manager.setAutoCheckEnabled(true)
        assertEquals(true, manager.isAutoCheckEnabled())
    }
}
