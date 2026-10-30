package cp.player.core.pluginrepo

import cp.player.core.util.PlatformContext
import cp.player.core.util.SettingsStorage
import cp.player.core.util.currentTimeMillis
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface PluginRepositoryIO {
    suspend fun fetchCatalog(url: String): String
    suspend fun cachedCatalog(url: String): String?
    suspend fun cacheCatalog(url: String, text: String)
    suspend fun preparePackage(
        plugin: RepositoryPlugin,
        artifact: PluginArtifact,
        onProgress: (PluginInstallPhase, Long) -> Unit,
    ): String
    suspend fun discardPackage(path: String)
    fun close() {}
}

expect fun createPluginRepositoryIO(context: PlatformContext): PluginRepositoryIO
expect fun currentPluginTarget(): PluginTarget

/** A deliberately small, opt-in JSON catalog service. It never runs an unattended install. */
class PluginRepositoryService(
    private val settings: SettingsStorage,
    private val io: PluginRepositoryIO,
    private val target: PluginTarget,
    private val installedPlugins: suspend () -> List<InstalledRepositoryPlugin>,
    private val pendingUpdateExists: (String) -> Boolean = { true },
    private val defaultSources: List<PluginRepositorySource> = emptyList(),
    private val installPackage: suspend (RepositoryPlugin, String) -> PluginInstallOutcome,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val catalogs = mutableMapOf<String, PluginCatalog>()
    private val refreshMutex = Mutex()
    private val installMutex = Mutex()
    private var refreshJob: Job? = null
    private var installJob: Job? = null
    private var installed = emptyList<InstalledRepositoryPlugin>()
    private var managed = read<List<ManagedPluginInstall>>("installed") ?: emptyList()
    private val _state = MutableStateFlow(
        PluginRepositoryState(
            sources = read<List<PluginRepositorySource>>("sources") ?: defaultSources,
            networkEnabled = settings.getString("network") == "true",
            autoCheck = settings.getString("auto_check") == "true",
            lastCheckedAt = settings.getString("last_checked")?.toLongOrNull(),
        ),
    )
    val state = _state.asStateFlow()

    private inline fun <reified T> read(key: String): T? =
        settings.getString(key)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    fun setNetworkEnabled(enabled: Boolean) {
        settings.putString("network", enabled.toString())
        _state.update { it.copy(networkEnabled = enabled) }
        if (!enabled) {
            refreshJob?.cancel()
            if (_state.value.task?.phase != PluginInstallPhase.INSTALLING) installJob?.cancel()
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        settings.putString("auto_check", enabled.toString())
        _state.update { it.copy(autoCheck = enabled) }
    }

    /** A later manual import is no longer attributable to the repository, even at the same version. */
    fun forgetInstall(key: String) {
        if (managed.none { it.key == key }) return
        managed = managed.filterNot { it.key == key }
        settings.putString("installed", json.encodeToString(managed))
        rebuildEntries()
    }

    fun addSource(url: String) {
        val normalized = requireRepositoryHttpsUrl(url.trim())
        require(_state.value.sources.none { it.url == normalized }) { "Repository already added" }
        require(_state.value.sources.size < 16) { "At most 16 repositories are supported" }
        val source = PluginRepositorySource(normalized, Url(normalized).host)
        saveSources(_state.value.sources + source)
    }

    fun setSourceEnabled(url: String, enabled: Boolean) {
        require(!isInstallingFrom(url)) { "Wait for this repository's installation to finish" }
        saveSources(_state.value.sources.map { if (it.url == url) it.copy(enabled = enabled) else it })
        rebuildEntries()
    }

    fun removeSource(url: String) {
        require(!isInstallingFrom(url)) { "Wait for this repository's installation to finish" }
        saveSources(_state.value.sources.filterNot { it.url == url })
        catalogs.remove(url)
        _state.update { it.copy(sourceErrors = it.sourceErrors - url) }
        rebuildEntries()
    }

    private fun saveSources(sources: List<PluginRepositorySource>) {
        settings.putString("sources", json.encodeToString(sources))
        _state.update { it.copy(sources = sources) }
    }

    suspend fun loadCached() {
        for (source in _state.value.sources) {
            if (source.url !in catalogs) {
                val text = io.cachedCatalog(source.url) ?: continue
                runCatching { parseCatalog(text) }.getOrNull()?.let {
                    if (source.url !in catalogs && _state.value.sources.any { s -> s.url == source.url }) {
                        catalogs[source.url] = it
                    }
                }
            }
        }
        syncInstalled()
    }

    suspend fun syncInstalled() {
        installed = installedPlugins()
        val completed = managed.map { record ->
            if (record.pendingRestart && (installed.any { it.key == record.key && it.version == record.version } ||
                    !pendingUpdateExists(record.key.substringAfter(':')))) {
                record.copy(pendingRestart = false)
            } else record
        }
        if (completed != managed) {
            managed = completed
            settings.putString("installed", json.encodeToString(managed))
        }
        rebuildEntries()
    }

    private fun rebuildEntries() {
        val entries = _state.value.sources.filter { it.enabled }.flatMap { source ->
            catalogs[source.url]?.plugins.orEmpty().map { plugin ->
                val (artifact, error) = plugin.compatibility(target)
                PluginCatalogEntry(
                    source, plugin, artifact, error,
                    installed.find { it.key == plugin.key },
                    managed.find { it.key == plugin.key },
                )
            }
        }
        _state.update { it.copy(entries = entries, installedPlugins = installed, managedInstalls = managed) }
    }

    suspend fun checkIfDue() {
        val current = _state.value
        if (!current.networkEnabled || !current.autoCheck || current.sources.none { it.enabled }) return
        val elapsed = currentTimeMillis() - (current.lastCheckedAt ?: 0L)
        if (elapsed < CHECK_INTERVAL_MS) return
        refresh()
    }

    suspend fun refresh() = coroutineScope {
        require(_state.value.networkEnabled) { "Repository networking is disabled" }
        if (!refreshMutex.tryLock()) return@coroutineScope
        refreshJob = currentCoroutineContext()[Job]
        _state.update { it.copy(refreshing = true) }
        try {
            for (source in _state.value.sources.filter { it.enabled }) {
                currentCoroutineContext().ensureActive()
                try {
                    val text = io.fetchCatalog(source.url)
                    val catalog = parseCatalog(text)
                    if (_state.value.sources.none { it.url == source.url && it.enabled }) continue
                    io.cacheCatalog(source.url, text)
                    catalogs[source.url] = catalog
                    saveSources(_state.value.sources.map {
                        if (it.url == source.url) it.copy(name = catalog.name) else it
                    })
                    _state.update { it.copy(sourceErrors = it.sourceErrors - source.url) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _state.update {
                        it.copy(sourceErrors = it.sourceErrors + (source.url to (error.message ?: "Request failed")))
                    }
                }
            }
            val checkedAt = currentTimeMillis()
            // A failed check is still throttled; users can explicitly retry from the toolbar.
            settings.putString("last_checked", checkedAt.toString())
            _state.update { it.copy(lastCheckedAt = checkedAt) }
            syncInstalled()
        } finally {
            refreshJob = null
            _state.update { it.copy(refreshing = false) }
            refreshMutex.unlock()
        }
    }

    suspend fun install(entry: PluginCatalogEntry, replaceUnmanaged: Boolean = false) = coroutineScope {
        require(_state.value.networkEnabled) { "Repository networking is disabled" }
        require(installMutex.tryLock()) { "Another plugin is being installed" }
        var path: String? = null
        installJob = currentCoroutineContext()[Job]
        try {
            require(_state.value.sources.any { it.url == entry.source.url && it.enabled }) { "Repository is disabled" }
            require(entry.incompatibility == null && entry.artifact != null) { entry.incompatibility ?: "No compatible package" }
            require(_state.value.entries.any {
                it.source.url == entry.source.url && it.plugin == entry.plugin
            }) { "Catalog changed; refresh and try again" }
            syncInstalled()
            val actual = installed.find { it.key == entry.plugin.key }
            val origin = managed.find { it.key == entry.plugin.key }
            require(origin?.pendingRestart != true) { "Restart the application to finish the pending update" }
            if (actual != null) {
                require(!actual.bundled) { "Built-in plugins are updated with the application" }
                require(origin == null || origin.sourceUrl == entry.source.url) {
                    "This plugin belongs to another repository; remove it before changing its source"
                }
                require(replaceUnmanaged || origin != null && origin.version == actual.version) {
                    "Confirm replacement of the locally imported plugin"
                }
                require(
                    if (entry.plugin.kind == RepositoryPluginKind.LYRICS) {
                        (entry.plugin.versionCode ?: 0) > (actual.versionCode ?: origin?.versionCode ?: 0)
                    } else {
                        PluginVersion.compare(entry.plugin.version, actual.version)?.let { it > 0 } == true
                    },
                ) { "Only a newer version can replace an installed plugin" }
            }
            val artifact = entry.artifact
            _state.update {
                it.copy(task = PluginInstallTask(entry.plugin.key, entry.source.url, PluginInstallPhase.DOWNLOADING, totalBytes = artifact.bytes))
            }
            val preparedPath = io.preparePackage(entry.plugin, artifact) { phase, downloaded ->
                _state.update { it.copy(task = it.task?.copy(phase = phase, downloadedBytes = downloaded)) }
            }
            path = preparedPath
            currentCoroutineContext().ensureActive()
            require(_state.value.networkEnabled) { "Repository networking is disabled" }
            _state.update { it.copy(task = it.task?.copy(phase = PluginInstallPhase.INSTALLING)) }
            // Do not abandon a local commit or its provenance write halfway through cancellation.
            withContext(NonCancellable) {
                val outcome = installPackage(entry.plugin, preparedPath)
                managed = managed.filterNot { it.key == entry.plugin.key } + ManagedPluginInstall(
                    entry.plugin.key, entry.source.url, entry.plugin.version, entry.plugin.versionCode, artifact.sha256,
                    pendingRestart = outcome.restartRequired,
                )
                settings.putString("installed", json.encodeToString(managed))
                syncInstalled()
                _state.update { it.copy(task = it.task?.copy(
                    phase = if (outcome.restartRequired) PluginInstallPhase.RESTART_REQUIRED else PluginInstallPhase.COMPLETE,
                    message = outcome.warning,
                )) }
            }
        } catch (cancelled: CancellationException) {
            _state.update { it.copy(task = it.task?.copy(phase = PluginInstallPhase.FAILED, message = "Download cancelled")) }
            throw cancelled
        } catch (error: Exception) {
            _state.update {
                it.copy(task = PluginInstallTask(
                    entry.plugin.key, entry.source.url, PluginInstallPhase.FAILED,
                    message = error.message ?: "Installation failed",
                ))
            }
            throw error
        } finally {
            withContext(NonCancellable) {
                runCatching { path?.let { io.discardPackage(it) } }
            }
            installJob = null
            installMutex.unlock()
        }
    }

    fun cancelDownload() {
        if (_state.value.task?.phase in setOf(PluginInstallPhase.DOWNLOADING, PluginInstallPhase.VERIFYING)) {
            installJob?.cancel()
        }
    }

    private fun isInstallingFrom(url: String): Boolean =
        _state.value.task?.let {
            it.sourceUrl == url && it.phase !in setOf(
                PluginInstallPhase.COMPLETE, PluginInstallPhase.RESTART_REQUIRED, PluginInstallPhase.FAILED,
            )
        } == true

    fun close() {
        refreshJob?.cancel()
        cancelDownload()
        io.close()
    }

    companion object {
        const val DEFAULT_CATALOG_URL =
            "https://raw.githubusercontent.com/Aurora-Nasa-1/CPPlayer-Plugins/main/index.json"
        const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        const val MAX_PACKAGE_BYTES = 256L * 1024L * 1024L
        const val MAX_CATALOG_BYTES = 5 * 1024 * 1024
        private val catalogJson = Json { ignoreUnknownKeys = true }
        private val idPattern = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")
        private val reservedNames = setOf("con", "prn", "aux", "nul") +
            (1..9).flatMap { listOf("com$it", "lpt$it") }

        fun parseCatalog(text: String): PluginCatalog {
            require(text.encodeToByteArray().size <= MAX_CATALOG_BYTES) { "Catalog is too large" }
            val catalog = catalogJson.decodeFromString<PluginCatalog>(text)
            require(catalog.schemaVersion == 1) { "Unsupported repository schema ${catalog.schemaVersion}" }
            require(catalog.name.isNotBlank() && catalog.name.length <= 120) { "Invalid repository name" }
            require(catalog.plugins.size <= 1000) { "Too many catalog entries" }
            require(catalog.plugins.map { it.key }.distinct().size == catalog.plugins.size) { "Duplicate plugin IDs" }
            for (plugin in catalog.plugins) {
                require(idPattern.matches(plugin.id) && ".." !in plugin.id &&
                    plugin.id.substringBefore('.') !in reservedNames && !plugin.id.endsWith('.')
                ) { "Unsafe plugin ID: ${plugin.id}" }
                require(plugin.name.isNotBlank() && plugin.name.length <= 120) { "Invalid plugin name" }
                require(plugin.description.length <= 8192 && plugin.changelog.length <= 16384) { "Plugin text is too long" }
                require(PluginVersion.isValid(plugin.version)) { "Invalid SemVer: ${plugin.version}" }
                if (plugin.kind == RepositoryPluginKind.LYRICS) {
                    require((plugin.versionCode ?: 0) > 0) { "Lyrics plugins require a positive versionCode" }
                }
                require(plugin.artifacts.isNotEmpty() && plugin.artifacts.size <= 32) { "Invalid package list" }
                for (artifact in plugin.artifacts) {
                    require(artifact.platform in setOf("any", "windows", "linux", "macos", "android")) { "Unknown platform" }
                    require(artifact.arch in setOf("any", "x64", "arm64", "armv7")) { "Unknown architecture" }
                    requireRepositoryHttpsUrl(artifact.url)
                    require(Regex("^[0-9a-f]{64}$").matches(artifact.sha256)) { "Invalid package SHA-256" }
                    require(artifact.bytes in 1..MAX_PACKAGE_BYTES) { "Invalid package size" }
                }
            }
            return catalog
        }
    }
}

fun requireRepositoryHttpsUrl(value: String): String {
    val url = Url(value)
    require(url.protocol.name == "https" && url.host.isNotBlank() &&
        url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty()
    ) { "Use an HTTPS URL without credentials or a fragment" }
    return url.toString()
}
