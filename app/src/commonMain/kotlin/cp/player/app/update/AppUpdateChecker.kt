package cp.player.app.update

import cp.player.app.version.AppVersion
import cp.player.app.platform.desktopPlatform
import cp.player.app.platform.isAndroidPlatform
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object AppUpdateChecker {

    data class UpdateResult(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String?,
        val changelog: String?,
        val publishedAt: String?,
        val releaseUrl: String,
        val assetName: String? = null,
    )

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("html_url") val htmlUrl: String,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        @SerialName("content_type") val contentType: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient {
        install(ContentNegotiation) { json(this@AppUpdateChecker.json) }
    }

    /** 正式版 tag：`v1.2.3` / `v1.2.3-beta.1`。 */
    private val stableTag = Regex("^v\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?$")

    /** 预发布渠道 tag：`debug-v1.2.3`（见 .github/workflows/debug-release.yml）。 */
    private val debugTag = Regex("^debug-v\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?$")

    /**
     * tag -> 版本号。
     *
     * ⚠️ 必须**同时**剥掉 `debug-v` 与 `v`：以前只写了 `removePrefix("v")`，
     * 于是 `debug-v1.2.3` 原样留下，`compareVersions` 再按 `.` 切成
     * `["debug-v1", "2", "3"]` —— 数字段解析失败全部回落 0，变成 `[0, 2, 3]`。
     * 结果：`0.0.x` 的构建会把这个「版本」当成更新推给用户。
     */
    private fun GitHubRelease.versionName(): String =
        tagName.removePrefix("debug-v").removePrefix("v")

    /**
     * 这条 release 要不要参与「有没有新版本」的判断。
     *
     * - 草稿永远不算。
     * - `stable` 渠道**不看 prerelease**：`/releases` 是**按创建时间**倒序返回的，
     *   会把 debug 预发布排在正式版前面，不过滤就会拿预发布去比版本号。
     * - `debug` / 其他非 stable 渠道才允许看到预发布。
     */
    private fun GitHubRelease.isCandidate(): Boolean {
        if (draft) return false
        val isDebugChannel = AppVersion.releaseChannel != "stable"
        return when {
            stableTag.matches(tagName) -> !prerelease || isDebugChannel
            debugTag.matches(tagName) -> isDebugChannel
            else -> false
        }
    }

    /** 按 SemVer 取更新的那条（相等时取 a）。 */
    private fun newer(a: GitHubRelease, b: GitHubRelease): GitHubRelease =
        if (compareVersions(a.versionName(), b.versionName()) >= 0) a else b

    suspend fun checkUpdate(): UpdateResult? {
        return try {
            val response = client.get(AppVersion.RELEASES_API) {
                header("Accept", "application/vnd.github.v3+json")
            }
            // 先落到一个带显式类型的局部变量：`response.body()` 是 reified 的，
            // 直接串 `.filter { … }` 时接收者类型没有约束，T 推不出来。
            val fetched: List<GitHubRelease> = response.body()
            val releases = fetched.filter { it.isCandidate() }
            if (releases.isEmpty()) return null

            // ⚠️ 不能取 `releases.first()`：GitHub 按**创建时间**倒序返回，
            // 而创建时间不等于版本号大小（补发旧版本、并行 workflow 都会打乱顺序）。
            // 按 SemVer 取最大的那条才是「最新版本」。
            val latest = releases.reduceOrNull { acc, r -> newer(acc, r) } ?: return null
            val remoteVersionName = latest.versionName()

            if (compareVersions(AppVersion.versionName, remoteVersionName) >= 0) return null

            val changelog = buildChangelog(releases, AppVersion.versionName)
            val asset = latest.assets.firstOrNull { asset ->
                when {
                    isAndroidPlatform() -> asset.name.endsWith(".apk", ignoreCase = true)
                    desktopPlatform() == "windows" -> asset.name.endsWith(".msi", ignoreCase = true) || asset.name.endsWith(".zip", ignoreCase = true)
                    desktopPlatform() == "linux" -> asset.name.endsWith(".deb", ignoreCase = true) || asset.name.endsWith(".tar.gz", ignoreCase = true)
                    else -> asset.name.endsWith(".msi", true) || asset.name.endsWith(".deb", true)
                }
            }
            val downloadUrl = asset?.browserDownloadUrl ?: latest.htmlUrl

            UpdateResult(
                versionName = remoteVersionName,
                versionCode = 0,
                downloadUrl = downloadUrl,
                changelog = changelog.ifBlank { latest.body },
                publishedAt = latest.publishedAt,
                releaseUrl = latest.htmlUrl,
                assetName = asset?.name,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildChangelog(releases: List<GitHubRelease>, currentVersion: String): String {
        // 先只留比当前版本新的，再按 SemVer 从新到旧排。
        // 接口给的是**创建时间序**，不重排的话「补发旧版本」「并行 workflow」
        // 都会把顺序打乱，日志就会缺条目或多出不该有的条目。
        val pending = releases
            .filter { compareVersions(it.versionName(), currentVersion) > 0 }
            .toMutableList()
        val ordered = mutableListOf<GitHubRelease>()
        while (pending.isNotEmpty()) {
            val newest = pending.reduce { acc, r -> newer(acc, r) }
            pending.remove(newest)
            ordered += newest
        }

        val sb = StringBuilder()
        for (release in ordered) {
            val body = release.body
            if (!body.isNullOrBlank()) {
                sb.appendLine("### ${release.tagName}")
                sb.appendLine()
                sb.appendLine(body.trim())
                sb.appendLine()
            }
        }
        return sb.toString().trimEnd()
    }

    fun compareVersions(v1: String, v2: String): Int {
        fun parseVersion(version: String): Pair<List<Int>, String?> {
            val v = version.removePrefix("v")
            val dash = v.indexOf('-')
            val numeric = if (dash >= 0) v.substring(0, dash) else v
            val pre = if (dash >= 0) v.substring(dash + 1) else null
            return numeric.split(".").map { it.toIntOrNull() ?: 0 } to pre
        }

        fun comparePre(pre1: String, pre2: String): Int {
            val re = Regex("^([a-zA-Z]*)(\\d*)$")
            val m1 = re.matchEntire(pre1)
            val m2 = re.matchEntire(pre2)
            if (m1 != null && m2 != null) {
                val pc = m1.groupValues[1].compareTo(m2.groupValues[1])
                if (pc != 0) return pc
                val n1 = m1.groupValues[2].toIntOrNull() ?: 0
                val n2 = m2.groupValues[2].toIntOrNull() ?: 0
                return n1 - n2
            }
            return pre1.compareTo(pre2)
        }

        val (p1, pre1) = parseVersion(v1)
        val (p2, pre2) = parseVersion(v2)
        val maxLen = maxOf(p1.size, p2.size)
        for (i in 0 until maxLen) {
            val a = p1.getOrElse(i) { 0 }
            val b = p2.getOrElse(i) { 0 }
            if (a != b) return a - b
        }
        return when {
            pre1 != null && pre2 != null -> comparePre(pre1, pre2)
            pre1 != null && pre2 == null -> -1
            pre1 == null && pre2 != null -> 1
            else -> 0
        }
    }
}
