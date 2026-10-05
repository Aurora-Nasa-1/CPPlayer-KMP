import java.security.MessageDigest
import java.time.Year
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.gradle.api.tasks.JavaExec
import org.gradle.api.file.RegularFile
import org.gradle.jvm.toolchain.JavaInstallationMetadata
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

// 版本元数据只有一处实现：根 build.gradle.kts。这里只做取值，**不要再自己算**。
// （历史：本文件曾各自 orElse("1.0.0")，与 CI 的 -P 覆盖、app-android 的写法
//   三份并存，同一个 tag 会得到不同的 versionCode。）
val appVersionName: String = rootProject.extra["cpAppVersionName"] as String
val appVersionCode: Int = rootProject.extra["cpAppVersionCode"] as Int
val appReleaseChannel: String = rootProject.extra["cpAppReleaseChannel"] as String
val appPackageVersion: String = rootProject.extra["cpAppPackageVersion"] as String
val gitSha: String = rootProject.extra["cpGitSha"] as String

// 建议保持 ASCII（**不再是硬约束**，2026-10-05 起）。
//
// 原本是硬约束，来源是 MSI：
// jpackage 把它原样写进 MSI：main.wxs 里的 `<Package Description="$(var.JpAppDescription)">`
// 和 `ARPCOMMENTS`。MSI 是一张带 codepage 的数据库，而 jpackage 内置的
// `MsiInstallerStrings_en.wxl` 把 Codepage 钉死在 **1252**（西欧），于是中文字符在
// light.exe 阶段直接报 LGHT0311 —— 也就是 CI 上那句不明所以的
// `Command [light.exe, ...] exited with 311 code`。
//
// JDK 侧至今没有 `--win-codepage`（JDK-8290471 未解决）。要保留中文只有一条路：
// 自己覆盖那份 .wxl 把 Codepage 改成 936，代价（依赖 jpackage 内部资源目录、
// 且非 1252 的 MSI 在部分环境里兼容性一般）远大于收益。
//
// 另：`copyright` 里的 `©`（U+00A9）不受影响 —— 它在 cp1252 里有码位，
// 而且只进 exe 的版本资源，不进 MSI 数据库。
val appDescription: String = "Cross-platform music player (Material 3 Expressive)"

/**
 * jpackage 的应用名：应用镜像目录名（`binaries/main/app/CPPlayer`）、启动器名
 * （`bin/CPPlayer`）、Windows 快捷方式目录等共用。`packageLinuxTarGz` 与
 * AUR PKGBUILD（packaging/aur/cpplayer-bin）都依赖这个名字，改这里必须连带核对。
 */
val cpPackageName = "CPPlayer"

// ---------------------------------------------------------------------------
// JetBrains Runtime (JBR) —— 换运行时的唯一目的是**原生窗口拖动**。
//
// 走 `compose.desktop.application.javaHome` 把 JBR 打进 jpackage 产物之后，
// `WindowDraggableArea` 会从 `StandardMoveHandler`（AWT MouseListener 软件移动）
// 升级到 `JbrMoveHandler`（原生 `WindowMove`），从而拿到**贴边吸附 / 拖出还原 /
// 拖动跟手**。选择点是 Compose 内部的
// `com.jetbrains.JBR.isWindowMoveSupported()` 二选一（已 javap 核实），应用侧改不了。
//
// 方案 / 来源 / 校验 / 风险 / 验收清单见 `docs/dev/JBR_PACKAGING.md`，这里只做落地。
//
// ⚠️ 三条硬约束：
//   1. **版本钉死 21**（不是 JBR 25）：`:app` 的 desktop `jvmTarget` 就是 JVM_21，
//      「换厂商」和「升主版本」必须分成两件事做，捆在一起出问题没法二分。
//   2. **来源是 JetBrains 官方 cache-redirector**，不是 GitHub Releases ——
//      `JetBrains/JetBrainsRuntime` 的 release **assets 全为 0**（扫过 3 页 100/页），
//      而 cache-redirector 实测 200 且给 bytes。它也不提供 `.sha256`，
//      所以哈希只能我们自己记（下面那两个值是**实际下载后算出来的**）。
//   3. **没解析到 JBR 就不设 javaHome** —— 保持现状（Zulu）比「设成一个不存在的路径」
//      或「静默回退还以为是 JBR」都好。换运行时是显式的，不是碰运气。
// ---------------------------------------------------------------------------

// ⚠️ 只能用 `val`，不能用 `const val`：Kotlin 脚本体的顶层是**语句块**而不是
// 类/文件顶层，`const` 在这里是编译错误（"Const 'val' is only allowed on top level,
// in named objects, or in companion objects"）。
private val jbrVersion = "21.0.8"
private val jbrBuild = "b1163.62"
private val jbrBaseUrl = "https://cache-redirector.jetbrains.com/intellij-jbr"

// 实测 sha256（`sha256sum` 于 2026-10-02 从 cache-redirector 下载后计算）。
// 升 JBR 版本时**必须重新下载重算**，不能沿用 —— 沿用一个对不上的哈希
// 会让构建直接失败（这是好的），但如果改哈希去迁就包，校验就形同虚设了。
//
// ⚠️ 必须用 `jbrsdk-` 变体（完整 JDK），不能用裸 `jbr-`（那是 JRE）：
// Compose 插件的 checkRuntime 要求 javaHome 里有 `jlink` / `jpackage`
// （它要自己跑 jlink 从 jmods 裁运行时镜像）。裸 `jbr-` 包在 CI 上
// `:app:checkRuntime` 直接失败："Failed to check JDK distribution:
// 'jlink', 'jpackage' are missing"。最终打进安装包的是 jlink 的裁剪产物，
// 所以用 SDK 包**不会**让产物变大（只是构建期多占 ~200MB 磁盘）。
private val JbrSha256 = mapOf(
    "windows" to "432d0f9bdc687a6c8e2e13e22be83cdb0d9460b6b15752e84bd97165e13e9f5f",
    // ⚠️ Linux 侧只有 `.tar.gz`：`.zip` 是 403。Windows 侧两者都有，用 .zip。
    "linux" to "482b63da8ac63b8f108878d1bd8a23df15fd78cc9dfd23f3b824fbfe2512c81f",
)

