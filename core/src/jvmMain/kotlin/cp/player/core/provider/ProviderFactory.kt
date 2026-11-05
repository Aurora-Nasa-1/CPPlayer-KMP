package cp.player.core.provider

import cp.player.core.util.PlatformInfo
import cp.player.core.util.PlatformSupport

actual object ProviderFactory {

    actual fun create(manifest: ModuleManifest, moduleDir: String): BackendProvider? {
        // capabilities 经 knownCapabilities 过滤：未识别的能力名在 manifest 里出现时
        // 只丢弃该条目，不让整个包加载失败（前向兼容，见 ProviderCapability 的 KDoc）。
        val caps = manifest.capabilities?.let { manifest.knownCapabilities }
        val apiVersion = manifest.resolvedApiVersion
        val apiVersionTag = manifest.resolvedApiVersionTag
        return when (manifest.type) {
            "http" -> HttpProvider(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                baseUrl = manifest.entryPoint,
                apiMap = manifest.apiMap,
                updateUrl = manifest.updateUrl,
                targetAppPackage = manifest.targetAppPackage,
                loginMethods = manifest.loginMethods,
                capabilities = caps,
                apiVersion = apiVersion,
                apiVersionTag = apiVersionTag,
                contract = manifest.contract,
                settingsSchema = manifest.settingsSchema,
            )
            "binary" -> {
                if (PlatformInfo.isAndroid) {
                    // Android 端完全不支持独立二进制（ProcessBuilder / exec 受 W^X 与 SELinux 拦截），
                    // Android 端严格拒收 binary 模块，原生模块须使用动态库（type: "jni" 或 "jna"）。
                    println("[ProviderFactory] Android 端拒收 binary 模块: id=${manifest.id}")
                    return null
                }
                val binPath = PlatformSupport.resolveEntryPoint(moduleDir, manifest.entryPoint, manifest.supportedAbis)
                val isSharedLib = binPath.endsWith(".so") || binPath.endsWith(".dll") || binPath.endsWith(".dylib")
                if (isSharedLib) {
                    if (PlatformSupport.exists(binPath)) {
                        // 桌面端声明为 binary 但入口为动态库时同样提升至 JNI
                        createJniProvider(manifest, binPath)
                    } else {
                        null
                    }
                } else if (!PlatformSupport.exists(binPath)) {
                    null
                } else {
                    BinaryProvider(
                        id = manifest.id,
                        name = manifest.name,
                        version = manifest.version,
                        binaryPath = binPath,
                        apiMap = manifest.apiMap,
                        updateUrl = manifest.updateUrl,
                        targetAppPackage = manifest.targetAppPackage,
                        loginMethods = manifest.loginMethods,
                        capabilities = caps,
                        apiVersion = apiVersion,
                        apiVersionTag = apiVersionTag,
                        contract = manifest.contract,
                        settingsSchema = manifest.settingsSchema,
                    )
                }
            }
            "jni" -> {
                val soPath = PlatformSupport.resolveEntryPoint(moduleDir, manifest.entryPoint, manifest.supportedAbis)
                if (!PlatformSupport.exists(soPath)) null
                else createJniProvider(manifest, soPath)
            }
            "jna" -> {
                val soPath = PlatformSupport.resolveEntryPoint(moduleDir, manifest.entryPoint, manifest.supportedAbis)
                if (!PlatformSupport.exists(soPath)) null
                else JnaProvider(
                    id = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    soPath = soPath,
                    apiMap = manifest.apiMap,
                    updateUrl = manifest.updateUrl,
                    targetAppPackage = manifest.targetAppPackage,
                    loginMethods = manifest.loginMethods,
                    capabilities = caps,
                    apiVersion = apiVersion,
                    apiVersionTag = apiVersionTag,
                    contract = manifest.contract,
                    settingsSchema = manifest.settingsSchema,
                )
            }
            "js", "script" -> {
                val scriptPath = PlatformSupport.resolveEntryPoint(moduleDir, manifest.entryPoint, manifest.supportedAbis)
                if (!PlatformSupport.exists(scriptPath)) null
                else JsBackendProvider(
                    id = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    scriptPath = scriptPath,
                    moduleDir = moduleDir,
                    apiMap = manifest.apiMap,
                    updateUrl = manifest.updateUrl,
                    targetAppPackage = manifest.targetAppPackage,
                    loginMethods = manifest.loginMethods,
                    capabilities = caps,
                    apiVersion = apiVersion,
                    apiVersionTag = apiVersionTag,
                    contract = manifest.contract,
                    settingsSchema = manifest.settingsSchema,
                )
            }
            else -> null
        }
    }
}
