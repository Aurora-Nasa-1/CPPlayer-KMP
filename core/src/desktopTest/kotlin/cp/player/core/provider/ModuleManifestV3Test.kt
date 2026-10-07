package cp.player.core.provider

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * manifest v3（`apiVersion` / `minHostApiVersion` / `capabilities`）的守卫测试。
 *
 * 钉住的都是**写错后不会有任何编译/运行表象**的语义：
 *
 * 1. **缺省必须是 1，不是当前宿主版本。** 默认写反（`apiVersion ?: HOST_PROVIDER_API_VERSION`）
 *    会让所有历史包被当成 v3 —— 编译过、单测绿，只在真装老包时行为错。
 * 2. **`apiVersion` 高于宿主不算错**（前向兼容）；判据只能是 `minHostApiVersion`。
 *    两条判据混用会出现「装不了本该能装的包」。
 * 3. **未识别的能力名必须忽略而不是抛错** —— manifest 是外部输入，
 *    宿主比音源旧是常态，抛错等于让用户装不上包。
 * 4. **缺省字段不能被序列化写回。** 打包往返一次凭空多出 `"apiVersion": 1`
 *    会让 zip 字节与作者的原始包不一致，`sha256` 校验随后莫名其妙地失败。
 */
class ModuleManifestV3Test {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun parse(text: String) = json.decodeFromString(ModuleManifest.serializer(), text)

    /** 一个「什么都没声明」的历史包 —— v3 之前所有音源都长这样。 */
    private val legacyJson = """
        {
          "id": "legacy", "name": "Legacy", "version": "1.0.0",
          "type": "http", "entryPoint": "http://127.0.0.1:3000"
        }
    """.trimIndent()

    @Test
    fun legacyPackageDefaultResolvesToV1() {
        val m = parse(legacyJson)
        assertNull(m.apiVersion, "缺省时字段本身应仍是 null，而不是被填上默认值")
        assertEquals(1, m.resolvedApiVersion)
        assertEquals(1, m.resolvedMinHostApiVersion)
        assertTrue(m.isLoadable, "老包必须永远可加载")
    }

    @Test
    fun missingDefaultsAreNotWrittenBack() {
        val m = parse(legacyJson)
        val roundTripped = json.encodeToString(ModuleManifest.serializer(), m)
        assertFalse(
            roundTripped.contains("apiVersion"),
            "缺省字段被序列化写回了，往返一次包就不再逐字节一致（sha256 会失败）：$roundTripped",
        )
        assertFalse(roundTripped.contains("capabilities"), roundTripped)
        assertFalse(roundTripped.contains("minHostApiVersion"), roundTripped)
    }

    @Test
    fun higherSelfApiVersionStillLoadable() {
        // 音源自己实现到 API 5、只要求宿主 API 1 —— 这正是前向兼容的标准写法。
        val m = parse(
            """
            {
              "id": "future", "name": "Future", "version": "2.0.0",
              "type": "http", "entryPoint": "http://127.0.0.1:3000",
              "apiVersion": 5, "minHostApiVersion": 1
            }
            """.trimIndent(),
        )
        assertEquals(5, m.resolvedApiVersion)
        assertTrue(m.isLoadable, "「比宿主新」本身不能成为拒绝理由")
    }

    @Test
    fun minHostApiVersionAboveHostIsRejected() {
        val m = parse(
            """
            {
              "id": "tooNew", "name": "Too New", "version": "3.0.0",
              "type": "http", "entryPoint": "http://127.0.0.1:3000",
              "apiVersion": 9, "minHostApiVersion": $HOST_PROVIDER_API_VERSION_PLUS_ONE
            }
            """.trimIndent(),
        )
        assertFalse(m.isLoadable, "包要求更高宿主却没被拦下，会以旧代码执行新契约")
    }

    @Test
    fun unknownCapabilitiesAreIgnoredNotRejected() {
        val m = parse(
            """
            {
              "id": "caps", "name": "Caps", "version": "1.0.0",
              "type": "http", "entryPoint": "http://127.0.0.1:3000",
              "capabilities": ["qrLogin", "someFutureCapability", "comments"]
            }
            """.trimIndent(),
        )
        // 解析不能抛错；未识别的那个安静丢掉。
        assertEquals(listOf(ProviderCapability.QR_LOGIN, ProviderCapability.COMMENTS), m.knownCapabilities)
        assertTrue(m.supports(ProviderCapability.QR_LOGIN))
        assertFalse(m.supports(ProviderCapability.CLOUD_DRIVE), "未声明的能力不能被当成支持")
    }

    @Test
    fun knownCapabilitiesKeepStableOrder() {
        // 无论 manifest 里怎么写顺序，展示顺序都按 ProviderCapability.ALL 固定 ——
        // 否则同一个音源在两次安装后标签顺序会变（用户以为装错了）。
        val reversed = parse(
            """
            {
              "id": "caps2", "name": "Caps2", "version": "1.0.0",
              "type": "http", "entryPoint": "http://127.0.0.1:3000",
              "capabilities": ["lyricsSearch", "comments", "qrLogin"]
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ProviderCapability.QR_LOGIN,
                ProviderCapability.COMMENTS,
                ProviderCapability.LYRICS_SEARCH,
            ),
            reversed.knownCapabilities,
        )
    }

    private companion object {
        /** 故意写成 `宿主版本 + 1`：宿主 API 涨到 4 时这条测试跟着仍然成立。 */
        const val HOST_PROVIDER_API_VERSION_PLUS_ONE = HOST_PROVIDER_API_VERSION + 1
    }
}