private val hostJbrPlatform: String? = run {
    val os = System.getProperty("os.name").lowercase()
    when {
        os.contains("win") -> "windows"
        os.contains("linux") -> "linux"
        else -> null // macOS 未支持：本仓库的 desktop 产物只有 Msi / Deb。
    }
}

/**
 * 把 `targetDir` 里唯一的顶层目录**拍平**到 `targetDir` 本身。
 *
 * JBR 的 zip 解开是 `<版本名>/bin/java.exe`，而 `javaHome` 必须正好是「含 `release` 的那层」。
 *
 * ⚠️ 判据刻意不写死目录名（**实测它叫 `jbr-21.0.8-windows-x64-b1163.62`，不是 `jbr`**）。
 * 这里用「顶层只有一个条目、且它是个目录」来判定，对版本号变化免疫。
 * 解压前 `targetDir` 已被清空，所以「只有一个条目」这个前提是成立且可判的。
 */
private fun flattenSingleTopDir(targetDir: File) {
    val entries = targetDir.listFiles() ?: return
    if (entries.size != 1) return
    val only = entries.single()
    if (!only.isDirectory) return
    val children = only.listFiles() ?: return
    children.forEach { it.renameTo(File(targetDir, it.name)) }
    only.delete()
}

/**
 * 纯 JVM 解 `.tar.gz`，并把顶层目录剥掉（等价 `tar --strip-components=1`）。
 *
 * 为什么手写而不是 shell 出去：见调用点的说明。这里只额外强调**剥顶层目录是必须的** ——
 * JBR 的 tar 包解出来是 `<版本名>/` 一层（与 zip 一样，**不是** `jbr/`），
 * 留着他 `javaHome` 就指错一层。
 *
 * ⚠️ 只处理普通文件与目录，符号链接**跳过**：JBR 的 Linux 运行时镜像里
 * `lib/server/libjvm.so` 之类都是真文件，不需要跟着链过去。
 */
private fun untarGz(archive: File, targetDir: File) {
    GzipCompressorInputStream(archive.inputStream().buffered()).use { gz ->
        TarArchiveInputStream(gz).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                // 剥掉第一层目录段：`jbr/bin/java` → `bin/java`
                val relative = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (relative.isNotEmpty()) {
                    val out = File(targetDir, relative)
                    // 防御 zip-slip / tar-slip：解出来的路径必须仍在 targetDir 之内。
                    check(out.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                        "JBR 包里有越界路径（可能是恶意构造）：${entry.name}"
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
                entry = tar.nextEntry
            }
        }
    }
}

/**
 * 解析要打进产物的 JBR 目录，**按优先级**：
 *
 * 1. `-Pcp.jbrHome=/abs/path` —— 本地调试 / CI 显式指定，最高优先级；
 * 2. 环境变量 `CP_JBR_HOME`；
 * 3. 仓库内 `.jbr/<platform>-x64/` —— 由 CI 或 [ensureJbrDownloaded] 铺好。
 *
 * 全都没命中 ⇒ `null`（构建继续用默认 JDK，并在配置期打印一行提示）。
 */
private fun resolveJbrHome(): File? {
    val prop = (findProperty("cp.jbrHome") as String?)?.takeIf { it.isNotBlank() }
    val env = System.getenv("CP_JBR_HOME")?.takeIf { it.isNotBlank() }
    val inRepo = hostJbrPlatform
        ?.let { rootProject.layout.projectDirectory.dir(".jbr/$it-x64").asFile }
        ?.takeIf { it.resolve("release").exists() } // `release` 是 JDK 布局的标志文件
    return listOfNotNull(prop?.let(::File), env?.let(::File), inRepo)
        .firstOrNull { it.resolve("release").exists() }
}

private fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 把 JBR SDK 铺到 `.jbr/<platform>-x64/`。**幂等**：已有合法运行时就直接返回（CI 每次全量冷跑，
 * 不幂等会白下 ~240MB）。
 *
 * ⚠️ 顺序是「先下临时文件 → 校验 → 再落位」，不是「直接下到目标目录」：
 * 中断留下的半截包如果已经落在目标位置，下次会因目录存在而被误判成已就绪，
 * 打出一个**损坏的运行时** —— 而且 jpackage 只会甩一句 "invalid runtime image"。
 * 校验失败必须**删掉临时文件再抛**，不能只 warn。
 */
