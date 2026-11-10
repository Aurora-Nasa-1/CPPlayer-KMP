package cp.player.app.update

import cp.player.app.AppModel
import cp.player.core.ImportResult
import cp.player.core.download.defaultDownloadExecutor
import cp.player.core.provider.BackendProvider
import cp.player.core.util.PlatformSupport
import cp.player.core.util.SettingsStorage
import cp.player.core.util.defaultSettingsStorage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ProviderUpdateInfo(
    val version: String,
    val downloadUrl: String,
    val changelog: String? = null,
    val name: String? = null,
    val minHostApiVersion: Int? = null,
    val sha256: String? = null,
    val publishedAt: String? = null,
)

sealed interface ProviderUpdateState {
    data object Idle : ProviderUpdateState
    data object Checking : ProviderUpdateState
    data class UpdateAvailable(val info: ProviderUpdateInfo) : ProviderUpdateState
    data object UpToDate : ProviderUpdateState
    data class Error(val message: String) : ProviderUpdateState
}

/**
 * 音源独立更新管理器：
 * - 支持读取 manifest 中的默认 updateUrl，也支持用户针对每个音源自定义更新源地址；
 * - 协议兼顾标准 JSON 格式与 GitHub Releases API；
 * - 支持后台自动静默检测、手动单源/全量检测，以及直接下载包体应用更新。
 */
