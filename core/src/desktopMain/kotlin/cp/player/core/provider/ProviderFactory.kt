package cp.player.core.provider

actual fun createJniProvider(manifest: ModuleManifest, soPath: String): BackendProvider? {
    return DesktopJniProvider(
        id = manifest.id,
        name = manifest.name,
        version = manifest.version,
        soPath = soPath,
        apiMap = manifest.apiMap,
        updateUrl = manifest.updateUrl,
        targetAppPackage = manifest.targetAppPackage,
        loginMethods = manifest.loginMethods,
        capabilities = manifest.capabilities?.let { manifest.knownCapabilities },
        apiVersion = manifest.resolvedApiVersion,
        apiVersionTag = manifest.resolvedApiVersionTag,
        contract = manifest.contract,
        settingsSchema = manifest.settingsSchema,
    )
}
