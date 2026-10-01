plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

// =============================================================================
// 版本元数据 —— 全仓唯一来源
// =============================================================================
// `:app`（桌面）与 `:app-android` 都从这里通过 `rootProject.extra` 取值，
// **不要在模块里再写第二份公式**。
//
// 历史：同一个版本号曾在四条路径上各算各的，于是同一个 tag 会得到不同的
// versionCode ——
//   * `gradle.properties`                      `app.versionCode=1`（写死）
//   * `.github/workflows/release.yml`          `GITHUB_RUN_NUMBER`
//                                              （与版本号无关；同一 tag 重跑一次
//                                               就换一个码，且不可复现）
//   * `.github/workflows/debug-release.yml`    `100000 + GITHUB_RUN_NUMBER`
//   * `scripts/release.ps1`                    `major*10000 + minor*100 + patch`
// 后果有两个，都是用户可见的：
//   ① 同一版本号在不同路径上得到不同的码 ⇒ 安装时被判成「降级」而失败；
//   ② debug 的 `+100000` 高段会让 **debug 包永久挡住之后所有 stable 包的升级安装**
//      （stable 1.1.0 = 11000 < debug 1.0.0 = 110000），必须卸载重装才能回正式版。
//
// 现在的规则：**versionCode 只由 versionName 决定**。
// 真要覆盖就显式传 `-Papp.versionCode=`（本地 fastrelease 包会这么干）。

/**
 * SemVer -> Android versionCode：`major * 1_000_000 + minor * 1_000 + patch`。
 *
 * - 每段留 1000 的位宽。`release.ps1` 原来用 100 的位宽，`1.0.100` 会和 `1.1.0`
 *   算出同一个码（10100）。
 * - 预发布后缀（`-beta.2` / `+build.7`）**不参与**计算：`1.2.3-beta.2` 与 `1.2.3`
 *   同码。Android 只要求「新版本 >= 旧版本」，同码可以覆盖安装；而且正式版
 *   能覆盖同号预发布、反过来不行，正是我们要的方向。
 *   ⚠️ **不要**再加「debug 高段」之类的偏移 —— 见上面 ②。
 * - 夹在 `1..2_100_000_000`（Android 的 versionCode 上限）之间。
 */
fun cpVersionCodeOf(versionName: String): Int {
    val core = versionName.substringBefore('-').substringBefore('+').trim()
    val parts = core.split('.').map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }.coerceAtLeast(0)
    val minor = parts.getOrElse(1) { 0 }.coerceAtLeast(0)
    val patch = parts.getOrElse(2) { 0 }.coerceAtLeast(0)
    val code = major.toLong() * 1_000_000L + minor.toLong() * 1_000L + patch.toLong()
    return code.coerceIn(1L, 2_100_000_000L).toInt()
}

val cpAppVersionName: String = providers.gradleProperty("app.versionName")
    .orElse("1.0.0")
    .get()
    .trim()

val cpAppReleaseChannel: String = providers.gradleProperty("app.releaseChannel")
    .orElse("stable")
    .get()
    .trim()

val cpAppVersionCode: Int = providers.gradleProperty("app.versionCode")
    .map { raw -> raw.trim().toIntOrNull() ?: error("app.versionCode 必须是整数，收到 '$raw'") }
    .orElse(cpVersionCodeOf(cpAppVersionName))
    .get()

/** jpackage 不认预发布后缀（`1.2.3-beta.1` 会被拒），所以 MSI/deb 只吃主版本号。 */
val cpAppPackageVersion: String = cpAppVersionName.substringBefore('-').ifBlank { "1.0.0" }

/**
 * 短 git sha，注入 `BuildConfig.GIT_SHA` / 桌面 `BuildInfo.GIT_SHA`。
 * git 不存在（源码包、容器）时回落 `"unknown"`，**不能让它把构建搞挂**。
 */
val cpGitSha: String = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
    process.waitFor()
    text
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

extra["cpAppVersionName"] = cpAppVersionName
extra["cpAppVersionCode"] = cpAppVersionCode
extra["cpAppReleaseChannel"] = cpAppReleaseChannel
extra["cpAppPackageVersion"] = cpAppPackageVersion
extra["cpGitSha"] = cpGitSha

// CI 日志里能直接看到「这个 tag 实际构建成了哪个版本」，不必再靠产物文件名反推。
logger.lifecycle("CPPlayer build metadata: $cpAppVersionName ($cpAppVersionCode) channel=$cpAppReleaseChannel sha=$cpGitSha")