private fun ensureJbrDownloaded(): File {
    val platform = requireNotNull(hostJbrPlatform) {
        "JBR 目前只支持 Windows / Linux（本仓库 desktop 产物是 Msi / Deb）。"
    }
    val targetDir = rootProject.layout.projectDirectory.dir(".jbr/$platform-x64").asFile
    if (targetDir.resolve("release").exists()) return targetDir

    val ext = if (platform == "windows") "zip" else "tar.gz"
    // ⚠️ 前缀必须是 `jbrsdk-`（完整 JDK，含 jlink/jpackage/jmods）；
    // 裸 `jbr-` 是 JRE，Compose 的 checkRuntime 会报
    // "Failed to check JDK distribution: 'jlink', 'jpackage' are missing"。
    val url = "$jbrBaseUrl/jbrsdk-$jbrVersion-$platform-x64-$jbrBuild.$ext"
    val archive = File(targetDir.parentFile, "jbr-$platform.part.$ext")
    archive.parentFile.mkdirs()

    logger.lifecycle("CPPlayer: 下载 JBR $jbrVersion-$jbrBuild ($platform-x64) → $url")
    uri(url).toURL().openStream().use { input ->
        archive.outputStream().use { output -> input.copyTo(output) }
    }

    val actual = sha256Of(archive)
    val expected = requireNotNull(JbrSha256[platform])
    check(actual == expected) {
        archive.delete()
        "JBR 包 sha256 不匹配 —— 已删除下载文件，不要使用它。\n" +
            "  期望：$expected\n  实得：$actual\n  来源：$url\n" +
            "若确认是该版本的新包，请重新计算并更新 app/build.gradle.kts 里的 JbrSha256。"
    }

    // 解开后**剥掉单层目录**：JBR 压缩包解出来是 `jbr/` 这一层，
    // 留着他 javaHome 就指错一层（jpackage 报 invalid runtime image，同样没有细节）。
    targetDir.deleteRecursively()
    targetDir.mkdirs()
    if (platform == "windows") {
        // ⚠️ `copy {}` 的参数是 Project —— 在普通函数里必须显式带上
        // `project.`（脚本作用域的隐式接收者不传递到函数内部）。
        project.copy { from(project.zipTree(archive)); into(targetDir) }
        // 拍平单层目录。⚠️ **不要硬编码目录名**：实际是 `jbr-21.0.8-windows-x64-b1163.62/`
        // （带版本号），不是 `jbr/`。判据是「顶层只有一个目录且里面没有 release 文件」。
        flattenSingleTopDir(targetDir)
    } else {
        // ⚠️ **不要调 `tar` / `project.exec`**：前者要假设宿主有 tar（Windows runner 上
        // 不一定有，且中文路径会踩编码坑）；后者在本脚本作用域里解析失败
        // （实测 `Unresolved reference 'exec'`，被隐式接收者遮蔽）。
        // 用 Gradle 运行时已经带着的 `commons-compress` 纯 JVM 解压 —— 无外部进程、跨平台。
        untarGz(archive, targetDir)
    }
    archive.delete()
    check(targetDir.resolve("release").exists()) {
        "JBR 解压后找不到 `release` 文件（目录布局不对）：${targetDir.absolutePath}"
    }
    return targetDir
}

// 解析一次，配置期就定下来。`javaHome` 只能在配置期赋值，所以这里**不能在
// 任务里延迟到执行期** —— 要「先下载再打包」就只有两个选择：
//   a) 配置期同步下载（下面这条，仅当 -Pcp.jbrDownload=true）；
//   b) CI 在 gradlew 之前用独立 step 下载好（`.github/workflows/desktop-release.yml` 走这条）。
// 默认 **off**：本地不带这个开关时构建完全不受影响（不会突然卡 ~240MB 下载）。
private var resolvedJbrHome: File? = resolveJbrHome()
if (resolvedJbrHome == null && (findProperty("cp.jbrDownload") as String?) == "true") {
    resolvedJbrHome = ensureJbrDownloaded()
}

// 2026-10-05：这条**曾经是硬断言**，现在降级为警告 —— 它防的是 **MSI** 那条链路：
// MSI 数据库的 codepage 被 jpackage 内置的 MsiInstallerStrings_en.wxl 钉死在 1252，
// 中文在 light.exe 阶段直接 LGHT0311，而 jpackage 只会甩一句 `exited with 311 code`
// （要 --verbose 才看得到原因）。Windows 安装包改由 Velopack 打之后，MSI 已经不出产了，
// 这个失败模式不复存在。
//
// 剩下的 deb / dmg 对 UTF-8 没有意见，但保持 ASCII 依旧最省心（老工具链、
// 「添加/删除程序」列表里读它），所以约束留着、只是不再让构建失败。
if (appDescription.any { it.code > 0x7F }) {
    logger.warn(
        "CPPlayer: nativeDistributions.description 含非 ASCII 字符。" +
            "MSI 时代它是硬错误（codepage 1252 → LGHT0311），现在只剩 deb/dmg 消费它，" +
            "构建能过，但仍建议保持 ASCII。"
    )
}

group = "cp.player"
version = appVersionName

