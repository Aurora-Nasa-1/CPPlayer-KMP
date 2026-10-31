package cp.player.core.provider

import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformInfo
import cp.player.core.util.PlatformSupport
import cp.player.core.util.createHttpClient
import cp.player.core.util.defaultSettingsStorage
import io.ktor.client.request.header
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
 * 独立可执行文件 Provider（仅适用于 Desktop 端：Windows / Linux / macOS）。
 *
 * ⚠️ **平台限制与规范说明（Android 端）**：
 * Android 系统的安全模型（W^X、`noexec`）严格禁止在应用私有数据目录执行二进制程序，
 * `ProcessBuilder` / `exec` 均会被系统拦截并报 `Permission denied`。
 * 因此 Android 端不支持 binary，规范已全面提升至运行 `.so`（通过 [JniProvider] 加载）。
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
    override val loginMethods: List<String>? = null,
    override val capabilities: List<String>? = null,
    override val apiVersion: Int = 1,
    override val apiVersionTag: String? = null,
    override val contract: String? = null,
    override val settingsSchema: List<ProviderSettingItemSchema>? = null
) : BackendProvider {

    override val type: ProviderType = ProviderType.BINARY

    private var process: Process? = null
    private var port: Int = 3000
    private var isStopped: Boolean = false
    private val client = createHttpClient()
    override var loadError: String? = null
    private val configStorage by lazy { defaultSettingsStorage("provider_config_$id") }

    init {
        if (PlatformInfo.isAndroid) {
            loadError = "Android 端不支持运行独立二进制可执行文件 (binary)，规范已提升至运行 .so 原生库"
        } else {
            val file = File(binaryPath)
            if (!file.exists()) {
                loadError = "二进制文件不存在: $binaryPath"
            }
        }
    }

    override fun isReady(): Boolean = loadError == null

    override fun startServer(context: PlatformContext, port: Int) {
        if (PlatformInfo.isAndroid) {
            val err = "Android 端不支持运行独立二进制可执行文件 (binary)，规范已提升至运行 .so 原生库"
            loadError = err
            throw IllegalStateException(err)
        }
        if (loadError != null) throw IllegalStateException("Binary not ready: $loadError")
        this.port = port
        this.isStopped = false
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
        isStopped = true
        process?.let { proc ->
            try {
                proc.destroyForcibly()
                proc.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (ignored: Throwable) {}
        }
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
        if (isStopped) return """{"code": 500, "msg": "Binary provider is stopped"}"""
        if (loadError != null && process == null) return """{"code": 500, "msg": "Binary not ready: $loadError"}"""
        if (process != null && !process!!.isAlive) {
            val exitCode = runCatching { process?.exitValue() }.getOrNull()
            println("[BinaryProvider-$id] 子进程已退出 (exitCode=$exitCode)，尝试自愈重启...")
            try {
                startServer(PlatformContext(), port)
            } catch (e: Throwable) {
                return """{"code": 500, "msg": "Binary exited ($exitCode) and restart failed: ${e.message?.replace("\"", "\\\"")}"}"""
            }
        }
        val configJson = settingsSchema?.let { schema ->
            buildJsonObject {
                schema.forEach { item ->
                    val value = configStorage.getString(item.key) ?: item.default
                    if (value != null) {
                        put(item.key, JsonPrimitive(value))
                    }
                }
            }
        }
        val body = buildJsonObject {
            settingsSchema?.forEach { item ->
                val value = configStorage.getString(item.key) ?: item.default
                if (value != null) put(item.key, JsonPrimitive(value))
            }
            params.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            if (configJson != null && configJson.isNotEmpty()) {
                put("config", configJson)
            }
        }.toString()
        return try {
            runBlocking {
                val resp = client.post("http://127.0.0.1:$port/api/$method") {
                    contentType(ContentType.Application.Json)
                    if (configJson != null && configJson.isNotEmpty()) {
                        header("X-Provider-Config", configJson.toString())
                    }
                    setBody(body)
                }
                resp.bodyAsText()
            }
        } catch (e: Exception) {
            """{"code": 500, "msg": "Binary call failed: ${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    override fun analyzeAudio(path: String): String = """{"code": 500}"""

    override fun getDiagnostics(): Map<String, String> = buildMap {
        put("id", id)
        put("type", "BINARY")
        put("binaryPath", binaryPath)
        put("port", port.toString())
        val p = process
        put("running", (p?.isAlive == true).toString())
        if (p != null && !p.isAlive) {
            put("exitCode", runCatching { p.exitValue().toString() }.getOrDefault("unknown"))
        }
        loadError?.let { put("loadError", it) }
    }

    /** 停掉子进程并释放 Ktor 客户端（连接池 + 后台线程）。 */
    override fun close() {
        stopServer()
        client.close()
    }
}
