package cp.player.kmp.provider

import cp.player.kmp.util.PlatformContext
import cp.player.kmp.util.PlatformSupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * 模块管理器（KMP 版）。
 *
 * 从 `modules` 目录加载/导入/更新/删除 [BackendProvider] 模块。
 *
 * 模块结构：每个子目录含 `manifest.json` + 入口（链接库/二进制或 http entryPoint）。
 *
 * @param modulesDir 模块根目录绝对路径（来自 [PlatformSupport.modulesDir]）
 */
class ModuleManager(
    private val modulesDir: String,
    private val context: PlatformContext
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val providers = mutableMapOf<String, BackendProvider>()

    private val _providersFlow = MutableStateFlow<List<BackendProvider>>(emptyList())
    val providersFlow: StateFlow<List<BackendProvider>> = _providersFlow.asStateFlow()

    /** 最近一次导入/加载失败的错误信息（供 UI 展示） */
    var lastLoadError: String? = null
        private set

    private fun updateProvidersFlow() { _providersFlow.value = providers.values.toList() }

    /**
     * 扫描 modulesDir，加载所有子目录模块，并按 [providerManager] 恢复/自动选择活跃 Provider。
     */
    fun init(providerManager: ProviderManager) {
        if (!PlatformSupport.exists(modulesDir)) {
            // 目录不存在时创建（jvmMain deleteRecursively/unzipTo 等会忽略）
        }
        scanAndLoadAll()
        updateProvidersFlow()
        val restored = providerManager.restoreLastProvider(getAvailableProviders(), context)
        if (!restored && providers.isNotEmpty() && providerManager.currentProvider == null) {
            providerManager.switchProvider(providers.values.first(), context, save = false)
        }
    }

    private fun scanAndLoadAll() {
        // 列出子目录：jvm 桥；此处通过一个轻量 expect 列目录。
        val dirs = PlatformSupport.listChildDirectories(modulesDir)
        for (dir in dirs) loadModuleIfExists(dir)
    }

    /** 导入 zip 模块包。 */
    fun importModule(zipPath: String): Boolean {
        lastLoadError = null
        return try {
            val tempDir = "$modulesDir/temp_${System.currentTimeMillis()}"
            if (!PlatformSupport.unzipTo(zipPath, tempDir)) {
                lastLoadError = "解压失败"
                return false
            }
            val manifestPath = "$tempDir/manifest.json"
            if (!PlatformSupport.exists(manifestPath)) {
                PlatformSupport.deleteRecursively(tempDir)
                lastLoadError = "模块包中缺少 manifest.json"
                return false
            }
            val manifestText = PlatformSupport.readTextFile(manifestPath) ?: ""
            val manifest = json.decodeFromString(ModuleManifest.serializer(), manifestText)
            val targetDir = "$modulesDir/${manifest.id}"
            if (PlatformSupport.exists(targetDir)) PlatformSupport.deleteRecursively(targetDir)
            if (!PlatformSupport.moveDir(tempDir, targetDir)) {
                lastLoadError = "移动临时目录失败"
                return false
            }
            val ok = loadModuleIfExists(targetDir)
            if (ok) updateProvidersFlow() else PlatformSupport.deleteRecursively(targetDir)
            ok
        } catch (e: Exception) {
            lastLoadError = "导入失败: ${e.message}"
            false
        }
    }

    private fun loadModuleIfExists(dir: String): Boolean {
        val manifestPath = "$dir/manifest.json"
        if (!PlatformSupport.exists(manifestPath)) return false
        return try {
            val manifest = json.decodeFromString(
                ModuleManifest.serializer(),
                PlatformSupport.readTextFile(manifestPath) ?: ""
            )
            val provider = ProviderFactory.create(manifest, dir)
            if (provider == null) {
                val reason = when (manifest.type) {
                    "jni" -> "缺少与当前平台 ABI 匹配的 native 库文件"
                    "binary" -> "二进制入口文件不存在或与当前平台 ABI 不匹配"
                    else -> "不支持的模块类型: ${manifest.type}"
                }
                lastLoadError = "Provider 创建失败 [${manifest.id}]: $reason"
                return false
            }
            if (!provider.isReady()) {
                val detail = extractLoadError(provider) ?: "未知原因"
                lastLoadError = "Provider 未就绪 [${manifest.id}]: $detail"
                return false
            }
            providers[manifest.id] = provider
            true
        } catch (e: Exception) {
            lastLoadError = "加载失败: ${e.message}"
            false
        }
    }

    private fun extractLoadError(provider: BackendProvider): String? {
        return runCatching {
            val m = provider.javaClass.getMethod("getLoadError")
            m.invoke(provider) as? String
        }.getOrNull()
    }

    fun getAvailableProviders(): List<BackendProvider> = providers.values.toList()
    fun getProvider(id: String): BackendProvider? = providers[id]
    fun getModuleDir(id: String): String = "$modulesDir/$id"

    fun deleteModule(id: String): Boolean {
        val dir = getModuleDir(id)
        if (!PlatformSupport.exists(dir)) return false
        val ok = PlatformSupport.deleteRecursively(dir)
        if (ok) { providers.remove(id); updateProvidersFlow() }
        return ok
    }
}