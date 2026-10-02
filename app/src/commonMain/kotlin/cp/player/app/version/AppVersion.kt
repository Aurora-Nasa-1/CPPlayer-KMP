package cp.player.app.version

object AppVersion {
    const val REPO_OWNER = "Aurora-Nasa-1"
    const val REPO_NAME = "CPPlayer-KMP"
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
    var releaseChannel: String = "stable"
        internal set

    fun init(
        versionName: String,
        versionCode: Int = 1,
        gitSha: String = "unknown",
        isDesktop: Boolean = false,
        releaseChannel: String = "stable",
    ) {
        this.versionName = versionName
        this.versionCode = versionCode
        this.gitSha = gitSha
        this.isDesktop = isDesktop
        this.releaseChannel = releaseChannel
    }

    /**
     * 是否为 debug 构建。
     *
     * 本项目没有传统意义的 buildType 开关（桌面端 run 与打包共用同一份 jvmArgs），
     * 「debug 构建」以**发布渠道**界定：只有 `stable` 是面向普通用户的正式渠道；
     * CI 的 `debug-v*` 预发布（`-Papp.releaseChannel=debug`）以及本地以 debug 渠道
     * 运行的构建都算 debug。开发者功能（渲染后端调优、重看新手引导等设置入口）
     * 据此决定是否展示。
     */
    val isDebugBuild: Boolean get() = releaseChannel != "stable"

    val fullVersion: String get() = "v$versionName ($versionCode)"

    val shortSha: String get() = if (gitSha.length > 7) gitSha.take(7) else gitSha
}
