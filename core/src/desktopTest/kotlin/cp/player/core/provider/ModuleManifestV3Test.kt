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
            "缺省字段被序列化写回了，往返一次 manifest 不再与原始文件一致：$roundTripped",
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
    fun apiVersionTagIsFreeFormLabelAndNeverABarrier() {
        // 代际标签刻意是**自由字符串**：宿主不认识 "netease-v3" 也必须照常加载。
        // 若这里改成 enum / 白名单，新代际名的包在旧宿主上会直接装不上 —— 那才是灾难。
        val m = parse(
            """
            {
              "id": "netease-v3", "name": "网易云 v3", "version": "1.0.0",
              "type": "http", "entryPoint": "http://127.0.0.1:3000/v123",
              "apiVersionTag": "netease-v3"
            }
            """.trimIndent(),
        )
        assertEquals("netease-v3", m.resolvedApiVersionTag)
        assertEquals("netease-v3", m.apiVersionTag, "标签必须原样保留，不能被规范化成别的写法")
        assertTrue(m.isLoadable, "标签不是加载闸门，判据只有 minHostApiVersion")
        // 标签与契约编号是两个维度：声明了标签不会顺手把 apiVersion 抬成 3。
        assertEquals(1, m.resolvedApiVersion)
    }

    @Test
    fun blankApiVersionTagIsTreatedAsUndeclared() {
        // 空串 / 全空白会渲染成「· ·」这种只有分隔点的空角标，应当等价于没声明。
        val blank = parse(
            """
            {
              "id": "blank-tag", "name": "Blank", "version": "1.0.0",
              "type": "http", "entryPoint": "http://127.0.0.1:3000",
              "apiVersionTag": "   "
            }
            """.trimIndent(),
        )
        assertNull(blank.resolvedApiVersionTag)
        // 字段本身仍保留原值（不替作者改写 manifest）。
        assertEquals("   ", blank.apiVersionTag)
    }

    @Test
    fun missingApiVersionTagIsNotWrittenBack() {
        val m = parse(legacyJson)
        val roundTripped = json.encodeToString(ModuleManifest.serializer(), m)
        assertFalse(
            roundTripped.contains("apiVersionTag"),
            "缺省字段被序列化写回，往返一次 manifest 不再与原始文件一致：$roundTripped",
        )
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

    @Test
    fun featureAvailabilityRespectsCapabilitiesDeclaration() {
        val legacy = parse(legacyJson)
        // 未声明能力的老包：保守兼容，所有能力均不主动禁用
        assertTrue(legacy.isFeatureAvailable(ProviderCapability.CLOUD_DRIVE))
        assertTrue(legacy.isFeatureAvailable(ProviderCapability.COMMENTS))

        val declared = parse(
            """
            {
              "id": "caps", "name": "Caps", "version": "1.0.0",
              "type": "http", "entryPoint": "http://127.0.0.1:3000",
              "capabilities": ["comments"]
            }
            """.trimIndent()
        )
        // 显式声明了能力的新模块：未声明的进行物理隐藏与功能降级
        assertTrue(declared.isFeatureAvailable(ProviderCapability.COMMENTS))
        assertFalse(declared.isFeatureAvailable(ProviderCapability.CLOUD_DRIVE))
    }

    @Test
    fun settingsSchemaSerializationAndParsing() {
        val manifest = parse(
            """
            {
              "id": "schema-source", "name": "Schema Source", "version": "1.0.0",
              "type": "http", "entryPoint": "http://127.0.0.1:3000",
              "contract": "standard",
              "settingsSchema": [
                {
                  "key": "serverUrl",
                  "label": "服务器地址",
                  "type": "string",
                  "placeholder": "https://music.example.com",
                  "required": true
                },
                {
                  "key": "quality",
                  "label": "首选音质",
                  "type": "select",
                  "default": "lossless",
                  "options": [
                    { "label": "标准", "value": "standard" },
                    { "label": "无损", "value": "lossless" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals("standard", manifest.contract)
        val schema = manifest.settingsSchema
        assertEquals(2, schema?.size)
        assertEquals("serverUrl", schema?.get(0)?.key)
        assertTrue(schema?.get(0)?.required ?: false)
        assertEquals(2, schema?.get(1)?.options?.size)
    }

    @Test
    fun loginMethodsAndWebviewCapabilityParsedCorrectly() {
        val manifest = parse(
            """
            {
              "id": "webview-source", "name": "Webview Source", "version": "1.0.0",
              "type": "http", "entryPoint": "http://127.0.0.1:3000",
              "contract": "standard",
              "capabilities": ["webviewLogin", "playback", "search"],
              "loginMethods": ["webview", "sms"]
            }
            """.trimIndent()
        )
        assertTrue(manifest.supports(ProviderCapability.WEBVIEW_LOGIN))
        assertTrue(manifest.isFeatureAvailable(ProviderCapability.WEBVIEW_LOGIN))
        assertEquals(listOf("webview", "sms"), manifest.loginMethods)
        assertEquals(
            listOf(ProviderCapability.SEARCH, ProviderCapability.PLAYBACK, ProviderCapability.WEBVIEW_LOGIN),
            manifest.knownCapabilities,
        )
    }


    private companion object {
        /** 故意写成 `宿主版本 + 1`：宿主 API 涨到 4 时这条测试跟着仍然成立。 */
        const val HOST_PROVIDER_API_VERSION_PLUS_ONE = HOST_PROVIDER_API_VERSION + 1
    }
}
