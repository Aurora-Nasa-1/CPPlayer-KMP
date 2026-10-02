package cp.player.core.provider

actual fun createJniProvider(manifest: ModuleManifest, soPath: String): BackendProvider? {
    return JniProvider(
        id = manifest.id,
        name = manifest.name,
        version = manifest.version,
        soPath = soPath,
        apiMap = manifest.apiMap,
        updateUrl = manifest.updateUrl,
        targetAppPackage = manifest.targetAppPackage,
        loginMethods = manifest.loginMethods
    )
}