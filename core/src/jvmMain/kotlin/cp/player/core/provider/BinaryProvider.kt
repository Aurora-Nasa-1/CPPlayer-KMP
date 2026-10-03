package cp.player.core.provider

import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformSupport
import cp.player.core.util.createHttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 独立可执行文件 Provider（JVM 共享：Android + Desktop）。
 *
 * - 启动：[ProcessBuilder] 运行二进制 `--port <port>`
 * - 通信：HTTP POST 到 `http://127.0.0.1:port/api/<method>`
 *
 * **延迟加载**：构造函数仅校验文件存在，ELF 校验与进程启动均在 [startServer] 中执行。
 */
class BinaryProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    private val binaryPath: String,
    override val apiMap: Map<String, String>? = null,
    override val updateUrl: String? = null,
    override val targetAppPackage: String? = null,
    override val loginMethods: List<String>? = null
) : BackendProvider {

    override val type: ProviderType = ProviderType.BINARY

    private var process: Process? = null
    private var port: Int = 3000
    private val client = createHttpClient()
    private var loadError: String? = null

    init {
        val file = File(binaryPath)
        if (!file.exists()) {
            loadError = "二进制文件不存在: $binaryPath"
        }
    }

    override fun isReady(): Boolean = loadError == null

    /** 获取加载失败的错误信息 */
    fun getLoadError(): String? = loadError

    override fun startServer(context: PlatformContext, port: Int) {
        if (loadError != null) throw IllegalStateException("Binary not ready: $loadError")
        this.port = port
        val file = File(binaryPath)
        if (!file.exists()) {
            loadError = "二进制文件不存在: $binaryPath"
            throw IllegalStateException(loadError!!)
        }

        val elfError = PlatformSupport.validateElfHeader(binaryPath)
        if (elfError != null) {
            loadError = elfError
            throw IllegalStateException("ELF 校验失败: $elfError")
        }

        try {
            file.setExecutable(true)
            process = ProcessBuilder(binaryPath, "--port", port.toString())
                .directory(file.parentFile)
                .redirectErrorStream(true)
                .start()
            drainProcessOutput(process!!)
            println("[BinaryProvider] Started $binaryPath on port $port")
        } catch (e: Exception) {
            loadError = "Binary 启动失败: ${e.message}"
            println("[BinaryProvider] Failed to start: ${e.message}")
            throw e
        }
    }

    override fun stopServer() {
        process?.destroy()
        process = null
    }

    /**
     * 持续排空子进程输出。
     *
     * `redirectErrorStream(true)` 把 stderr 并进 stdout，而子进程一旦写入量超过 OS 管道
     * 缓冲就会阻塞在 `write` —— 没人读进程输出时，Provider 会「假死」、`callApi` 一直超时。
     * 守护线程读到 EOF 自然结束，[stopServer] 销毁进程时随之退出。
     */
    private fun drainProcessOutput(proc: Process) {
        Thread({
            // 整体兜底：排空线程自身的异常不该影响主流程。
            runCatching {
                val input = proc.inputStream
                val buf = ByteArray(8192)
                while (input.read(buf) >= 0) {
                    // 丢弃输出；需要排查模块问题时改成写入受限日志。
                }
            }
        }, "binary-provider-drain").apply { isDaemon = true }.start()
    }

    override fun callApi(method: String, params: Map<String, String>): String {
        if (loadError != null) return """{"code": 500, "msg": "Binary not ready: $loadError"}"""
        val body = buildJsonObject {
            params.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }.toString()
        return try {
            runBlocking {
                val resp = client.post("http://127.0.0.1:$port/api/$method") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                resp.bodyAsText()
            }
        } catch (e: Exception) {
            """{"code": 500, "msg": "Binary call failed: ${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    override fun analyzeAudio(path: String): String = """{"code": 500}"""
}
