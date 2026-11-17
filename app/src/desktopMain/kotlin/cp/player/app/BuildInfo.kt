package cp.player.app

/**
 * 桌面端构建元数据。
 *
 * 取值链路（三个入口，同一份值）：
 * - **JVM 包（Windows 的 jpackage app-image / Linux 的 deb / tar.gz）**：`app/build.gradle.kts`
 *   的 `desktopCommonJvmArgs` 写进启动器 `CPPlayer.cfg` 的 `-Dcp.player.*`；
 * - **本地 `desktopRun`**：同一份 `jvmArgs`，所以本地也能看到真实版本号；
 * - **Windows 原生镜像（Velopack 安装包）**：没有启动器、没有 `.cfg`，靠
 *   `graalvm { buildArgs }` 里那两个东西 —— `-Dcp.player.*` 与
 *   `--initialize-at-build-time=cp.player.app.BuildInfo`。native-image 的 `-D`
 *   只在**构建期**有效，所以本 object 必须在构建期完成初始化，值才会随对象进镜像。
 *   少任何一行，这里就静默回落到默认值。
 *
 * 默认值刻意写成 `0.0.0 (0)` 而不是 `1.0.0 (1)`：v1.9.0 / v1.9.1 的原生包就是因为
 * 回落到默认值，把「关于」页显示成 1.0.0 (1) 而没人发现 —— 默认值一旦「看起来像版本号」，
 * 断链就是静默的。这里宁可显示一个明显不对的版本。
 *
 * ⚠️ **Gradle 属性不会自动变成 JVM 系统属性**：`-Papp.versionName=…` 与
 * `-Dcp.player.versionName=…` 是两回事，别把前者当成后者的来源。
 */
object BuildInfo {
    val VERSION_NAME: String = System.getProperty("cp.player.versionName", "0.0.0")
    val VERSION_CODE: Int = System.getProperty("cp.player.versionCode", "0").toIntOrNull() ?: 0
    val GIT_SHA: String = System.getProperty("cp.player.gitSha", "unknown")
    val RELEASE_CHANNEL: String = System.getProperty("cp.player.releaseChannel", "stable")
}
