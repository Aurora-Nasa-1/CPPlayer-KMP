package cp.player.app.update

import cp.player.app.version.AppVersion
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
    )

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
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

    suspend fun checkUpdate(): UpdateResult? {
        return try {
            val response = client.get(AppVersion.RELEASES_API) {
                header("Accept", "application/vnd.github.v3+json")
            }
            val releases: List<GitHubRelease> = response.body()
            if (releases.isEmpty()) return null

            val latest = releases.first()
            val remoteVersionName = latest.tagName.removePrefix("v")

            if (compareVersions(AppVersion.versionName, remoteVersionName) >= 0) return null

            val changelog = buildChangelog(releases, AppVersion.versionName)
            val downloadUrl = latest.assets
                .firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?.browserDownloadUrl
                ?: latest.htmlUrl

            UpdateResult(
                versionName = remoteVersionName,
                versionCode = 0,
                downloadUrl = downloadUrl,
                changelog = changelog.ifBlank { latest.body },
                publishedAt = latest.publishedAt,
                releaseUrl = latest.htmlUrl,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildChangelog(releases: List<GitHubRelease>, currentVersion: String): String {
        val sb = StringBuilder()
        for (release in releases) {
            val ver = release.tagName.removePrefix("v")
            if (compareVersions(ver, currentVersion) <= 0) break
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
