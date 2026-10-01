package cp.player.app

/**
 * 桌面端构建元数据。
 *
 * 四个值全部来自 `app/build.gradle.kts` 里 `compose.desktop.application.jvmArgs`
 * 注入的系统属性 —— **Gradle 属性不会自动变成 JVM 系统属性**，所以那里少写一行，
 * 这里就会静默回落到默认值（「关于」页永远显示 v1.0.0 (1) / unknown / stable）。
 *
 * 取值链路：根 `build.gradle.kts` 的 `cpApp*` extra → `jvmArgs` → 此处。
 * 本地 `desktopRun` 与打包后的 MSI/deb 走的是同一份 `jvmArgs`。
 */
object BuildInfo {
    val VERSION_NAME: String = System.getProperty("cp.player.versionName", "1.0.0")
    val VERSION_CODE: Int = System.getProperty("cp.player.versionCode", "1").toIntOrNull() ?: 1
    val GIT_SHA: String = System.getProperty("cp.player.gitSha", "unknown")
    val RELEASE_CHANNEL: String = System.getProperty("cp.player.releaseChannel", "stable")
}