kotlin {
    android {
        namespace = "cp.player.app.lib"
        // 与 app-android 对齐：material-kolor 5.x 要求 compileSdk ≥ 37。
        compileSdk = 37
        minSdk = 29
        // ⚠️ 这一行是 Compose 资源（字体）能在安卓上跑起来的**前提**，删了会静默失效。
        //
        // `com.android.kotlin.multiplatform.library` 默认关掉 Android 资源处理，
        // 连带的后果是 AGP 根本**不创建 assets 任务**（`generateAndroidMainAssets` /
        // `mergeAndroidMainAssets` 都不存在）。于是 Compose 插件注册的
        // `copyAndroidMainComposeResourcesToAndroidAssets` 没人给它 outputDirectory，
        // 这个任务就永远不在任何任务图里 —— 打包出的 AAR/APK 里一个字体文件都没有，
        // 而**编译、单测、桌面端全都正常**，只有安卓运行时静默回退到系统字体。
        //
        // 打开之后 AAR 里会出现
        // `assets/composeResources/cp.player.app.resources/font/google_sans_flex.ttf`，
        // 正是 `DefaultAndroidResourceReader` 用 `AssetManager.open(path)` 读的那个路径。
        // 路径里的 `cp.player.app.resources` 来自下面 `compose.resources.packageOfResClass`，
        // **两者改一个必须同时改另一个**。
        androidResources { enable = true }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        mainRun {
            mainClass = "cp.player.app.MainKt"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core"))
                implementation(libs.composemediaplayer.audio)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                // 把 material3 顶到 1.11.0-alpha07（插件的 compose.material3 只有 1.9.0，
                // 缺少 M3 Expressive 的 MaterialShapes / 波形进度条 / 变形加载指示器 等）。
                // 版本冲突时 Gradle 取高者，所以这一行足以覆盖插件给的 1.9.0。
                implementation(libs.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.voyager.navigator)
                implementation(libs.voyager.screenmodel)
                implementation(libs.voyager.transitions)
                implementation(libs.qrose)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.okhttp)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.accompanist.lyrics.ui)
                implementation(libs.accompanist.lyrics.core)
                // 跨平台 Material You：seed 色 → M3 ColorScheme（含逐角色过渡动画）
                implementation(libs.materialkolor)
                // 封面 / 壁纸取色：Material You 官方的量化 + 打分算法（与系统 Monet 同源）
                implementation(libs.material.color.utilities)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.media.compat)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.ktor.client.okhttp)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jmtc)
                implementation(libs.jna.platform)
                implementation(libs.ktor.client.okhttp)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// 生成的 `Res` 类固定放 cp.player.app.resources。
// 默认情况下它的包名由 Gradle 的 group + 模块名拼出来，一旦有人动 `group`，
// 所有 `import ...resources.Res` 会一起断掉；钉死就不会。
compose.resources {
    packageOfResClass = "cp.player.app.resources"
}

