package cp.player.core.provider

import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformSupport
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

class JniProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    private val soPath: String,
    override val apiMap: Map<String, String>? = null,
    override val updateUrl: String? = null,
    override val targetAppPackage: String? = null,
    override val loginMethods: List<String>? = null
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
        log("startServer 开始: soPath=$soPath, port=$port")
        loadNativeLibrary()
        if (!isLoaded) {
            log("startServer 失败: JNI 库加载失败: $loadError")
            throw IllegalStateException("JNI 库加载失败: $loadError")
        }
        try {
            startNativeServer("127.0.0.1", port)
            log("JNI 服务启动成功: $soPath, port=$port")
        } catch (e: UnsatisfiedLinkError) {
            // 库能 load、但方法找不到 —— 几乎总是模块的导出符号前缀与宿主包名不一致。
            isLoaded = false
            loadError = "JNI 服务启动崩溃: ${e.message} —— ${symbolMismatchHint("startNativeServer")}"
            log(loadError!!)
            throw e
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
        if (!isLoaded) {
            log("callApi 被调用但 JNI 未加载: method=$method, loadError=$loadError")
            return errorJson("JNI not loaded: ${loadError ?: "unknown"}")
        }
        // 用 buildJsonObject 构造参数 JSON，自动转义 cookie 等值中的 " \ 等特殊字符
        val json = buildJsonObject {
            params.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }.toString()
        log("callApi -> nativeCallApi: method=$method, params={${maskParamsForLog(params)}}")
        return try {
            val result = nativeCallApi(method, json)
            // 只记长度：响应体可能含账号资料 / 私信片段，不适合整段落日志。
            log("callApi <- nativeCallApi: method=$method, resultLen=${result.length}")
            result
        } catch (e: UnsatisfiedLinkError) {
            isLoaded = false
            loadError = "JNI 调用崩溃: ${e.message} —— ${symbolMismatchHint("nativeCallApi")}"
            log(loadError!!)
            errorJson(loadError!!)
        } catch (e: Throwable) {
            isLoaded = false
            loadError = "JNI 调用崩溃: ${e.message}"
            log("nativeCallApi 崩溃: $method - ${e.message}")
            errorJson("JNI call crashed: ${e.message}")
        }
    }

    override fun analyzeAudio(path: String): String {
        if (!isLoaded) return errorJson("JNI not loaded: ${loadError ?: "unknown"}")
        return try {
            analyzeAudioFile(path)
        } catch (e: UnsatisfiedLinkError) {
            isLoaded = false
            loadError = "JNI 分析崩溃: ${e.message} —— ${symbolMismatchHint("analyzeAudioFile")}"
            log(loadError!!)
            errorJson(loadError!!)
        } catch (e: Throwable) {
            isLoaded = false
            loadError = "JNI 分析崩溃: ${e.message}"
            log("analyzeAudioFile 崩溃: ${e.message}")
            errorJson("JNI analyze crashed: ${e.message}")
        }
    }

    /**
     * native 导出符号与宿主类的**全限定名**硬绑定：JNI 按
     * `Java_<包名下划线化>_<类名>_<方法名>` 查找符号。
     *
     * 宿主改包名后，用旧前缀编译的模块**仍能被 `System.load()` 成功加载**（它是合法的
     * PE/ELF），但**首次方法调用**才抛 [UnsatisfiedLinkError] —— 症状是
     * 「模块显示已加载，一调用就崩」，很容易被误判成模块损坏或网络问题。
     *
     * 这里用**运行时的实际类名**推导期望符号，所以包名以后再改，提示也会自动跟着变。
     */
    private fun symbolMismatchHint(method: String): String {
        val expected = "Java_" + javaClass.name.replace('.', '_') + "_" + method
        return "native 导出符号与宿主包名不匹配：JNI 按 `Java_<包名下划线化>_<类名>_<方法名>` " +
            "查找符号，当前宿主要求该模块导出 `$expected`（已加载: $soPath）。" +
            "请用当前宿主包名**重新编译**该模块并重新部署到 modules 目录 —— " +
            "只改 native 源码不重新构建二进制是不生效的。"
    }

    /** 统一的错误 JSON。手写字符串模板不会转义 message 里的引号，会让集成方解析失败。 */
    private fun errorJson(msg: String): String = buildJsonObject {
        put("code", JsonPrimitive(500))
        put("msg", JsonPrimitive(msg))
    }.toString()

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
            // 注意：这里失败是 System.load 本身失败（缺依赖库 / 架构不匹配），
            // 与「加载成功但方法找不到」是两种不同的故障，不要混淆。
            loadError = "JNI 链接失败（缺依赖库或架构不匹配）: ${e.message}"
            log("加载 SO 失败: $soPath - ${e.message}")
        } catch (e: Exception) {
            loadError = "JNI 加载异常: ${e.message}"
            log("加载 SO 异常: $soPath - ${e.message}")
        }
    }

    private fun log(msg: String) {
        println("[JniProvider] $msg")
    }

    /**
     * 打日志前对参数做**脱敏**。
     *
     * `MusicApiServiceImpl` 会把会话 cookie 注入 params（等价于账号密码），而 Android 上
     * `println` 直接进 logcat、桌面上进 stdout —— 原样打印等于把登录态写进日志，任何
     * 日志抓取工具 / 崩溃上报 / `adb logcat` 都能读到。这里只保留键名，敏感键的值换成
     * 长度占位符，既堵住泄露又不损失定位故障所需的信息量。
     */
    private fun maskParamsForLog(params: Map<String, String>): String =
        params.entries.joinToString(", ") { (k, v) ->
            if (k.lowercase() in SENSITIVE_PARAM_KEYS) "$k=***(${v.length})" else "$k=$v"
        }

    private companion object {
        /** 值等价于凭据的参数键，绝不落日志。 */
        val SENSITIVE_PARAM_KEYS = setOf(
            "cookie", "token", "password", "md5_password", "access_token", "refresh_token",
        )
    }
}
