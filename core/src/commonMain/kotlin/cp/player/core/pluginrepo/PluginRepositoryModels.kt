package cp.player.core.pluginrepo

import cp.player.core.lyricsplugin.HostApiRegistry
import cp.player.core.provider.HOST_PROVIDER_API_VERSION
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RepositoryPluginKind {
    @SerialName("provider") PROVIDER,
    @SerialName("lyrics") LYRICS,
}

@Serializable
data class PluginCatalog(
    val schemaVersion: Int = 1,
    val name: String,
    val plugins: List<RepositoryPlugin> = emptyList(),
)

@Serializable
data class RepositoryPlugin(
    val id: String,
    val kind: RepositoryPluginKind,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val homepage: String? = null,
    val changelog: String = "",
    val versionCode: Int? = null,
    val apiVersion: Int = 1,
    val minHostApiVersion: Int = 1,
    val artifacts: List<PluginArtifact>,
) {
    val key: String get() = "${kind.name.lowercase()}:$id"
}

@Serializable
data class PluginArtifact(
    val platform: String,
    val arch: String = "any",
    val runtime: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
)

@Serializable
data class PluginRepositorySource(
    val url: String,
    val name: String,
    val enabled: Boolean = true,
)

@Serializable
data class ManagedPluginInstall(
    val key: String,
    val sourceUrl: String,
    val version: String,
    val versionCode: Int? = null,
    val sha256: String,
    val pendingRestart: Boolean = false,
)

data class InstalledRepositoryPlugin(
    val key: String,
    val version: String,
    val versionCode: Int? = null,
    val bundled: Boolean = false,
    val name: String = key.substringAfter(':'),
)

data class PluginInstallOutcome(val restartRequired: Boolean = false, val warning: String? = null)
data class PluginTarget(val platform: String, val arch: String)

data class PluginCatalogEntry(
    val source: PluginRepositorySource,
    val plugin: RepositoryPlugin,
    val artifact: PluginArtifact?,
    val incompatibility: String?,
    val installed: InstalledRepositoryPlugin? = null,
    val managed: ManagedPluginInstall? = null,
) {
    val updateAvailable: Boolean
        get() = installed != null && managed?.pendingRestart != true && managed?.sourceUrl == source.url &&
            when (plugin.kind) {
                RepositoryPluginKind.LYRICS ->
                    plugin.versionCode != null && plugin.versionCode > (installed.versionCode ?: managed.versionCode ?: 0)
                RepositoryPluginKind.PROVIDER ->
                    PluginVersion.compare(plugin.version, installed.version)?.let { it > 0 } == true
            }
}

enum class PluginInstallPhase { DOWNLOADING, VERIFYING, INSTALLING, COMPLETE, RESTART_REQUIRED, FAILED }

data class PluginInstallTask(
    val key: String,
    val sourceUrl: String,
    val phase: PluginInstallPhase,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String? = null,
)

data class PluginRepositoryState(
    val sources: List<PluginRepositorySource> = emptyList(),
    val entries: List<PluginCatalogEntry> = emptyList(),
    val sourceErrors: Map<String, String> = emptyMap(),
    val networkEnabled: Boolean = false,
    val autoCheck: Boolean = false,
    val refreshing: Boolean = false,
    val lastCheckedAt: Long? = null,
    val task: PluginInstallTask? = null,
    val installedPlugins: List<InstalledRepositoryPlugin> = emptyList(),
    val managedInstalls: List<ManagedPluginInstall> = emptyList(),
)

/** Strict SemVer comparison; unknown legacy versions are never guessed to be newer. */
object PluginVersion {
    private val pattern = Regex(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
            "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
            "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$",
    )

    private fun parse(value: String): List<String>? {
        val match = pattern.matchEntire(value) ?: return null
        val pre = match.groupValues[4]
        if (pre.isNotEmpty() && pre.split('.').any {
                it.all(Char::isDigit) && it.length > 1 && it.startsWith('0')
            }) return null
        return match.groupValues
    }

    fun isValid(value: String): Boolean = parse(value) != null

    fun compare(left: String, right: String): Int? {
        val a = parse(left) ?: return null
        val b = parse(right) ?: return null
        for (index in 1..3) {
            val order = compareNumeric(a[index], b[index])
            if (order != 0) return order
        }
        val ap = a[4]
        val bp = b[4]
        if (ap.isEmpty() || bp.isEmpty()) return when {
            ap == bp -> 0
            ap.isEmpty() -> 1
            else -> -1
        }
        val ai = ap.split('.')
        val bi = bp.split('.')
        for (index in 0 until minOf(ai.size, bi.size)) {
            val x = ai[index]
            val y = bi[index]
            val xn = x.all(Char::isDigit)
            val yn = y.all(Char::isDigit)
            val order = when {
                xn && yn -> compareNumeric(x, y)
                xn != yn -> if (xn) -1 else 1
                else -> x.compareTo(y)
            }
            if (order != 0) return order
        }
        return ai.size.compareTo(bi.size)
    }

    private fun compareNumeric(a: String, b: String): Int =
        a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
}

fun RepositoryPlugin.compatibility(target: PluginTarget): Pair<PluginArtifact?, String?> {
    if (minHostApiVersion < 1 || apiVersion < 1) return null to "Invalid API version"
    when (kind) {
        RepositoryPluginKind.PROVIDER ->
            if (minHostApiVersion > HOST_PROVIDER_API_VERSION) {
                return null to "Requires provider host API $minHostApiVersion (current $HOST_PROVIDER_API_VERSION)"
            }
        RepositoryPluginKind.LYRICS -> {
            if (!HostApiRegistry.supportsHostApiVersion(minHostApiVersion) ||
                !HostApiRegistry.supportsPluginApiVersion(apiVersion)
            ) return null to "Unsupported lyrics API $apiVersion / host API $minHostApiVersion"
        }
    }
    val matches = artifacts.filter {
        (it.platform == "any" || it.platform == target.platform) &&
            (it.arch == "any" || it.arch == target.arch)
    }
    if (matches.isEmpty()) return null to "No package for ${target.platform} / ${target.arch}"
    val ranked = matches.groupBy {
        (if (it.platform == target.platform) 2 else 0) + (if (it.arch == target.arch) 1 else 0)
    }
    val best = ranked.getValue(ranked.keys.max())
    if (best.size != 1) return null to "Ambiguous platform packages"
    val artifact = best.single()
    if (kind == RepositoryPluginKind.PROVIDER && artifact.runtime !in setOf("http", "jni", "binary") ||
        kind == RepositoryPluginKind.LYRICS && artifact.runtime != "rhino-js"
    ) return null to "Unsupported plugin runtime"
    if (target.platform == "android" && artifact.runtime == "binary") {
        return null to "Android requires a JNI package, not an executable"
    }
    if (artifact.runtime in setOf("jni", "binary") &&
        (artifact.platform == "any" || artifact.arch == "any")
    ) return null to "Native packages must declare a platform and architecture"
    return artifact to null
}
