package cp.player.kmp.provider

import cp.player.kmp.util.PlatformSupport

actual object ProviderFactory {

    actual fun create(manifest: ModuleManifest, moduleDir: String): BackendProvider? {
        return when (manifest.type) {
            "http" -> HttpProvider(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                baseUrl = manifest.entryPoint,
                apiMap = manifest.apiMap,
                updateUrl = manifest.updateUrl,
                targetAppPackage = manifest.targetAppPackage
            )
            "binary" -> {
                val binPath = PlatformSupport.resolveEntryPoint(moduleDir, manifest.entryPoint, manifest.supportedAbis)
                if (!PlatformSupport.exists(binPath)) null
                else BinaryProvider(manifest.id, manifest.name, manifest.version, binPath, manifest.apiMap, manifest.updateUrl, manifest.targetAppPackage)
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