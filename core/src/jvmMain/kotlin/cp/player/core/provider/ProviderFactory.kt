package cp.player.core.provider

import cp.player.core.util.PlatformInfo
import cp.player.core.util.PlatformSupport

actual object ProviderFactory {

    actual fun create(manifest: ModuleManifest, moduleDir: String): BackendProvider? {
        // capabilities 经 knownCapabilities 过滤：未识别的能力名在 manifest 里出现时
        // 只丢弃该条目，不让整个包加载失败（前向兼容，见 ProviderCapability 的 KDoc）。
        val caps = manifest.knownCapabilities.ifEmpty { null }
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
                val binPath = PlatformSupport.resolveEntryPoint(moduleDir, manifest.entryPoint, manifest.supportedAbis)
                val isSharedLib = binPath.endsWith(".so") || binPath.endsWith(".dll") || binPath.endsWith(".dylib")
                if (PlatformInfo.isAndroid || isSharedLib) {
                    // Android 端完全不支持独立二进制（ProcessBuilder / exec 受 W^X 与 SELinux 拦截），
                    // 规范全面提升至运行 .so 原生动态库。
                    // 若解析出的入口为动态库（或在 Android 端解析到 .so），提升至 JniProvider 运行。
                    if (PlatformSupport.exists(binPath) && binPath.endsWith(".so")) {
                        println("[ProviderFactory] Android/Native 环境下将 binary 模块 [${manifest.id}] 规范提升至运行 .so: $binPath")
                        createJniProvider(manifest, binPath)
                    } else if (PlatformInfo.isAndroid) {
                        println("[ProviderFactory] Android 端不支持独立 binary 且未找到兼容架构的 .so 库: id=${manifest.id}, binPath=$binPath")
                        null
                    } else if (PlatformSupport.exists(binPath) && isSharedLib) {
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
            else -> null
        }
    }
}