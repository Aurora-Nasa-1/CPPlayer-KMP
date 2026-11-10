package cp.player.core.provider

actual object ProviderFactory {

    actual fun create(
        manifest: ModuleManifest,
        moduleDir: String,
        context: cp.player.core.util.PlatformContext?,
    ): BackendProvider? {
        if (!manifest.isLoadable) return null
        val root = java.io.File(moduleDir).canonicalFile
        val file = java.io.File(root, manifest.entryPoint).canonicalFile
        if (!file.toPath().startsWith(root.toPath()) || file == root ||
            !file.isFile || file.length() > manifest.maxEntryPointBytes) return null
        if (manifest.isNative) {
            if (manifest.nativeFactoryClass == null) {
                val header = ByteArray(4)
                if (file.inputStream().use { it.read(header) } != header.size) return null
                val pe = header[0] == 0x4d.toByte() && header[1] == 0x5a.toByte()
                val elf = header.contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
                val mach = header.map { it.toInt() and 255 } in listOf(
                    listOf(0xcf, 0xfa, 0xed, 0xfe), listOf(0xfe, 0xed, 0xfa, 0xcf),
                    listOf(0xca, 0xfe, 0xba, 0xbe), listOf(0xbe, 0xba, 0xfe, 0xca),
                )
                require(pe || elf || mach) { "Native SDK entryPoint must be a DLL, SO or dylib" }
            }
            return cp.player.core.provider.typed.installedNativeSdkProvider(manifest, root, context)
        }
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
