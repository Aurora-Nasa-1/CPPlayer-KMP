package cp.player.app.version

object AppVersion {
    const val REPO_OWNER = "Aurora-Nasa-1"
    const val REPO_NAME = "CPPlayer"
    const val RELEASES_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases"
    const val RELEASES_PAGE = "https://github.com/$REPO_OWNER/$REPO_NAME/releases"

    var versionName: String = "1.0.0"
        internal set
    var versionCode: Int = 1
        internal set
    var gitSha: String = "unknown"
        internal set
    var isDesktop: Boolean = false
        internal set

    fun init(
        versionName: String,
        versionCode: Int = 1,
        gitSha: String = "unknown",
        isDesktop: Boolean = false,
    ) {
        this.versionName = versionName
        this.versionCode = versionCode
        this.gitSha = gitSha
        this.isDesktop = isDesktop
    }

    val fullVersion: String get() = "v$versionName ($versionCode)"

    val shortSha: String get() = if (gitSha.length > 7) gitSha.take(7) else gitSha
}