class ProviderUpdateManager(
    private val settings: SettingsStorage = defaultSettingsStorage("provider_custom_updates_v1"),
    private val client: HttpClient = HttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _updateStates = MutableStateFlow<Map<String, ProviderUpdateState>>(emptyMap())
    val updateStates: StateFlow<Map<String, ProviderUpdateState>> = _updateStates.asStateFlow()

    fun getCustomUpdateUrl(providerId: String): String? {
        return settings.getString("custom_url_$providerId")?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setCustomUpdateUrl(providerId: String, url: String?) {
        val clean = url?.trim()?.takeIf { it.isNotEmpty() }
        if (clean == null) {
            settings.remove("custom_url_$providerId")
        } else {
            settings.putString("custom_url_$providerId", clean)
        }
    }

    fun getEffectiveUpdateUrl(provider: BackendProvider): String? {
        return getCustomUpdateUrl(provider.id) ?: provider.updateUrl?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun isAutoCheckEnabled(): Boolean {
        return settings.getString("auto_check") != "false"
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        settings.putString("auto_check", enabled.toString())
    }

    fun parseUpdateResponse(jsonText: String, defaultProviderId: String? = null): ProviderUpdateInfo? {
        return runCatching {
            val root = json.parseToJsonElement(jsonText)
            val obj = when (root) {
                is JsonObject -> root
                is JsonArray -> root.firstOrNull()?.jsonObject ?: return null
                else -> return null
            }

            // 1. GitHub Releases API 格式兼容 (tag_name / assets / body)
            val tagName = obj["tag_name"]?.jsonPrimitive?.content
            if (!tagName.isNullOrBlank()) {
                val version = tagName.removePrefix("v").removePrefix("debug-v").trim()
                val body = obj["body"]?.jsonPrimitive?.content?.trim()
                val assets = obj["assets"]?.jsonArray
                val zipAsset = assets?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                    ?.firstOrNull {
                        val name = it["name"]?.jsonPrimitive?.content.orEmpty()
                        name.endsWith(".zip", ignoreCase = true)
                    }
                val downloadUrl = zipAsset?.get("browser_download_url")?.jsonPrimitive?.content
                    ?: obj["html_url"]?.jsonPrimitive?.content
                    ?: return null
                val publishedAt = obj["published_at"]?.jsonPrimitive?.content
                return ProviderUpdateInfo(
                    version = version,
                    downloadUrl = downloadUrl,
                    changelog = body,
                    publishedAt = publishedAt,
                )
            }

            // 2. 通用标准 JSON 格式兼容
            val version = (obj["version"] ?: obj["versionName"] ?: obj["version_name"])
                ?.jsonPrimitive?.content?.removePrefix("v")?.trim() ?: return null
            val downloadUrl = (obj["downloadUrl"] ?: obj["download_url"] ?: obj["url"] ?: obj["zipUrl"] ?: obj["zip_url"])
                ?.jsonPrimitive?.content?.trim() ?: return null
            val changelog = (obj["changelog"] ?: obj["notes"] ?: obj["description"] ?: obj["body"])
                ?.jsonPrimitive?.content?.trim()
            val name = obj["name"]?.jsonPrimitive?.content?.trim()
            val minHostApiVersion = obj["minHostApiVersion"]?.jsonPrimitive?.content?.toIntOrNull()
            val sha256 = (obj["sha256"] ?: obj["hash"])?.jsonPrimitive?.content?.trim()
            val publishedAt = (obj["publishedAt"] ?: obj["published_at"])?.jsonPrimitive?.content?.trim()

            ProviderUpdateInfo(
                version = version,
                downloadUrl = downloadUrl,
                changelog = changelog,
                name = name,
                minHostApiVersion = minHostApiVersion,
                sha256 = sha256,
                publishedAt = publishedAt,
            )
        }.getOrNull()
    }

    suspend fun checkUpdate(provider: BackendProvider): ProviderUpdateState {
        val url = getEffectiveUpdateUrl(provider)
        if (url.isNullOrBlank()) {
            val state = ProviderUpdateState.Error("未配置更新源")
            _updateStates.update { it + (provider.id to state) }
            return state
        }

        _updateStates.update { it + (provider.id to ProviderUpdateState.Checking) }

        val state = try {
            val response = client.get(url) {
                header("User-Agent", "CPPlayer-ProviderUpdate/1.0")
                header("Accept", "application/json, text/plain, */*")
            }
            if (!response.status.isSuccess()) {
                ProviderUpdateState.Error("HTTP ${response.status.value}")
            } else {
                val text = response.bodyAsText()
                val info = parseUpdateResponse(text, provider.id)
                if (info == null) {
                    ProviderUpdateState.Error("更新信息解析失败")
                } else {
                    val hasNewer = AppUpdateChecker.compareVersions(info.version, provider.version) > 0
                    if (hasNewer) {
                        ProviderUpdateState.UpdateAvailable(info)
                    } else {
                        ProviderUpdateState.UpToDate
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ProviderUpdateState.Error(e.message ?: "检测失败")
        }

        _updateStates.update { it + (provider.id to state) }
        return state
    }

    suspend fun checkAllUpdates(providers: List<BackendProvider>) = coroutineScope {
        providers.forEach { provider ->
            if (!getEffectiveUpdateUrl(provider).isNullOrBlank()) {
                launch { checkUpdate(provider) }
            }
        }
    }

    suspend fun downloadAndInstall(
        provider: BackendProvider,
        info: ProviderUpdateInfo,
        onProgress: suspend (sessionWrittenBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        val context = AppModel.backend.platformContext
        val dataDir = PlatformSupport.dataDir(context)
        val downloadsDir = "$dataDir/provider-downloads"
        PlatformSupport.ensureDir(downloadsDir)
        val partPath = "$downloadsDir/${provider.id}-update.part"
        val zipPath = "$downloadsDir/${provider.id}-update.zip"

        PlatformSupport.deleteRecursively(partPath)
        PlatformSupport.deleteRecursively(zipPath)

        try {
            val executor = defaultDownloadExecutor()
            executor.download(
                url = info.downloadUrl,
                headers = mapOf(
                    "User-Agent" to "CPPlayer-ProviderUpdate/1.0",
                    "Accept" to "application/octet-stream, application/zip, */*",
                ),
                targetPartPath = partPath,
                resumeBytes = 0L,
                onProgress = onProgress,
            )

            check(PlatformSupport.moveFile(partPath, zipPath)) { "更新文件重命名失败" }

            if (!info.sha256.isNullOrBlank()) {
                val localSha256 = PlatformSupport.sha256Hex(zipPath)
                check(localSha256.equals(info.sha256.trim(), ignoreCase = true)) {
                    "校验失败：期望 ${info.sha256}，实际 $localSha256"
                }
            }

            val result = AppModel.updateModule(zipPath, provider.id)
            if (result is ImportResult.Activated || result is ImportResult.Loaded || result is ImportResult.PendingRestart) {
                _updateStates.update { it + (provider.id to ProviderUpdateState.UpToDate) }
            }
            result
        } finally {
            PlatformSupport.deleteRecursively(partPath)
            PlatformSupport.deleteRecursively(zipPath)
        }
    }

    companion object {
        val INSTANCE by lazy { ProviderUpdateManager() }
    }
}
