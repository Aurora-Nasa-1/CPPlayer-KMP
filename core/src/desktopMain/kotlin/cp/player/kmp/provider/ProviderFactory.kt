package cp.player.kmp.provider

actual fun createJniProvider(manifest: ModuleManifest, soPath: String): BackendProvider? {
    return JniProvider(
        id = manifest.id,
        name = manifest.name,
        version = manifest.version,
        soPath = soPath,
        apiMap = manifest.apiMap,
        updateUrl = manifest.updateUrl,
        targetAppPackage = manifest.targetAppPackage
    )
}