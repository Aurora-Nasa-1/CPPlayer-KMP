package cp.player.core.provider

actual object ProviderFactory {

    actual fun create(manifest: ModuleManifest, moduleDir: String): BackendProvider? {
        if (!manifest.isLoadable) return null
        val root = java.io.File(moduleDir).canonicalFile
        val file = java.io.File(root, manifest.entryPoint).canonicalFile
        if (!file.toPath().startsWith(root.toPath()) || file == root ||
            !file.isFile || file.length() > 1024 * 1024) return null
        val source = file.readBytes().decodeToString(throwOnInvalidSequence = true)
        return SdkBackendProvider(
            id = manifest.id, name = manifest.name, version = manifest.version,
            type = ProviderType.SCRIPT,
            factory = { context -> cp.player.core.provider.typed.JsSdkProvider(source, context) },
            settingsSchema = manifest.settingsSchema,
            sdkAllowedOrigins = manifest.sdkOrigins.toSet(),
            capabilities = manifest.knownCapabilities,
        )
    }
}
