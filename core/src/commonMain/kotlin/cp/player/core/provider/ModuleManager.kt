package cp.player.core.provider

import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformSupport
import cp.player.core.util.PlatformInfo
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

    /** 最近一次导出失败的错误信息（供 UI 展示） */
    var lastExportError: String? = null
        private set

    private fun updateProvidersFlow() { _providersFlow.value = providers.values.toList() }

    private companion object {
        /** 导入时的暂存目录前缀；扫描模块目录时据此跳过并清理失败残留。 */
        const val TEMP_PREFIX = "temp_"

        /** 取路径最后一段（兼容 `/` 与 `\` 两种分隔符）。 */
        fun lastPathSegment(path: String): String {
            val cut = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
            return if (cut >= 0) path.substring(cut + 1) else path
        }
    }

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
        for (dir in dirs) {
            // ⚠️ 跳过导入中途失败残留的暂存目录：它可能已解压出 manifest.json，
            // 被当作真实模块加载会以**相同的 id** 覆盖真模块（providers[id] = provider），
            // 而 getModuleDir(id) 返回的却是正式目录 —— 表现为「幽灵音源」且删不掉。
            // 顺手清掉，避免无限累积。
            if (lastPathSegment(dir).startsWith(TEMP_PREFIX)) {
                PlatformSupport.deleteRecursively(dir)
                continue
            }
            loadModuleIfExists(dir)
        }
    }

    data class ModuleImportSuccess(
        val provider: BackendProvider,
        val isUpdate: Boolean,
        val replacedProviderId: String? = null,
    )

    /** 导入 zip 模块包。 */
    fun importModule(zipPath: String): Boolean = importZip(zipPath, expectedId = null) != null

    /**
     * 用 zip 模块包**更新**已有模块：包内 manifest.id 必须与 [targetId] 一致，
     * 否则拒绝 —— 防止把 A 模块的包覆盖到 B 的目录上。
     */
    fun updateModule(zipPath: String, targetId: String): Boolean = importZip(zipPath, expectedId = targetId) != null

    /** 导入 zip 模块包，返回具体导入结果（包含准确的 Provider 实例、是否更新、被替换的旧 ID）。 */
    fun importModuleDetails(zipPath: String): ModuleImportSuccess? = importZip(zipPath, expectedId = null)

    /** 更新已有模块，返回具体更新结果。 */
    fun updateModuleDetails(zipPath: String, targetId: String): ModuleImportSuccess? = importZip(zipPath, expectedId = targetId)

    private fun importZip(zipPath: String, expectedId: String?): ModuleImportSuccess? {
        lastLoadError = null
        // tempDir 提到 try 外：catch 分支必须能清理它 —— 解压中途失败（zip-slip / 磁盘满）
        // 会留下「已解压出 manifest.json」的半成品目录，被下次 scanAndLoadAll 当成真实模块。
        val tempDir = "$modulesDir/$TEMP_PREFIX${System.currentTimeMillis()}"
        return try {
            if (!PlatformSupport.unzipTo(zipPath, tempDir)) {
                PlatformSupport.deleteRecursively(tempDir)
                lastLoadError = "解压失败"
                return null
            }
            val manifestPath = "$tempDir/manifest.json"
            if (!PlatformSupport.exists(manifestPath)) {
                PlatformSupport.deleteRecursively(tempDir)
                lastLoadError = "模块包中缺少 manifest.json"
                return null
            }
            val manifestText = PlatformSupport.readTextFile(manifestPath) ?: ""
            val manifest = json.decodeFromString(ModuleManifest.serializer(), manifestText)
            // 完整性校验（CODE_REVIEW K5）：manifest 声明了 sha256 时，对原始 zip 字节
            // 重算比对。jni/binary 模块会执行原生代码，损坏/被篡改的包不能静默加载。
            // 字段缺省 = 旧格式包，跳过校验（向后兼容）。
            manifest.sha256?.let { expected ->
                val actual = PlatformSupport.sha256Hex(zipPath)
                if (actual == null || !actual.equals(expected, ignoreCase = true)) {
                    PlatformSupport.deleteRecursively(tempDir)
                    lastLoadError = if (actual == null) "无法读取模块包以校验完整性" else "模块包完整性校验失败（sha256 不匹配）"
                    return null
                }
            }

            // 自动检查同 ID 或同名模块：优先按 ID，其次按音源显示名称匹配已安装模块
            val existingById = providers[manifest.id]
            val existingByName = providers.values.find {
                it.name.trim().equals(manifest.name.trim(), ignoreCase = true)
            }
            val targetExisting = if (expectedId != null) {
                providers[expectedId] ?: existingById ?: existingByName
            } else {
                existingById ?: existingByName
            }

            if (expectedId != null && manifest.id != expectedId && targetExisting?.id != expectedId) {
                PlatformSupport.deleteRecursively(tempDir)
                lastLoadError = "模块包不匹配：包内 id 为 ${manifest.id}，无法更新 $expectedId"
                return null
            }

            val isUpdate = targetExisting != null
            val replacedId = targetExisting?.id

            // API 版本闸门：包声明的最低宿主版本高于本宿主 ⇒ 拒绝，且必须**明确说原因**。
            // 放行的话会以旧代码执行新契约，失败点落在插件内部，用户只会看到「这个音源坏了」。
            // 判据只用 minHostApiVersion（不是 apiVersion）—— 见 ModuleManifest 的 KDoc。
            if (!manifest.isLoadable) {
                PlatformSupport.deleteRecursively(tempDir)
                lastLoadError = "此音源需要更高版本的应用" +
                    "（需要 API ${manifest.resolvedMinHostApiVersion}，当前支持 ${HOST_PROVIDER_API_VERSION}）"
                return null
            }

            // 若更新已有模块，先停止旧 Provider 的服务进程以释放文件锁（尤其是 Windows 上的 dll / exe）
            if (targetExisting != null) {
                runCatching { targetExisting.stopServer() }
            }

            // 若音源重名但 ID 变更（例如 kugou-music-rs 更新为 kugou），清理旧 ID 目录及缓存注册
            if (targetExisting != null && targetExisting.id != manifest.id) {
                val oldDir = "$modulesDir/${targetExisting.id}"
                if (PlatformSupport.exists(oldDir)) {
                    PlatformSupport.deleteRecursively(oldDir)
                }
                providers.remove(targetExisting.id)
            }

            val targetDir = "$modulesDir/${manifest.id}"
            // 旧目录清理失败（典型：Windows 上活跃 jni 模块的 dll 被占用）必须中止，
            // 否则 moveDir 语义未定义、旧文件半新半旧。
            if (PlatformSupport.exists(targetDir) && !PlatformSupport.deleteRecursively(targetDir)) {
                PlatformSupport.deleteRecursively(tempDir)
                lastLoadError = "无法清理旧版本目录（模块文件可能正被占用）"
                return null
            }
            if (!PlatformSupport.moveDir(tempDir, targetDir)) {
                PlatformSupport.deleteRecursively(tempDir)
                lastLoadError = "移动临时目录失败"
                return null
            }
            val ok = loadModuleIfExists(targetDir)
            if (!ok) {
                PlatformSupport.deleteRecursively(targetDir)
                return null
            }
            updateProvidersFlow()
            val loadedProvider = providers[manifest.id]
            if (loadedProvider == null) {
                lastLoadError = "模块加载成功但未登记 Provider [${manifest.id}]"
                return null
            }
            ModuleImportSuccess(
                provider = loadedProvider,
                isUpdate = isUpdate,
                replacedProviderId = if (isUpdate) replacedId else null,
            )
        } catch (e: Exception) {
            // 任何异常都不能把半成品暂存目录留在 modules 目录里（同上）。
            PlatformSupport.deleteRecursively(tempDir)
            lastLoadError = "导入失败: ${e.message}"
            null
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
            // 与导入路径同一道闸门。为什么必须在**加载**处也判一次：应用降级安装
            // （用户回退到旧版本）时，modules 目录里已经躺着新契约的包，从没走过 importZip。
            if (!manifest.isLoadable) {
                lastLoadError = "此音源需要更高版本的应用" +
                    "（需要 API ${manifest.resolvedMinHostApiVersion}，当前支持 ${HOST_PROVIDER_API_VERSION}）"
                return false
            }
            val provider = ProviderFactory.create(manifest, dir)
            if (provider == null) {
                val reason = when (manifest.type) {
                    "jni" -> "缺少与当前平台 ABI 匹配的 native 库文件"
                    "binary" -> if (PlatformInfo.isAndroid) {
                        "Android 端不支持独立二进制可执行文件 (binary)，需规范提升至与当前架构匹配的 .so 动态库"
                    } else {
                        "二进制入口文件不存在或与当前平台 ABI 不匹配"
                    }
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

    /**
     * 把已安装模块 [id] 的目录打包为 zip 写入 [zipPath]（已存在则覆盖）。
     * 失败原因见 [lastExportError]。
     */
    fun exportModule(id: String, zipPath: String): Boolean {
        lastExportError = null
        val dir = getModuleDir(id)
        if (!PlatformSupport.exists(dir)) {
            lastExportError = "模块目录不存在: $id"
            return false
        }
        return if (PlatformSupport.zipDirTo(dir, zipPath)) true
        else {
            lastExportError = "打包 zip 失败: $id"
            false
        }
    }
}