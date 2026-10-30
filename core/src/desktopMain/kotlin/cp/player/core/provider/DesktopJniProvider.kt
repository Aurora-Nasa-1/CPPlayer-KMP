package cp.player.core.provider

import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformSupport
import cp.player.core.util.createHttpClient
import cp.player.core.util.defaultSettingsStorage
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Desktop 平台专用的 JNI Provider 实现。
 *
 * 在 Desktop 端：
 * 1. 优先使用独立 [JniWorker] 子进程承载 Native 模块，彻底隔离 JVM 符号并避免文件锁。
 * 2. 切换音源时，旧 Worker 进程直接强杀（destroyForcibly），OS 立即回收端口并卸载动态库。
 * 3. 若环境受限导致子进程拉起失败，平滑降级为进程内 [JniProvider]。
 */
class DesktopJniProvider(
    id: String,
    name: String,
    version: String,
    soPath: String,
    apiMap: Map<String, String>? = null,
    updateUrl: String? = null,
    targetAppPackage: String? = null,
    loginMethods: List<String>? = null,
    capabilities: List<String>? = null,
    apiVersion: Int = 1,
    apiVersionTag: String? = null,
    contract: String? = null,
    settingsSchema: List<ProviderSettingItemSchema>? = null
) : JniProvider(
    id = id,
    name = name,
    version = version,
    soPath = soPath,
    apiMap = apiMap,
    updateUrl = updateUrl,
    targetAppPackage = targetAppPackage,
    loginMethods = loginMethods,
    capabilities = capabilities,
    apiVersion = apiVersion,
    apiVersionTag = apiVersionTag,
    contract = contract,
    settingsSchema = settingsSchema
) {

    private var workerProcess: Process? = null
    private var inProcessFallback: JniProvider? = null
    private var isLoaded = false
    override var loadError: String? = null
        get() = field ?: inProcessFallback?.loadError ?: super.loadError
        protected set
    private var serverPort = 3000
    private var httpClient: HttpClient? = null
    private val configStorage by lazy { defaultSettingsStorage("provider_config_$id") }

    init {
        val soFile = File(soPath)
        if (!soFile.exists()) {
            loadError = "SO 文件不存在: $soPath"
        }
    }

    override fun isReady(): Boolean = loadError == null

    override fun startServer(context: PlatformContext, port: Int) {
        val soFile = File(soPath)
        if (!soFile.exists()) {
            loadError = "SO 文件不存在: $soPath"
            throw IllegalStateException(loadError!!)
        }
        this.serverPort = port

        // 尝试拉起独立 Worker 子进程
        val started = startWorkerProcess(port)
        if (started) {
            isLoaded = true
            loadError = null
            println("[DesktopJniProvider] 独立 Worker 进程启动成功: id=$id, port=$port")
            return
        }

        // 降级为进程内 JniProvider
        println("[DesktopJniProvider] Worker 启动失败，降级为进程内调用: id=$id")
        val inProcess = JniProvider(
            id = id,
            name = name,
            version = version,
            soPath = soPath,
            apiMap = apiMap,
            updateUrl = updateUrl,
            targetAppPackage = targetAppPackage,
            loginMethods = loginMethods,
            capabilities = capabilities,
            apiVersion = apiVersion,
            apiVersionTag = apiVersionTag,
            contract = contract,
            settingsSchema = settingsSchema
        )
        inProcess.startServer(context, port)
        inProcessFallback = inProcess
        isLoaded = inProcess.isReady()
        loadError = inProcess.loadError
    }

    private fun startWorkerProcess(port: Int): Boolean {
        return try {
            val javaHome = System.getProperty("java.home") ?: ""
            val isWin = System.getProperty("os.name", "").lowercase().contains("win")
            val javaExe = if (isWin) File(javaHome, "bin/java.exe") else File(javaHome, "bin/java")
            val javaBin = if (javaExe.exists()) javaExe.absolutePath else "java"
            val classpath = System.getProperty("java.class.path") ?: ""

            val pb = ProcessBuilder(
                javaBin,
                "-cp",
                classpath,
                "cp.player.core.provider.JniWorker",
                "--dll",
                File(soPath).absolutePath,
                "--port",
                port.toString()
            )
            pb.directory(File(soPath).parentFile)
            pb.redirectErrorStream(true)
            val proc = pb.start()

            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var ready = false
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    println("[DesktopJniProvider-$id] $line")
                    if (line.contains("[JniWorker] READY")) {
                        ready = true
                        break
                    }
                } else {
                    if (!proc.isAlive) {
                        println("[DesktopJniProvider-$id] Worker 过早退出: exitValue=${proc.exitValue()}")
                        break
                    }
                    Thread.sleep(50)
                }
            }

            if (ready) {
                workerProcess = proc
                drainProcessOutput(proc)
                true
            } else {
                proc.destroyForcibly()
                false
            }
        } catch (e: Throwable) {
            println("[DesktopJniProvider] 启动子进程失败: ${e.message}")
            false
        }
    }

    private fun drainProcessOutput(proc: Process) {
        Thread({
            runCatching {
                val input = proc.inputStream
                val buf = ByteArray(8192)
                while (input.read(buf) >= 0) {
                    // drain
                }
            }
        }, "desktop-jni-worker-drain-$id").apply { isDaemon = true }.start()
    }

    override fun stopServer() {
        workerProcess?.let { proc ->
            try {
                proc.destroyForcibly()
                proc.waitFor(500, TimeUnit.MILLISECONDS)
            } catch (ignored: Throwable) {}
        }
        workerProcess = null
        inProcessFallback?.stopServer()
        inProcessFallback = null
        httpClient?.close()
        httpClient = null
        isLoaded = false
    }

    private fun getOrCreateHttpClient(): HttpClient {
        return httpClient ?: createHttpClient().also { httpClient = it }
    }

    override fun callApi(method: String, params: Map<String, String>): String {
        inProcessFallback?.let {
            return it.callApi(method, params)
        }
        var proc = workerProcess
        if (proc == null || !proc.isAlive) {
            val exitCode = proc?.let { runCatching { it.exitValue() }.getOrNull() }
            println("[DesktopJniProvider-$id] Worker 子进程不可用 (exitCode=$exitCode)，尝试自愈重启...")
            val restarted = startWorkerProcess(serverPort)
            if (restarted) {
                proc = workerProcess
            } else {
                return """{"code":500,"msg":"Provider worker process not running (exitCode=$exitCode, restart failed: ${loadError ?: "unknown"})"}"""
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
                val client = getOrCreateHttpClient()
                val resp = client.post("http://127.0.0.1:$serverPort/api/$method") {
                    contentType(ContentType.Application.Json)
                    if (configJson != null && configJson.isNotEmpty()) {
                        header("X-Provider-Config", configJson.toString())
                    }
                    setBody(body)
                }
                resp.bodyAsText()
            }
        } catch (e: Exception) {
            """{"code": 500, "msg": "Worker HTTP call failed: ${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    override fun analyzeAudio(path: String): String {
        inProcessFallback?.let {
            return it.analyzeAudio(path)
        }
        val proc = workerProcess
        if (proc == null || !proc.isAlive) {
            return """{"error":"Provider worker process not running"}"""
        }
        return try {
            runBlocking {
                val client = getOrCreateHttpClient()
                val body = buildJsonObject {
                    put("path", JsonPrimitive(path))
                }.toString()
                val resp = client.post("http://127.0.0.1:$serverPort/api/analyzeAudio") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                resp.bodyAsText()
            }
        } catch (e: Exception) {
            """{"error":"Audio analysis failed: ${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    override fun getDiagnostics(): Map<String, String> = buildMap {
        put("id", id)
        put("type", "JNI (Desktop Worker)")
        put("soPath", soPath)
        put("serverPort", serverPort.toString())
        val proc = workerProcess
        put("workerRunning", (proc?.isAlive == true).toString())
        if (proc != null && !proc.isAlive) {
            put("workerExitCode", runCatching { proc.exitValue().toString() }.getOrDefault("unknown"))
        }
        if (inProcessFallback != null) {
            put("mode", "in-process fallback")
        } else {
            put("mode", "isolated worker process")
        }
        loadError?.let { put("loadError", it) }
    }

    override fun close() {
        stopServer()
    }
}
