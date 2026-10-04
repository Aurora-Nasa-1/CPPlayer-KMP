package cp.player.core.provider

import cp.player.core.util.PlatformContext
import cp.player.core.util.createHttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HTTP API 服务 Provider（KMP 版，Ktor 实现）。
 *
 * 连接到已有的 HTTP API 服务（如 NeteaseCloudMusicApi）。
 * `startServer/stopServer` 为空操作（服务由外部启动）。
 */
class HttpProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    private val baseUrl: String,
    override val apiMap: Map<String, String>? = null,
    override val updateUrl: String? = null,
    override val targetAppPackage: String? = null,
    override val loginMethods: List<String>? = null
) : BackendProvider {

    override val type: ProviderType = ProviderType.HTTP

    private val client = createHttpClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override fun startServer(context: PlatformContext, port: Int) {}
    override fun stopServer() {}

    override fun callApi(method: String, params: Map<String, String>): String {
        val body = buildJsonObject {
            params.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }.toString()
        return try {
            // callApi 是同步契约，使用 runBlocking 转发 IO 协程
            kotlinx.coroutines.runBlocking {
                val resp: HttpResponse = client.post("${baseUrl.trimEnd('/')}/$method") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                resp.bodyAsText()
            }
        } catch (e: Exception) {
            """{"code": 500, "msg": "HTTP call failed: ${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    override fun analyzeAudio(path: String): String = """{"code": 500, "msg": "Not supported"}"""

    /** 释放 Ktor 客户端（连接池 + 后台线程）；Provider 重载 / 后端 reset 时不释放会持续泄漏。 */
    override fun close() {
        client.close()
    }
}