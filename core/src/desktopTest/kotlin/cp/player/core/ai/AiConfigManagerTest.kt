package cp.player.core.ai

import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiConfigManagerTest {

    private class InMemorySettingsStorage : SettingsStorage {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() { map.clear() }
    }

    @Test
    fun testMaskedApiKey() {
        val emptyConfig = AiConfig(apiKey = "")
        assertEquals("", emptyConfig.maskedApiKey())

        val shortConfig = AiConfig(apiKey = "1234567")
        assertEquals("****", shortConfig.maskedApiKey())

        val normalConfig = AiConfig(apiKey = "sk-proj-1234567890abcdef")
        val masked = normalConfig.maskedApiKey()
        assertTrue(masked.startsWith("sk-"))
        assertTrue(masked.endsWith("cdef"))
        assertTrue(masked.contains("****"))
        assertFalse(masked.contains("1234567890"))
    }

    @Test
    fun testNormalizedBaseUri() {
        val configWithSlash = AiConfig(baseUri = "https://api.deepseek.com/v1///")
        assertEquals("https://api.deepseek.com/v1", configWithSlash.normalizedBaseUri())

        val configClean = AiConfig(baseUri = "http://localhost:11434/v1")
        assertEquals("http://localhost:11434/v1", configClean.normalizedBaseUri())
    }

    @Test
    fun testPresetModelsIntegrity() {
        for (provider in AiProviderType.entries) {
            assertTrue(provider.presetModels.isNotEmpty(), "Provider ${provider.name} 必须包含预设模型列表")
            assertTrue(provider.presetModels.contains(provider.defaultModel), "Provider ${provider.name} 的预设列表中必须包含 defaultModel")
        }
    }

    @Test
    fun testProviderPresetSwitching() {
        val storage = InMemorySettingsStorage()
        val manager = AiConfigManager(storage = storage)

        manager.selectProviderPreset(AiProviderType.DEEPSEEK)
        val deepseekConfig = manager.currentConfig
        assertEquals(AiProviderType.DEEPSEEK, deepseekConfig.provider)
        assertEquals("https://api.deepseek.com/v1", deepseekConfig.baseUri)
        assertEquals("deepseek-chat", deepseekConfig.model)

        manager.selectProviderPreset(AiProviderType.GEMINI)
        val geminiConfig = manager.currentConfig
        assertEquals(AiProviderType.GEMINI, geminiConfig.provider)
        assertEquals("gemini-1.5-flash", geminiConfig.model)
    }

    @Test
    fun testFetchAvailableModelsFallback() = runTest {
        val storage = InMemorySettingsStorage()
        val manager = AiConfigManager(storage = storage)

        // 未配置真实端点时，获取模型应优雅回退至当前 provider 的 presetModels
        val result = manager.fetchAvailableModels()
        assertTrue(result.isSuccess)
        val models = result.getOrThrow()
        assertTrue(models.isNotEmpty())
        assertEquals(manager.currentConfig.provider.presetModels, models)
    }

    @Test
    fun testGenerationTestWithoutKeyFailsInsteadOfSimulating() = runTest {
        val storage = InMemorySettingsStorage()
        val manager = AiConfigManager(storage = storage)

        val config = AiConfig(
            apiKey = "",
            offlineSimulation = true,
            model = "deepseek-chat",
        )

        val result = manager.runGenerationTest(
            prompt = "夜间沉浸音乐风格测试",
            config = config,
        )

        assertTrue(result.isFailure)
    }
}
