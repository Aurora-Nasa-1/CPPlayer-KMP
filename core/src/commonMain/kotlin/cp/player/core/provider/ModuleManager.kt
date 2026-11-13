package cp.player.core.provider

import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** SDK packages. Manual import and repository installation share one transaction. */
class ModuleManager(
    private val modulesDir: String,
    private val context: PlatformContext,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val providers = VersionedProviderRegistry<BackendProvider> { it.id }
    private val _providersFlow = MutableStateFlow<List<BackendProvider>>(emptyList())
    val providersFlow: StateFlow<List<BackendProvider>> = _providersFlow.asStateFlow()
    private val providersFlowLock = Any()

    var lastLoadError: String? = null
        private set
    var lastExportError: String? = null
        private set
    var lastPendingUpdateError: String? = null
        private set

    private fun updateProvidersFlow() {
        synchronized(providersFlowLock) {
            _providersFlow.value = providers.all()
        }
    }

    private fun recordLoadErrorIfCurrent(id: String, generation: Long, message: String) {
        synchronized(this) {
            if (providers.generation(id) == generation) lastLoadError = message
        }
    }

    private companion object {
        const val TEMP_PREFIX = ".repository-import-"
        val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,127}")
        fun validId(id: String) = ID_PATTERN.matches(id) && ".." !in id && id != "local"
        fun lastPathSegment(path: String): String = path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
    }

    @Synchronized
    fun init(providerManager: ProviderManager) {
        recoverRepositoryBackups()
        applyPendingRepositoryUpdates()
        val allChildDirs = PlatformSupport.listChildDirectories(modulesDir)
        val loadedProviderIds = providers.ids()
        val validDirs = allChildDirs.filter { dir ->
            val name = lastPathSegment(dir)
            !name.startsWith(".repository-") && name !in loadedProviderIds
        }

        if (validDirs.isNotEmpty()) {
            val lastActiveId = providerManager.getLastActiveProviderId()
            // 快速定位目标激活音源目录：优先上次活跃音源，否则取第一个有效模块
            val targetDir = (if (lastActiveId != null) validDirs.find { lastPathSegment(it) == lastActiveId } else null)
                ?: validDirs.firstOrNull()

            // 1. 【极速就绪】：启动主链路只立刻加载当前需要使用的这 1 个 Provider 并立即激活
            if (targetDir != null) {
                val name = lastPathSegment(targetDir)
                try {
                    val activeProvider = readProvider(targetDir, expectedDirectoryId = name)
                    providers.put(activeProvider)
                    updateProvidersFlow()
                    providerManager.switchProvider(activeProvider, context, save = false)
                } catch (error: Exception) {
                    lastLoadError = error.message ?: "Cannot load provider"
                }
            }

            // 2. 【多核后台并发】：其余未激活的模块完全不阻塞启动，放至后台多核协程并行加载
            val remainingDirs = validDirs.filter { it != targetDir }
            if (remainingDirs.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    val remainingLoaded = remainingDirs.map { directory ->
                        async {
                            val name = lastPathSegment(directory)
                            val generation = providers.generation(name)
                            if (!PlatformSupport.exists(directory)) return@async null
                            try {
                                val provider = readProvider(directory, expectedDirectoryId = name)
                                provider to generation
                            } catch (e: Exception) {
                                recordLoadErrorIfCurrent(name, generation, e.message ?: "Cannot load provider")
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()

                    val staleProviders = remainingLoaded.filterNot { (provider, generation) ->
                        providers.putIfUnchanged(provider, generation)
                    }
                    staleProviders.forEach { (provider, _) -> runCatching(provider::close) }
                    updateProvidersFlow()
                }
            }
        } else {
            updateProvidersFlow()
        }

        // 保底：若目标音源启动失败且没有成功激活，回退尝试 restoreLastProvider
        if (providerManager.currentProvider == null) {
            val restored = providerManager.restoreLastProvider(getAvailableProviders(), context)
            if (!restored) {
                getAvailableProviders().firstOrNull()?.let { providerManager.switchProvider(it, context, save = false) }
            }
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
            require(providers.all().none {
                it.id != id && it.name.trim().equals(provider.name.trim(), ignoreCase = true)
            }) { "A different provider already uses this name; it will not be overwritten" }
            val target = "$modulesDir/$id"
            val backup = "$modulesDir/.repository-backup-$id"
            val previous = providers.get(id)
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
            providers.put(provider)
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

    private fun readProvider(directory: String, expectedDirectoryId: String? = null): BackendProvider {
        val manifest = json.decodeFromString<ModuleManifest>(
            PlatformSupport.readTextFile("$directory/manifest.json") ?: error("Missing manifest.json"),
        )
        require(manifest.isLoadable) { manifest.loadabilityError.orEmpty() }
        val implementation = ProviderFactory.create(manifest, directory, context)
            ?: error("SDK entryPoint is missing, outside the package, or exceeds ${manifest.maxEntryPointBytes} bytes")
        try {
            check(implementation.isReady()) { implementation.loadError ?: "SDK provider is not ready" }
            val provider = object : BackendProvider by implementation {
                override val updateUrl = manifest.updateUrl
                override val qualityOptions = manifest.qualityOptions
                override val loginMethods = emptyList<String>()
            }
            if (expectedDirectoryId != null) {
                require(provider.id == expectedDirectoryId) { "Provider directory must match manifest.id" }
            }
            return provider
        } catch (error: Throwable) {
            runCatching { implementation.close() }
            throw error
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

    fun getAvailableProviders(): List<BackendProvider> = providers.all()
    fun getProvider(id: String): BackendProvider? = providers.get(id)
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
        if (!PlatformSupport.exists(directory)) {
            // A directory can disappear outside this manager while a scan is reading it.
            // remove() also advances the generation so that scan cannot resurrect the provider.
            runCatching { providers.remove(id)?.close() }
            updateProvidersFlow()
            return false
        }
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
