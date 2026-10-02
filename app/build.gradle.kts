import java.security.MessageDigest
import java.time.Year
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.gradle.api.tasks.JavaExec
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

// ⚠️ 这个字符串只能用 ASCII，**一个中文都不能有**。
//
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
private val JbrSha256 = mapOf(
    "windows" to "22704601a5fffc9b5f43c2c4e8650d3ae92905139dc11e99c48387e2475be938",
    // ⚠️ Linux 侧只有 `.tar.gz`：`.zip` 是 403。Windows 侧两者都有，用 .zip。
    "linux" to "34a7ae7b3b45af5c8a3388a7305ad96712956a03ee8d82c7f928f31e7a79fa8e",
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
 * 把 JBR 铺到 `.jbr/<platform>-x64/`。**幂等**：已有合法运行时就直接返回（CI 每次全量冷跑，
 * 不幂等会白下 ~90MB）。
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
    val url = "$jbrBaseUrl/jbr-$jbrVersion-$platform-x64-$jbrBuild.$ext"
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
// 默认 **off**：本地不带这个开关时构建完全不受影响（不会突然卡 90MB 下载）。
private var resolvedJbrHome: File? = resolveJbrHome()
if (resolvedJbrHome == null && (findProperty("cp.jbrDownload") as String?) == "true") {
    resolvedJbrHome = ensureJbrDownloaded()
}

// 兜底断言：CI 的 Windows runner 上只会甩出一个 311，从日志根本看不出是文案问题。
// 在配置期就拦住，省得下次有人把中文改回来又排查一轮。
// description 是全平台共用的（deb / dmg / msi 同一个字段），MSI 是最严格的那个，
// 所以这里不区分宿主平台。
require(appDescription.none { it.code > 0x7F }) {
    "nativeDistributions.description 含非 ASCII 字符：" +
        "MSI 数据库 codepage 固定为 1252，light.exe 会报 LGHT0311（退出码 311）。" +
        "见本文件 appDescription 上方的注释。"
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
        jvmArgs += listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-Dcp.player.versionName=$appVersionName",
            "-Dcp.player.versionCode=$appVersionCode",
            "-Dcp.player.releaseChannel=$appReleaseChannel",
            "-Dcp.player.gitSha=$gitSha",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            packageName = "CPPlayer"
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
            windows {
                iconFile.set(project.file("desktop-icons/icon.ico"))
                // 桌面快捷方式 + 开始菜单快捷方式（jpackage 默认两个都关）。
                shortcut = true
                menu = true
                menuGroup = packageName
                // 免管理员安装（装到 %LOCALAPPDATA%\Programs 而不是 Program Files），
                // 装的时候不弹 UAC；同时让用户自己挑目录。
                perUserInstall = true
                dirChooser = true
                // ⚠️ 这个 UUID 一旦发布就**永远不能再改**：Windows Installer 靠它判定
                // "新包是同一个应用的升级版"。改了会怎样？装新版本时报
                // "已安装此产品的另一个版本"，而且旧版本卸不干净。
                upgradeUuid = "4A6A38AA-6BC8-4526-9EEF-92A5499DEB9C"
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
    listOf(
        "run",
        "runRelease",
        "desktopRun",
        "desktopRunHot", // hotRunDesktop 的废弃别名，留着以防旧脚本/IDE 配置还在用
        "hotRunDesktop",
        "hotRunDesktopAsync",
    ).forEach { taskName ->
        tasks.matching { it.name == taskName }.configureEach {
            if (this is JavaExec) {
                jvmArgs("-Dcp.player.releaseChannel=debug")
            } else {
                logger.warn("CPPlayer: 任务 $taskName 不是 JavaExec，无法注入 debug 渠道标记")
            }
        }
    }
}