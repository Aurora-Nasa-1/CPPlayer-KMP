package cp.player.core.provider

import cp.player.core.util.PlatformSupport

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
            // 内置音源：实现在 App 内，manifest 只负责声明与开关（不需要任何二进制）
            "internal", "kotlin", "builtin" -> createInternalProvider(manifest)
            else -> null
        }
    }

    /**
     * 按模块 id 返回内置 Provider。
     *
     * 新增内置音源时在这里加分支即可：manifest 里 `"type": "internal"`，
     * 用户导入只含 manifest.json 的 zip 就能启用，跨平台无需分发二进制。
     */
    private fun createInternalProvider(manifest: ModuleManifest): BackendProvider? = when (manifest.id) {
        MiguProvider.DEFAULT_ID, "migu-music" -> MiguProvider(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            apiMap = manifest.apiMap,
            updateUrl = manifest.updateUrl,
            targetAppPackage = manifest.targetAppPackage,
        )
        else -> null
    }
}