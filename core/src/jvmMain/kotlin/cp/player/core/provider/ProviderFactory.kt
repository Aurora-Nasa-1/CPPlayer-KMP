package cp.player.core.provider

import cp.player.core.util.PlatformSupport

actual object ProviderFactory {

    actual fun create(manifest: ModuleManifest, moduleDir: String): BackendProvider? {
        // capabilities 经 knownCapabilities 过滤：未识别的能力名在 manifest 里出现时
        // 只丢弃该条目，不让整个包加载失败（前向兼容，见 ProviderCapability 的 KDoc）。
        val caps = manifest.knownCapabilities.ifEmpty { null }
        val apiVersion = manifest.resolvedApiVersion
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
            )
            "binary" -> {
                val binPath = PlatformSupport.resolveEntryPoint(moduleDir, manifest.entryPoint, manifest.supportedAbis)
                if (!PlatformSupport.exists(binPath)) null
                else BinaryProvider(
                    manifest.id, manifest.name, manifest.version, binPath,
                    manifest.apiMap, manifest.updateUrl, manifest.targetAppPackage,
                    manifest.loginMethods, caps, apiVersion,
                )
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