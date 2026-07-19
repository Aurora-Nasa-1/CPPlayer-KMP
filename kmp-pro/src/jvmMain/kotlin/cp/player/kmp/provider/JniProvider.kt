package cp.player.kmp.provider

import cp.player.kmp.util.PlatformContext
import cp.player.kmp.util.PlatformSupport
import java.io.File

class JniProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    private val soPath: String,
    override val apiMap: Map<String, String>? = null,
    override val updateUrl: String? = null,
    override val targetAppPackage: String? = null
) : BackendProvider {

    override val type: ProviderType = ProviderType.JNI
    private var isLoaded = false
    private var loadError: String? = null

    init {
        val soFile = File(soPath)
        if (!soFile.exists()) {
            loadError = "SO 文件不存在: $soPath"
            log(loadError!!)
        }
    }

    override fun isReady(): Boolean = loadError == null

    fun getLoadError(): String? = loadError

    external fun startNativeServer(host: String, port: Int)
    external fun nativeCallApi(method: String, paramsJson: String): String
    external fun analyzeAudioFile(path: String): String

    override fun startServer(context: PlatformContext, port: Int) {
        loadNativeLibrary()
        if (!isLoaded) throw IllegalStateException("JNI 库加载失败: $loadError")
        try {
            startNativeServer("127.0.0.1", port)
            log("JNI 服务启动成功: $soPath, port=$port")
        } catch (e: Throwable) {
            isLoaded = false
            loadError = "JNI 服务启动崩溃: ${e.message}"
            log("startNativeServer 崩溃: ${e.message}")
            throw e
        }
    }

    override fun stopServer() {
        isLoaded = false
    }

    override fun callApi(method: String, params: Map<String, String>): String {
        if (!isLoaded) return """{"code": 500, "msg": "JNI not loaded: ${loadError ?: "unknown"}"}"""
        val json = buildMap { params.forEach { (k, v) -> put(k, v) } }
            .entries.joinToString(separator = ",", prefix = "{", postfix = "}") {
                "\"${it.key}\":\"${it.value}\""
            }
        return try {
            nativeCallApi(method, json)
        } catch (e: Throwable) {
            isLoaded = false
            loadError = "JNI 调用崩溃: ${e.message}"
            log("nativeCallApi 崩溃: $method - ${e.message}")
            """{"code": 500, "msg": "JNI call crashed: ${e.message}"}"""
        }
    }

    override fun analyzeAudio(path: String): String {
        if (!isLoaded) return """{"code": 500, "msg": "JNI not loaded: ${loadError ?: "unknown"}"}"""
        return try {
            analyzeAudioFile(path)
        } catch (e: Throwable) {
            isLoaded = false
            loadError = "JNI 分析崩溃: ${e.message}"
            log("analyzeAudioFile 崩溃: ${e.message}")
            """{"code": 500, "msg": "JNI analyze crashed: ${e.message}"}"""
        }
    }

    private fun loadNativeLibrary() {
        if (isLoaded) return
        val soFile = File(soPath)

        if (!soFile.exists()) {
            loadError = "SO 文件不存在: $soPath"
            log(loadError!!)
            return
        }
        if (soFile.length() < 1024) {
            loadError = "SO 文件过小 (${soFile.length()} bytes)，可能已损坏: $soPath"
            log(loadError!!)
            return
        }
        if (!soFile.canRead()) {
            loadError = "SO 文件无法读取（权限不足）: $soPath"
            log(loadError!!)
            return
        }

        PlatformSupport.validateElfHeader(soPath)?.let {
            loadError = it
            log(it)
            return
        }

        try {
            System.load(soPath)
            isLoaded = true
            loadError = null
            log("JNI 库加载成功: $soPath")
        } catch (e: UnsatisfiedLinkError) {
            loadError = "JNI 链接失败: ${e.message}"
            log("加载 SO 失败: $soPath - ${e.message}")
        } catch (e: Exception) {
            loadError = "JNI 加载异常: ${e.message}"
            log("加载 SO 异常: $soPath - ${e.message}")
        }
    }

    private fun log(msg: String) {
        println("[JniProvider] $msg")
    }
}
