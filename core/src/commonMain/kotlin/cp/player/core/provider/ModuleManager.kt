package cp.player.core.provider

import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformSupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** SDK packages. Manual import and repository installation share one transaction. */
class ModuleManager(
    private val modulesDir: String,
    private val context: PlatformContext,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val providers = mutableMapOf<String, BackendProvider>()
    private val _providersFlow = MutableStateFlow<List<BackendProvider>>(emptyList())
    val providersFlow: StateFlow<List<BackendProvider>> = _providersFlow.asStateFlow()

    var lastLoadError: String? = null
        private set
    var lastExportError: String? = null
        private set
    var lastPendingUpdateError: String? = null
        private set

    private fun updateProvidersFlow() { _providersFlow.value = providers.values.toList() }

    private companion object {
        const val TEMP_PREFIX = ".repository-import-"
        val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,127}")
        fun validId(id: String) = ID_PATTERN.matches(id) && ".." !in id && id != "local"
        fun lastPathSegment(path: String): String = path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
    }

    fun init(providerManager: ProviderManager) {
        recoverRepositoryBackups()
        applyPendingRepositoryUpdates()
        for (directory in PlatformSupport.listChildDirectories(modulesDir)) {
            val name = lastPathSegment(directory)
            if (name.startsWith(".repository-") || name in providers) continue
            try {
                val provider = readProvider(directory)
                require(provider.id == name) { "Provider directory must match manifest.id" }
                providers[provider.id] = provider
            } catch (error: Exception) {
                lastLoadError = error.message ?: "Cannot load provider"
            }
        }
        updateProvidersFlow()
        val restored = providerManager.restoreLastProvider(getAvailableProviders(), context)
        if (!restored && providerManager.currentProvider == null) {
            providers.values.firstOrNull()?.let { providerManager.switchProvider(it, context, save = false) }
        }
    }

    data class ModuleImportSuccess(
        val provider: BackendProvider,
        val isUpdate: Boolean,
        val replacedProviderId: String? = null,
        val pendingRestart: Boolean = false,
    )

    fun importModule(zipPath: String): Boolean = importModuleDetails(zipPath) != null
    fun updateModule(zipPath: String, targetId: String): Boolean = updateModuleDetails(zipPath, targetId) != null
    fun importModuleDetails(zipPath: String): ModuleImportSuccess? = importZip(zipPath, null)
    fun updateModuleDetails(zipPath: String, targetId: String): ModuleImportSuccess? = importZip(zipPath, targetId)
    fun installRepositoryModule(zipPath: String, expectedId: String): ModuleImportSuccess? = importZip(zipPath, expectedId)

    @Synchronized
    private fun importZip(zipPath: String, expectedId: String?): ModuleImportSuccess? {
        lastLoadError = null
        val temporary = "$modulesDir/${TEMP_PREFIX}${System.nanoTime()}"
        var id: String? = null
        var replacement: BackendProvider? = null
        var backedUp = false
        var moved = false
        try {
            require(expectedId == null || validId(expectedId)) { "Invalid provider ID" }
            check(PlatformSupport.unzipTo(zipPath, temporary)) { "Cannot extract provider package" }
            // Descriptors capture a source or native runtime snapshot before replacing installed files.
            val provider = readProvider(temporary)
            replacement = provider
            id = provider.id
            require(expectedId == null || id == expectedId) { "Package ID does not match $expectedId" }
            require(providers.values.none {
                it.id != id && it.name.trim().equals(provider.name.trim(), ignoreCase = true)
            }) { "A different provider already uses this name; it will not be overwritten" }
            val target = "$modulesDir/$id"
            val backup = "$modulesDir/.repository-backup-$id"
            val previous = providers[id]
            val existed = PlatformSupport.exists(target)
            check(!PlatformSupport.exists(backup)) { "An interrupted installation needs recovery; restart the application" }
            removePendingRepositoryUpdate(id)
            if (existed) {
                check(PlatformSupport.moveDir(target, backup)) { "Cannot back up the existing module" }
                backedUp = true
            }
            check(PlatformSupport.moveDir(temporary, target)) { "Cannot move the new module into place" }
            moved = true
            if (backedUp) {
                val committed = "$modulesDir/.repository-old-$id-${System.nanoTime()}"
                check(PlatformSupport.moveDir(backup, committed)) { "Cannot commit the module replacement" }
                backedUp = false
                PlatformSupport.deleteRecursively(committed)
            }
            providers[id] = provider
            runCatching { previous?.close() }
            updateProvidersFlow()
            return ModuleImportSuccess(provider, existed, id.takeIf { existed })
        } catch (error: Exception) {
            runCatching { replacement?.close() }
            val target = id?.let { "$modulesDir/$it" }
            val removed = !moved || (target != null && PlatformSupport.deleteRecursively(target))
            val recovered = !backedUp || (removed &&
                PlatformSupport.moveDir("$modulesDir/.repository-backup-$id", checkNotNull(target)))
            lastLoadError = if (!removed || !recovered) {
                "Installation failed; recover the backup for $id before retrying"
            } else error.message ?: "Provider installation failed"
            return null
        } finally {
            PlatformSupport.deleteRecursively(temporary)
        }
    }

    private fun readProvider(directory: String): BackendProvider {
        val manifest = json.decodeFromString<ModuleManifest>(
            PlatformSupport.readTextFile("$directory/manifest.json") ?: error("Missing manifest.json"),
        )
        require(manifest.isLoadable) { manifest.loadabilityError.orEmpty() }
        val implementation = ProviderFactory.create(manifest, directory, context)
            ?: error("SDK entryPoint is missing, outside the package, or exceeds ${manifest.maxEntryPointBytes} bytes")
        check(implementation.isReady()) { implementation.loadError ?: "SDK provider is not ready" }
        return object : BackendProvider by implementation {
            override val updateUrl = manifest.updateUrl
            override val qualityOptions = manifest.qualityOptions
            override val loginMethods = emptyList<String>()
        }
    }

    @Serializable
    private data class PendingRepositoryUpdate(
        val id: String,
        val sha256: String,
        // Read old activation markers, but never allow them to delete a different provider.
        val replacedId: String? = null,
    )

    fun hasPendingRepositoryUpdate(id: String): Boolean =
        validId(id) && PlatformSupport.exists("$modulesDir/.repository-pending/$id.json")

    @Synchronized
    fun queueRepositoryUpdate(zipPath: String, id: String, replacedId: String? = null): Boolean {
        if (!validId(id) || (replacedId != null && replacedId != id)) return false
        val hash = PlatformSupport.sha256Hex(zipPath) ?: return false
        val directory = "$modulesDir/.repository-pending"
        val partial = "$directory/$id.zip.part"
        val metadata = "$directory/$id.json"
        if (!PlatformSupport.copyFile(zipPath, partial)) return false
        if (!PlatformSupport.moveFile(partial, "$directory/$id.zip")) return false
        if (!PlatformSupport.writeTextFile("$metadata.part", json.encodeToString(PendingRepositoryUpdate(id, hash)))) return false
        return PlatformSupport.moveFile("$metadata.part", metadata)
    }

    private fun removePendingRepositoryUpdate(id: String) {
        require(validId(id)) { "Invalid provider ID" }
        val directory = "$modulesDir/.repository-pending"
        // Remove the activation marker first; an orphaned ZIP is never applied on startup.
        check(!PlatformSupport.exists("$directory/$id.json") ||
            PlatformSupport.deleteRecursively("$directory/$id.json")) { "Cannot cancel the pending provider update" }
        PlatformSupport.deleteRecursively("$directory/$id.zip")
    }

    private fun applyPendingRepositoryUpdates() {
        val directory = "$modulesDir/.repository-pending"
        for (metadataPath in PlatformSupport.listChildFiles(directory).filter { it.endsWith(".json") }) {
            try {
                val pending = json.decodeFromString<PendingRepositoryUpdate>(
                    PlatformSupport.readTextFile(metadataPath) ?: error("Cannot read pending update"),
                )
                require(validId(pending.id) && lastPathSegment(metadataPath) == "${pending.id}.json")
                require(pending.replacedId == null || pending.replacedId == pending.id) { "Cross-ID updates are retired" }
                val archive = "$directory/${pending.id}.zip"
                require(PlatformSupport.sha256Hex(archive) == pending.sha256) { "Pending package hash mismatch" }
                // importZip removes the activation marker only after validating the complete new package.
                check(importZip(archive, pending.id) != null) { lastLoadError ?: "Pending update failed" }
            } catch (error: Exception) {
                lastPendingUpdateError = error.message ?: "Pending update failed"
                PlatformSupport.moveFile(metadataPath, "$metadataPath.failed")
            }
        }
    }

    private fun recoverRepositoryBackups() {
        for (directory in PlatformSupport.listChildDirectories(modulesDir)) {
            val name = lastPathSegment(directory)
            if (!name.startsWith(".repository-backup-")) continue
            val id = name.removePrefix(".repository-backup-")
            if (!validId(id)) continue
            val target = "$modulesDir/$id"
            if (PlatformSupport.exists(target) && !PlatformSupport.deleteRecursively(target)) continue
            if (!PlatformSupport.moveDir(directory, target)) lastPendingUpdateError = "Cannot recover provider $id"
        }
    }

    fun getAvailableProviders(): List<BackendProvider> = providers.values.toList()
    fun getProvider(id: String): BackendProvider? = providers[id]
    fun getModuleDir(id: String): String {
        require(validId(id)) { "Invalid provider ID" }
        return "$modulesDir/$id"
    }

    @Synchronized
    fun deleteModule(id: String): Boolean {
        if (!validId(id)) return false
        val directory = getModuleDir(id)
        try { removePendingRepositoryUpdate(id) } catch (error: Exception) {
            lastLoadError = error.message
            return false
        }
        if (!PlatformSupport.exists(directory)) return false
        if (!PlatformSupport.deleteRecursively(directory)) return false
        runCatching { providers.remove(id)?.close() }
        updateProvidersFlow()
        return true
    }

    fun exportModule(id: String, zipPath: String): Boolean {
        lastExportError = null
        if (!validId(id) || !PlatformSupport.exists(getModuleDir(id))) {
            lastExportError = "Module directory does not exist: $id"
            return false
        }
        return if (PlatformSupport.zipDirTo(getModuleDir(id), zipPath)) true else {
            lastExportError = "Cannot export module: $id"
            false
        }
    }
}
