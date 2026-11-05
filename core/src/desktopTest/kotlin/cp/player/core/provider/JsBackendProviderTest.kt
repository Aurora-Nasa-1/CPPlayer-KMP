package cp.player.core.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsBackendProviderTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("cpplayer-js-provider-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `js backend provider dispatches search, song detail, and media source`() {
        val scriptFile = File(tempDir, "index.js")
        scriptFile.writeText(
            """
            globalThis.CPPlayer = {
                async search({ keywords, page, limit }) {
                    // 测试原生 crypto 加速与 storage
                    const sign = crypto.md5(keywords + "_salt");
                    storage.setItem("last_keyword", keywords);

                    return {
                        code: 200,
                        sign: sign,
                        data: {
                            songs: [
                                {
                                    id: "track_001",
                                    title: "晴天 (" + keywords + ")",
                                    artist: "周杰伦",
                                    album: "叶惠美",
                                    durationMs: 269000,
                                    coverUrl: "https://example.com/cover.jpg"
                                }
                            ]
                        }
                    };
                },

                async getMediaSource({ id, quality }) {
                    const cached = storage.getItem("last_keyword");
                    return {
                        code: 200,
                        url: "https://stream.example.com/" + id + ".flac",
                        headers: {
                            "Referer": "https://music.example.com",
                            "X-Custom-Sign": crypto.sha256(id)
                        },
                        cachedKeyword: cached
                    };
                },

                async getSongDetail({ id }) {
                    return {
                        id: id,
                        title: "七里香",
                        artist: "周杰伦"
                    };
                }
            };
            """.trimIndent(),
            Charsets.UTF_8
        )

        val provider = JsBackendProvider(
            id = "test-js-provider",
            name = "Test JS Provider",
            version = "1.0.0",
            scriptPath = scriptFile.absolutePath,
            moduleDir = tempDir.absolutePath
        )

        try {
            assertTrue(provider.isReady())

            // 1. 测试 search 调用
            val searchRes = provider.callApi("cloudsearch", mapOf("keywords" to "Jay", "page" to "1"))
            val searchJson = json.parseToJsonElement(searchRes).jsonObject
            assertEquals(200, searchJson["code"]?.jsonPrimitive?.content?.toInt())
            val song = searchJson["data"]?.jsonObject?.get("songs")?.jsonArray?.get(0)?.jsonObject
            assertEquals("晴天 (Jay)", song?.get("title")?.jsonPrimitive?.content)
            assertEquals("叶惠美", song?.get("album")?.jsonPrimitive?.content)

            // 2. 测试 getMediaSource 调用（且验证 storage 状态持久保持）
            val urlRes = provider.callApi("song/url/v1", mapOf("id" to "track_001"))
            val urlJson = json.parseToJsonElement(urlRes).jsonObject
            assertEquals(200, urlJson["code"]?.jsonPrimitive?.content?.toInt())
            assertEquals("https://stream.example.com/track_001.flac", urlJson["url"]?.jsonPrimitive?.content)
            assertEquals("Jay", urlJson["cachedKeyword"]?.jsonPrimitive?.content)
            val reqHeaders = urlJson["headers"]?.jsonObject
            assertEquals("https://music.example.com", reqHeaders?.get("Referer")?.jsonPrimitive?.content)

            // 3. 测试 getSongDetail 调用
            val detailRes = provider.callApi("song/detail", mapOf("id" to "track_002"))
            val detailJson = json.parseToJsonElement(detailRes).jsonObject
            assertEquals("七里香", detailJson["title"]?.jsonPrimitive?.content)
            assertEquals("周杰伦", detailJson["artist"]?.jsonPrimitive?.content)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `js backend provider supports aes crypto and settings schema injection`() {
        val scriptFile = File(tempDir, "crypto_test.js")
        scriptFile.writeText(
            """
            globalThis.CPPlayer = {
                async callApi(method, params) {
                    if (method === "test/crypto") {
                        const key = "1234567890123456";
                        const iv = "1234567890123456";
                        const cipher = crypto.aesEncrypt("CPPlayer_Secret", key, iv);
                        const plain = crypto.aesDecrypt(cipher, key, iv);
                        return {
                            plain: plain,
                            token: env.settings.api_token
                        };
                    }
                    return { code: 404 };
                }
            };
            """.trimIndent(),
            Charsets.UTF_8
        )

        val provider = JsBackendProvider(
            id = "test-crypto-provider",
            name = "Crypto Test Provider",
            version = "1.0.0",
            scriptPath = scriptFile.absolutePath,
            moduleDir = tempDir.absolutePath,
            settingsSchema = listOf(
                ProviderSettingItemSchema(
                    key = "api_token",
                    label = "API Token",
                    type = "string",
                    default = "default_token_xyz"
                )
            )
        )

        try {
            val res = provider.callApi("test/crypto", emptyMap())
            val resJson = json.parseToJsonElement(res).jsonObject
            assertEquals("CPPlayer_Secret", resJson["plain"]?.jsonPrimitive?.content)
            assertEquals("default_token_xyz", resJson["token"]?.jsonPrimitive?.content)
        } finally {
            provider.close()
        }
    }
}
