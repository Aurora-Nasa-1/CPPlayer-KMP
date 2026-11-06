package cp.player.core.provider

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class AudioQualityResolverTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun testDefaultBehaviorWhenOptionsNullOrEmpty() {
        assertEquals("hires", AudioQualityResolver.resolveKey(null, "hires"))
        assertEquals("standard", AudioQualityResolver.resolveKey(emptyList(), "standard"))

        assertEquals("hires", AudioQualityResolver.resolveTargetQuality(null, "hires"))
        assertNull(AudioQualityResolver.resolveOption(null, "hires"))
    }

    @Test
    fun testExactMatching() {
        val options = listOf(
            ProviderQualityOption(key = "128k", targetQuality = "standard", label = "标准 128K"),
            ProviderQualityOption(key = "320k", targetQuality = "exhigh", label = "极高 320K"),
            ProviderQualityOption(key = "flac", targetQuality = "lossless", label = "无损 FLAC"),
            ProviderQualityOption(key = "hi-res", targetQuality = "hires", label = "Hi-Res 无损", format = "flac"),
        )

        assertEquals("hi-res", AudioQualityResolver.resolveKey(options, "hires"))
        assertEquals("flac", AudioQualityResolver.resolveKey(options, "lossless"))
        assertEquals("320k", AudioQualityResolver.resolveKey(options, "exhigh"))
        assertEquals("128k", AudioQualityResolver.resolveKey(options, "standard"))

        // 反查
        assertEquals("hires", AudioQualityResolver.resolveTargetQuality(options, "hi-res"))
        val option = AudioQualityResolver.resolveOption(options, "hi-res")
        assertNotNull(option)
        assertEquals("Hi-Res 无损", option.label)
        assertEquals("flac", option.format)
    }

    @Test
    fun testLadderFallbackWhenQualityNotSupported() {
        // 音源最高只支持到 lossless (flac)，不支持 hires
        val options = listOf(
            ProviderQualityOption(key = "128k", targetQuality = "standard"),
            ProviderQualityOption(key = "320k", targetQuality = "exhigh"),
            ProviderQualityOption(key = "flac", targetQuality = "lossless"),
        )

        // 请求 hires 时，按阶梯就近降级到 lossless (flac)
        assertEquals("flac", AudioQualityResolver.resolveKey(options, "hires"))
        // 请求 jymaster 时，同样降级到最高支持的 lossless (flac)
        assertEquals("flac", AudioQualityResolver.resolveKey(options, "jymaster"))
        // 请求 higher (192k) 时，阶梯降级到 <= higher 的最高档位，即 standard (128k)
        assertEquals("128k", AudioQualityResolver.resolveKey(options, "higher"))
    }

    @Test
    fun testCrossProviderSwitchingKeepsPreference() {
        // 模拟用户偏好为 hires
        val userPreference = "hires"

        // 音源 A (如网易定制源): "hi-res" -> "hires"
        val providerAOptions = listOf(
            ProviderQualityOption(key = "standard", targetQuality = "standard"),
            ProviderQualityOption(key = "exhigh", targetQuality = "exhigh"),
            ProviderQualityOption(key = "lossless", targetQuality = "lossless"),
            ProviderQualityOption(key = "hi-res", targetQuality = "hires", label = "Hi-Res 臻品"),
        )

        // 音源 B (如自建母带源): "master" -> "hires"
        val providerBOptions = listOf(
            ProviderQualityOption(key = "128k", targetQuality = "standard"),
            ProviderQualityOption(key = "flac", targetQuality = "lossless"),
            ProviderQualityOption(key = "master", targetQuality = "hires", label = "超清母带"),
        )

        // 音源 C (无 hires 源，仅支持无损与标准):
        val providerCOptions = listOf(
            ProviderQualityOption(key = "lq", targetQuality = "standard"),
            ProviderQualityOption(key = "sq", targetQuality = "lossless", label = "超品质无损"),
        )

        // 1. 在 Provider A 上：解析为 "hi-res"
        val keyA = AudioQualityResolver.resolveKey(providerAOptions, userPreference)
        assertEquals("hi-res", keyA)
        val optA = AudioQualityResolver.resolveOption(providerAOptions, keyA)
        assertEquals("Hi-Res 臻品", optA?.label)

        // 2. 切换至 Provider B：保持用户的 userPreference = "hires"，寻找 B 的映射
        val keyB = AudioQualityResolver.resolveKey(providerBOptions, userPreference)
        assertEquals("master", keyB)
        val optB = AudioQualityResolver.resolveOption(providerBOptions, keyB)
        assertEquals("超清母带", optB?.label)

        // 3. 切换至 Provider C：保持用户的 userPreference = "hires"，C 不支持 hires，就近降级为 "sq" (lossless)
        val keyC = AudioQualityResolver.resolveKey(providerCOptions, userPreference)
        assertEquals("sq", keyC)
        val optC = AudioQualityResolver.resolveOption(providerCOptions, keyC)
        assertEquals("超品质无损", optC?.label)

        // 4. 再次切回 Provider A：用户的 userPreference 依然是 "hires"（未被降级篡改），立即重新解析回 "hi-res"
        val backToKeyA = AudioQualityResolver.resolveKey(providerAOptions, userPreference)
        assertEquals("hi-res", backToKeyA)
    }

    @Test
    fun testManifestSerializationWithQualityOptions() {
        val manifestJson = """
            {
              "id": "demo-quality-source",
              "name": "高音质测试源",
              "version": "1.0.0",
              "type": "http",
              "entryPoint": "http://127.0.0.1:3000",
              "apiVersion": 3,
              "contract": "standard",
              "qualityOptions": [
                {
                  "key": "standard",
                  "targetQuality": "standard",
                  "label": "标准 128K",
                  "bitrate": 128000
                },
                {
                  "key": "320k",
                  "targetQuality": "exhigh",
                  "label": "极高 320K",
                  "bitrate": 320000
                },
                {
                  "key": "flac",
                  "targetQuality": "lossless",
                  "label": "无损 FLAC",
                  "format": "flac"
                },
                {
                  "key": "hi-res",
                  "targetQuality": "hires",
                  "label": "Hi-Res 无损",
                  "format": "flac"
                }
              ]
            }
        """.trimIndent()

        val manifest = json.decodeFromString(ModuleManifest.serializer(), manifestJson)
        val options = manifest.qualityOptions
        assertNotNull(options)
        assertEquals(4, options.size)
        assertEquals("hi-res", options[3].key)
        assertEquals("hires", options[3].targetQuality)
        assertEquals("Hi-Res 无损", options[3].label)
        assertEquals("flac", options[3].format)

        // 验证 round-trip
        val encoded = json.encodeToString(ModuleManifest.serializer(), manifest)
        val decoded = json.decodeFromString(ModuleManifest.serializer(), encoded)
        assertEquals(options, decoded.qualityOptions)
    }
}