compose.desktop {
    application {
        mainClass = "cp.player.app.MainKt"

        // 换 JBR：让 jpackage 用 JetBrains Runtime 做运行时镜像。
        //
        // ⚠️ **没解析到就不设**，不能设成 null / 不存在的路径 ——
        // 「设了但其实是默认 JDK」是最坏的结局：产物里没有 JbrMoveHandler，
        // 但日志里看起来「已经换过了」，下一次排查会从错误的前提出发。
        // 这里顺手打一行日志，让 CI 与本地都能从输出直接看出到底换没换。
        if (resolvedJbrHome != null) {
            javaHome = resolvedJbrHome!!.absolutePath
            logger.lifecycle("CPPlayer: JBR runtime = ${resolvedJbrHome!!.absolutePath}")
        } else {
            logger.lifecycle(
                "CPPlayer: JBR 未启用 —— 将使用当前 JDK 打运行时。" +
                    "如需启用：-Pcp.jbrDownload=true 或 -Pcp.jbrHome=<dir>" +
                    "（见 docs/dev/JBR_PACKAGING.md）。",
            )
        }

        // FFM 直接调 Win32（DWM 圆角，见 WindowsWindowCorners）在 JDK 24+ 需要显式放行原生访问。
        // 不放行目前只是打警告，但后续 JDK 会直接拒绝，所以现在就加上。
        //
        // ⚠️ 后四个 `-Dcp.player.*` 是**桌面端版本元数据的唯一入口**。
        // `BuildInfo` 就是读这四个系统属性，而 Gradle 属性（`-Papp.versionName=…`）
        // **不会自动变成 JVM 系统属性** —— 少了这几行，打包出的 MSI/deb 里
        // 「关于」页永远显示 v1.0.0 (1) / unknown / stable，与 tag 无关。
        // 它们同时作用于 `run` 任务与 jpackage 生成的启动器（.cfg），两处都需要。
        //
        // ⚠️ 内存参数（2026-10-04）：release 包此前没有设任何堆参数，JVM 默认
        // 最大堆 = 物理内存的 1/4、**初始堆 = 物理内存的 1/64**（32GB 机器上启动即
        // 提交 512MB），G1 又几乎从不把涨上去的堆还给 OS ⇒ 空闲占用 780MB。
        // 这一组对「音乐播放器」这种长驻 GUI 应用是稳态组合：
        //   -Xms128m / -Xmx512m —— 初始堆压小；上限 512m 对 UI + 列表 + 封面绰绰有余。
        //   -XX:SoftMaxHeapSize=256m —— 常态软顶：G1 会尽量把堆压在 256m 内，
        //     只有连续压力才越线（软顶不是硬顶，OOM 仍由 -Xmx 兜底）。
        //   -XX:MinHeapFreeRatio=10 / MaxHeapFreeRatio=30 —— 空闲比例从默认(20/45? /40/70)
        //     收紧：并发周期/Full GC 结束后按比例**收缩已提交堆**，把内存还给 OS。
        //   -XX:G1PeriodicGCInterval=180000 —— 空闲 3 分钟触发一次并发周期，
        //     配合上面两个 ratio 让「放歌暂停后内存缓慢回落」成立（G1 没有它就不收缩）。
        //   -XX:+UseStringDeduplication —— G1 免费午餐：歌单里海量重复字符串
        //     （歌手名 / 来源 / URL 前缀）共享同一份 char[]，省堆且几乎零成本。
        jvmArgs += listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-Xms128m",
            "-Xmx512m",
            "-XX:SoftMaxHeapSize=256m",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
            "-XX:G1PeriodicGCInterval=180000",
            "-XX:+UseStringDeduplication",
            "-Dcp.player.versionName=$appVersionName",
            "-Dcp.player.versionCode=$appVersionCode",
            "-Dcp.player.releaseChannel=$appReleaseChannel",
            "-Dcp.player.gitSha=$gitSha",
        )
        nativeDistributions {
            // ⚠️ 故意没有 TargetFormat.Msi：Windows 安装包改由 Velopack 从 app-image 打
            // （见文件末尾的 `packageWindowsVelopack`）。jpackage 的 MSI 是内置 WiX 模板
            // 直出，`--resource-dir` 又不对外暴露 ⇒ 「装新版不继承上次的安装目录」
            // 「目录选择框是老式对话框」「向导步骤多」这些都属于 MSI 的固有行为，
            // 在本仓库的配置层面改不动。详见 RELEASE.md「Windows 安装包」。
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = cpPackageName
            packageVersion = appPackageVersion
            // 「添加/删除程序」里显示的那几行元信息。不填的话 MSI 里厂商是 Unknown、
            // 描述为空，用户在程序列表里认不出这是什么。
            // 注意：值来自上面的 appDescription，那里写了为什么必须是 ASCII。
            description = appDescription
            vendor = "CPPlayer"
            // ⚠️ 不能直接写 `java.time.Year.now()`：Kotlin 默认只导入 java.lang/io/util，
            // 而 `java` 在这个脚本作用域里被 Gradle 的属性遮蔽了，会报
            // "Unresolved reference 'time'"。所以走文件顶部的 import。
            copyright = "Copyright © ${Year.now()} CPPlayer"

            // 图标：三个平台各要各的格式（Windows .ico / macOS .icns / Linux .png）。
            // 由 scripts/gen_app_icon.py 生成，改主色或改形状后重跑一次即可。
            // ⚠️ 不放在 src/desktopMain/resources 下 —— 那会被打进运行时 jar，
            // .icns 有 300KB，白占体积。
            // ⚠️ Windows 侧 jpackage **只出 app-image**（`createDistributable`），安装包由
            // Velopack 从这份镜像打（见文件末尾的 `packageWindowsVelopack`）。所以这里
            // 只留 iconFile —— 快捷方式、安装目录、升级识别全部归 Velopack 管。
            //
            // 原来的 `shortcut` / `menu` / `menuGroup` / `perUserInstall` / `dirChooser` /
            // `upgradeUuid` 一律删掉了：它们**只对 jpackage 的 msi / exe 生效**（jpackage
            // 对 `--type app-image` 会拒收其中一部分），在本场景下是死配置，留着只会
            // 让人误以为「改这里能改安装行为」——而那恰恰是换 Velopack 的原因。
            //
            // 若将来要回退到 MSI：`upgradeUuid` 必须**原样沿用**
            // `4A6A38AA-6BC8-4526-9EEF-92A5499DEB9C`（见 RELEASE.md），否则装新版会报
            // "已安装此产品的另一个版本"，旧版本也卸不干净。
            windows {
                iconFile.set(project.file("desktop-icons/icon.ico"))
            }
            macOS {
                iconFile.set(project.file("desktop-icons/icon.icns"))
                bundleID = "cp.player"
                appCategory = "public.app-category.music"
            }
            linux {
                iconFile.set(project.file("desktop-icons/icon.png"))
                shortcut = true
                appCategory = "Audio"
                // ⚠️ 别在这儿填 debMaintainer：deb 的 Maintainer 字段必须是
                // "Name <email>" 形式，填错 dpkg-deb 直接失败；不填 jpackage 有默认值。
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 通用 Linux 分发包（tar.gz）：把 createDistributable 的 Linux 应用镜像直接打包，
// 「解压即用」。面向不用 deb / AUR 的发行版，同时是 AUR cpplayer-bin 的源包。
//
// 为什么不加 TargetFormat.AppImage：那是 AppImageKit 的自挂载格式（还要额外下载
// appimagetool），行为与普通目录不同；要的就是 jpackage 的 app-image 本身 ——
// 自带 jlink 裁剪好的 JBR 运行时与 bin/CPPlayer 启动器。
//
// 路径依据（compose 插件 1.12.1 源码逐行核对，改插件版本后如断言报错按此更新）：
//   - configureJvmApplication.kt：createDistributable = AbstractJPackageTask(
//       args = listOf(TargetFormat.AppImage))
//   - AbstractJPackageTask.kt：jpackage `--type <AppImage.id> --dest <destinationDir>`
//   - destinationDir = outputBaseDir / "main" / format.outputDirName
//     （TargetFormat.kt：AppImage.outputDirName = "app"；outputBaseDir 默认
//      build/compose/binaries —— CI 对 binaries/main/velopack 的 glob 是同一套约定）
//   ⇒ 应用镜像目录 = build/compose/binaries/main/app/CPPlayer/
//
// ⚠️ 只能在 Linux 宿主上构建：createDistributable 产出**当前宿主平台**的应用镜像
// （Windows 上是 .exe 启动器 + Windows JBR），拿去打 tar.gz 是错的。因此：
//   - 非 Linux 宿主上**不挂 dependsOn**（不会先白打一遍 jpackage 再报错）；
//   - doFirst 再拦一道直接报清晰错误。刻意不用 onlyIf —— SKIPPED + BUILD
//     SUCCESSFUL 的「假成功」正是本仓库明令禁止的失败模式。
//
// ⚠️ 不要显式设 fileMode / dirMode：CopySpec 默认（null）在 POSIX 文件系统上
// **原样保留** jpackage 产出的权限位（启动器与运行时二进制是 755、jspawnhelper 755）；
// 显式设档位反而会掩盖 Windows 上「文件系统根本没有执行位」的事实。
// 这也是禁止在非 Linux 宿主运行的第二个理由。
val packageLinuxTarGz = tasks.register<Tar>("packageLinuxTarGz") {
    group = "compose desktop"
    description =
        "把 createDistributable 的 Linux 应用镜像打成 tar.gz（通用 Linux 分发，解压即用）。仅限 Linux 宿主。"
    val onLinux = System.getProperty("os.name").lowercase().contains("linux")
    if (onLinux) {
        dependsOn(tasks.named("createDistributable"))
    }
    // `from(<app镜像目录>)` 会把目录内容拷到顶层 —— 用嵌套 into() 垫回 CPPlayer/ 前缀，
    // tar 顶层才是 CPPlayer/（与 /opt/CPPlayer 的安装布局、PKGBUILD 的假设一致）。
    from(layout.buildDirectory.dir("compose/binaries/main/app/$cpPackageName")) {
        into(cpPackageName)
    }
    // 文件名用 versionName（可带预发布后缀）；AUR 侧 pkgver 由 CI 剥后缀。
    archiveFileName.set("$cpPackageName-$appVersionName-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/targz"))
    compression = Compression.GZIP
    doFirst {
        check(onLinux) {
            "packageLinuxTarGz 只能在 Linux 宿主上运行：createDistributable 产出的是" +
                "**当前宿主平台**的应用镜像（Windows 上是 .exe + Windows JBR），" +
                "打成 Linux tar.gz 是错的。请在 Linux 上或 CI 的 ubuntu runner 构建。"
        }
        val appDir = layout.buildDirectory.dir("compose/binaries/main/app/$cpPackageName").get().asFile
        check(appDir.resolve("bin").isDirectory && appDir.resolve("lib/runtime").isDirectory) {
            "createDistributable 产物布局不符合预期（缺 bin/ 或 lib/runtime/）：${appDir.absolutePath}\n" +
                "若 compose 插件改了输出目录，请按本任务上方注释里的出处同步更新路径。"
        }
    }
}

// ---------------------------------------------------------------------------
// Windows 安装包（Velopack）：把 createDistributable 的 Windows 应用镜像打成
// `CPPlayer-<version>-win-Setup.exe`，并产出更新源（`*.nupkg` + `RELEASES`）。
//
// ## 为什么换掉 jpackage 的 MSI（2026-10-05 定案）
//
// MSI 的三个毛病——装新版**不继承上次的安装目录**、目录选择框是 WiX 的老式对话框、
// 向导步骤多——全是 jpackage 内置 WiX 模板的行为。`--resource-dir` 不对外暴露
// （Compose 插件也没透出），所以在本仓库的配置层面**改不动**，只能换打包后端。
//
// Velopack = Squirrel.Windows 的官方继任者，Rust 实现、**语言无关**（官方把 Java 列为
// 一等支持）。它的解法是「取消问题」而不是「修好它」：不做目录选择页，直接装到
// %LOCALAPPDATA%\CPPlayer，双击 Setup.exe 即装完并自动启动；升级走 delta 差分，由
// `Update.exe` 静默替换，用户不必再走一遍向导。顺带把「每次升级重下整个 ~80MB 的
// JBR runtime」变成只下差异。
//
// ⚠️ 只能在 Windows 宿主上构建：createDistributable 产出的是**当前宿主平台**的镜像
// （Linux 上是 ELF 启动器 + Linux JBR），拿去打 Windows 包是错的。与 packageLinuxTarGz
// 同一条纪律：非 Windows 宿主**不挂 dependsOn**（不会先白打一遍 jpackage 再报错），
// doFirst 里再明确拦一道。
//
// ⚠️ vpk 是 .NET global tool。CI 用 `dotnet tool install --tool-path <dir>` 装到工作区，
// 再经 `-Pcp.vpkPath=<dir>/vpk.exe` 传进来 —— 不能赌 `~/.dotnet/tools` 在 GitHub
// runner 的 PATH 上（原生 runner 不保证）。
//
// ⚠️ `--skipVeloAppCheck`：Velopack 默认要求应用在启动早期调用自家的 VelopackApp
// builder（安装/更新后的回调钩子）。JVM 侧不引它的 SDK，更新改由 `Update.exe`
// 命令行驱动，所以必须跳过这项校验，否则 vpk 直接报错退出。
//
// ⚠️ `--shortcuts Desktop,StartMenuRoot`：合法值只有 `Desktop` / `StartMenuRoot` /
// `StartMenu` / `Startup` / `None`。**`StartMenu` 是 StartMenuRoot 里的子文件夹**，
// 且官方文档明写它「必须同时指定 --packAuthors」——用它会在开始菜单多出一层目录。
// 这里用 `StartMenuRoot`：快捷方式直接落在开始菜单根，与 WindowsSmtcIdentity 自建的那个
// `Programs\CPPlayer.lnk` 同一层。
//
// ⚠️ `--aumid cp.player.CPPlayer`：**这才是让两套快捷方式不打架的关键**。
// WindowsSmtcIdentity（`WindowsSmtcIdentity.desktop.kt`）靠「设显式 AUMID + 开始菜单里
// 放一个带该 AUMID 属性的 .lnk」拿 SMTC 面板的「来源应用」名字与图标，否则系统显示
// 「未知应用」。安装器生成的 .lnk 不带 AUMID 属性 → 两处入口各说各话。`--aumid`
// 让 Velopack 写进自己的快捷方式，值必须与代码里的 `WindowsSmtcIdentity.APP_ID` 一致；
// 改代码里的 APP_ID 时这里要同步（两处不一致 = SMTC 身份失效）。
// ⚠️ 别改成 StartMenu 子目录「错开」——那只是让两个同名入口互相看不见，AUMID 仍然对不上。
val packageWindowsVelopack = tasks.register<Exec>("packageWindowsVelopack") {
    group = "compose desktop"
    description =
        "把 createDistributable 的 Windows 应用镜像打成 Velopack 安装包（Setup.exe + 更新源）。仅限 Windows 宿主。"
    val onWindows = System.getProperty("os.name").lowercase().contains("win")
    if (onWindows) {
        dependsOn(tasks.named("createDistributable"))
    }

    val vpkOverride = (project.findProperty("cp.vpkPath") as String?)?.trim()?.ifBlank { null }
    val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app/$cpPackageName")
    val outputDir = layout.buildDirectory.dir("compose/binaries/main/velopack")
    val icoFile = project.file("desktop-icons/icon.ico")

    // commandLine 必须在配置期定下来；平台不对 / vpk 缺失由下面的 doFirst 报清晰错误。
    //
    // 参数名与取值全部核对自 vpk 1.2.x 的 `vpk pack -H`（Oakton 参数解析器）——
    // vpk **不认 `--version`**，也**没有 `--msi`** 这类旧参数，凭记忆写必错。
    // 每个参数的用途见上方注释。
    commandLine(
        // ⚠️ `--yes`：vpk 在部分非交互场景会提问等输入（Oakton 的 confirm prompt），
        // CI 无 TTY ⇒ 任务永久挂住直到超时。显式答「是」把它消掉。
        vpkOverride ?: "vpk", "pack",
        "--yes",
        "--packId", cpPackageName,
        // ⚠️ 必须是**合法 SemVer**。appPackageVersion 已剥掉预发布后缀
        // （`1.2.3-beta.1` → `1.2.3`），否则 vpk 解析失败。
        "--packVersion", appPackageVersion,
        // 快捷方式显示名 & 默认文件夹名都取自 packTitle。
        "--packTitle", cpPackageName,
        // StartMenu（子目录形式）要求这个字段；给了它以后将来切过去也不用补参数。
        "--packAuthors", "CPPlayer",
        "--packDir", appImageDir.get().asFile.absolutePath,
        // 只给**文件名**，不给路径（官方原话 "The file name (not path)"）。
        "--mainExe", "$cpPackageName.exe",
        "--icon", icoFile.absolutePath,
        "--outputDir", outputDir.get().asFile.absolutePath,
        // 无参数时 vpk 默认 `Releases`（相对 CWD）⇒ Gradle 每次都从仓库根解析，产物散落。
        // 显式给绝对路径才是我们要的 app/build/compose/binaries/main/velopack。
        "--shortcuts", "Desktop,StartMenuRoot",
        // 必须与 WindowsSmtcIdentity.APP_ID 一致，见上方注释。
        "--aumid", "cp.player.CPPlayer",
        "--skipVeloAppCheck",
    )
    // 声明输出目录让 Gradle 认得这是有产物的任务；doFirst 里清空则是为了不让
    // vpk 留下的旧 RELEASES / 旧 nupkg 混进本次产物。
    outputs.dir(outputDir)

    doFirst {
        check(onWindows) {
            "packageWindowsVelopack 只能在 Windows 宿主上运行：createDistributable 产出的是" +
                "**当前宿主平台**的应用镜像（Linux 上是 ELF 启动器 + Linux JBR），" +
                "打成 Windows 安装包是错的。请在 Windows 上或 CI 的 windows runner 构建。"
        }
        if (vpkOverride != null) {
            check(File(vpkOverride).isFile) {
                "cp.vpkPath 指向的 vpk 不存在：$vpkOverride\n" +
                    "安装：`dotnet tool install --tool-path <目录> vpk`，再把 <目录>/vpk.exe 传进来。"
            }
        }
        check(icoFile.isFile) { "缺图标文件：${icoFile.absolutePath}（先跑 scripts/gen_app_icon.py）" }
        val appDir = appImageDir.get().asFile
        // Windows 的 app-image 布局是 `<name>/<name>.exe` + `<name>/app/`（jar 与 .cfg）
        // + `<name>/runtime/`（jlink 裁剪的 JBR）——与 Linux 的 bin/ + lib/ 不同。
        check(
            appDir.resolve("$cpPackageName.exe").isFile &&
                (appDir.resolve("app").isDirectory || appDir.resolve("runtime").isDirectory)
        ) {
            "createDistributable 产物布局不符合预期（缺 $cpPackageName.exe，或 app/ 与 runtime/ 都没有）：${appDir.absolutePath}\n" +
                "若 compose 插件改了输出目录，请按本任务上方注释里的出处同步更新路径。"
        }
        outputDir.get().asFile.deleteRecursively()
    }

    doLast {
        val out = outputDir.get().asFile
        val setup = out.listFiles()?.firstOrNull { it.name.endsWith("-Setup.exe", ignoreCase = true) }
            ?: error("vpk 没产出 Setup.exe：${out.absolutePath}")
        check(out.resolve("RELEASES").isFile) {
            "vpk 没产出 RELEASES 更新清单：${out.absolutePath}\n" +
                "没有它，应用内的 Velopack 更新源就不可用。"
        }
        logger.lifecycle("Velopack 产物：${setup.name}（${setup.length() / 1024 / 1024} MiB）in ${out.absolutePath}")
    }
}

// ---------------------------------------------------------------------------
// 本地开发运行 = debug 构建。
//
// `AppVersion.isDebugBuild` 的判定是「releaseChannel != stable」，而上面的 jvmArgs
// 对 run 任务与 jpackage 启动器是**同一份**（渠道都是 stable）。这里给 run 家族任务
// 在其后追加 `-Dcp.player.releaseChannel=debug` —— JVM 系统属性**后写的 -D 覆盖
// 先写的**，于是：
// - `desktopRun` / `run` / 热重载等本地运行 → debug 渠道，debugOnly 的设置入口
//   （渲染后端、重看新手引导，见 SettingsRegistry）可见；
// - jpackage 打包产物与 `*Distributable`（跑的就是打包产物本身）不受影响，
//   渠道保持 stable —— 正式版用户看不到 debug 入口。
//
// 用 matching + configureEach 而非 tasks.named：这些任务由插件创建，个别可能不存在
// （如未应用 hot-reload 插件时的 hotRunDesktop），写死 named 会直接抛 MissingTaskException。
// ⚠️ 必须包在 afterEvaluate 里：compose 插件在它自己的 afterEvaluate 里**整体覆写**
// run 任务的 jvmArgs（探针验证过），脚本期直接 append 会被冲掉；本 afterEvaluate
// 注册在插件之后、回调也更晚执行，追加才能留在最终值里。
afterEvaluate {
    // 让本地 run 家族与打包产物跑在**同一个运行时**（JBR）上。
    //
    // 为什么必须改（2026-10-04 只读探针实测，:app:help + init 脚本打印任务属性）：
    // - `:app:run`（compose 插件的任务）：javaLauncher 已被插件指到
    //   `compose.desktop.application.javaHome`（.jbr）——不用管；
    // - `:app:desktopRun`（**Kotlin KMP 的 KotlinJvmRun**，不是 compose 的任务）：
    //   javaLauncher 默认 = Gradle 守护进程的 JDK（本机 Zulu 25）。
    //   而 JBR 的 `WindowDecorations`（自定义标题栏 / 原生贴边吸附的前提）只在 JBR 里有，
    //   于是 desktopRun 下 `JbrWindowChrome.isSupported` 恒为 false，窗口永远走
    //   `Undecorated` 模拟路 —— 「跑了 JBR 还是没吸附」就是这么来的。
    //
    // KotlinJvmRun 继承 JavaExec：javaLauncher 非 null 时**优先于** executable，
    // 所以必须先把它清空再设 executable，否则赋值被无声忽略。
    val runOnJbrJava = resolvedJbrHome
        ?.let { File(it, if (hostJbrPlatform == "windows") "bin/java.exe" else "bin/java") }
        ?.takeIf { it.isFile }
    if (resolvedJbrHome != null && runOnJbrJava == null) {
        logger.warn("CPPlayer: JBR 目录里找不到 bin/java，desktopRun 家族将退回守护进程 JDK（窗口走自绘无边框模拟路）")
    }
    val runFamilyTaskNames = listOf(
        "run",
        "runRelease",
        "desktopRun",
        "desktopRunHot", // hotRunDesktop 的废弃别名，留着以防旧脚本/IDE 配置还在用
        "hotRunDesktop",
        "hotRunDesktopAsync",
    )
    runFamilyTaskNames.forEach { taskName ->
        tasks.matching { it.name == taskName }.configureEach {
            if (this is JavaExec) {
                jvmArgs("-Dcp.player.releaseChannel=debug")
            } else {
                logger.warn("CPPlayer: 任务 $taskName 不是 JavaExec，无法注入 debug 渠道标记")
            }
        }
    }
    if (runOnJbrJava != null) {
        val applyJbrRuntime: (JavaExec) -> Unit = { task ->
            runCatching {
                // ⚠️ 不能写 `executable = …`：JavaExec 的 setter 是 setExecutable(Object)，
                // 与 String getter 不配对，Kotlin 不会合成属性，赋值会编译失败/错绑。
                //
                // KGP（registerMainRunTask，KotlinJvmRun.kt:121）在**任务创建时**给
                // javaLauncher 设 convention = toolchain launcher（= 守护进程 JDK）。
                // JavaExec 规则：javaLauncher 在场时优先于 executable —— 所以唯一稳妥的
                // 做法是把 **value** 设成指向 JBR 的自定义 JavaLauncher：
                //   - value 永远压过 convention，与本动作和 KGP 注册动作谁先谁后无关；
                //   - 不要去 set(null)/convention(null)：在 Gradle 9 上会与 KGP 的
                //     convention() 撞出 "property is final"（已实测，堆栈落在
                //     KotlinJvmRunKt$registerKotlinJvmRun$1.execute）。
                val jbrLauncher = object : JavaLauncher {
                    override fun getExecutablePath(): RegularFile =
                        objects.fileProperty().also { it.set(runOnJbrJava) }.get()

                    // JavaExec 只消费 executablePath；metadata 只是个描述，全部给占位值。
                    override fun getMetadata(): JavaInstallationMetadata =
                        object : JavaInstallationMetadata {
                            override fun getLanguageVersion() = JavaLanguageVersion.of(21)
                            override fun getJavaRuntimeVersion() = "21"
                            override fun getJvmVersion() = "21"
                            override fun getVendor() = "JetBrains s.r.o."
                            override fun getInstallationPath() =
                                layout.projectDirectory.dir(runOnJbrJava.parentFile.parentFile.path)
                            override fun isCurrentJvm() = false
                        }
                }
                task.javaLauncher.value(jbrLauncher)
                task.setExecutable(runOnJbrJava.absolutePath)
            }.onFailure {
                // hotRunDesktop 的 javaLauncher 被热重载插件 final 化，改不动；跳过即可。
                logger.warn("CPPlayer: 未能把 ${task.name} 指到 JBR（${it.message}）")
            }
        }
        // configureEach：对现在与将来注册的同名任务都生效（desktopRun 由 KGP
        // 惰性注册，注册动作晚于本 afterEvaluate，靠这条兜住）。
        tasks.withType(JavaExec::class.java)
            .matching { it.name in runFamilyTaskNames }
            .configureEach(applyJbrRuntime)
    }
}