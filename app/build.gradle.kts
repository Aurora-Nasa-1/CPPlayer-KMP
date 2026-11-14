import java.security.MessageDigest
import java.time.Year
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.gradle.api.tasks.JavaExec
import org.gradle.api.file.RegularFile
import org.gradle.jvm.toolchain.JavaInstallationMetadata
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.desktop.application.dsl.NativeImageGarbageCollector
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.nucleus)
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
 * 解析要打进产物的 JBR / JDK 目录，**按优先级**：
 *
 * 1. `-Pcp.jbrHome=/abs/path` —— 本地调试 / CI 显式指定，最高优先级；
 * 2. 环境变量 `CP_JBR_HOME`；
 * 3. `-Pcp.jdk25=true` —— 显式请求使用 JDK 25 运行时（检查 dist-jbr/windows-x64、E:/java、JAVA_HOME）；
 * 4. 仓库内 `.jbr/<platform>-x64/` —— 由 CI 或 [ensureJbrDownloaded] 铺好。
 *
 * 全都没命中 ⇒ `null`（构建继续用默认 JDK，并在配置期打印一行提示）。
 */
private fun resolveJbrHome(): File? {
    val prop = (findProperty("cp.jbrHome") as String?)?.takeIf { it.isNotBlank() }
    val env = System.getenv("CP_JBR_HOME")?.takeIf { it.isNotBlank() }
    val useJdk25 = ((findProperty("cp.jdk25") ?: rootProject.findProperty("cp.jdk25")) as String?) == "true"
    val jdk25Candidates = if (useJdk25) {
        listOf(
            rootProject.layout.projectDirectory.dir("dist-jbr/windows-x64").asFile,
            File("E:/java"),
            System.getenv("JAVA_HOME")?.let(::File),
        ).filterNotNull().filter { it.resolve("release").exists() }
    } else {
        emptyList()
    }
    val inRepo = hostJbrPlatform
        ?.let { rootProject.layout.projectDirectory.dir(".jbr/$it-x64").asFile }
        ?.takeIf { it.resolve("release").exists() } // `release` 是 JDK 布局的标志文件
    return (listOfNotNull(prop?.let(::File), env?.let(::File)) + jdk25Candidates + listOfNotNull(inRepo))
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
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    android {
        namespace = "cp.player.app.lib"
        // 与 app-android 对齐：material-kolor 5.x 要求 compileSdk ≥ 37。
        compileSdk = 37
        // 24 = Android 7.0（见 app-android 的 minSdk 注释）。
        minSdk = 24
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
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
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
                // 把 material3 顶到 1.12.0-alpha03（插件的 compose.material3 只有 1.9.0，
                // 缺少 M3 Expressive 的 MaterialShapes / 波形进度条 / 变形加载指示器 / SplitButton / 浮动工具栏等）。
                // 版本冲突时 Gradle 取高者，所以这一行足以覆盖插件给的 1.9.0。
                implementation(libs.material3)
                implementation(libs.compose.material3.adaptive)
                implementation(libs.compose.material3.adaptive.layout)
                implementation(libs.compose.material3.adaptive.navigation)
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
                // 播放页流体背景：SkSL 运行时着色器 → Modifier.shaderBackground。
                // ⚠️ 只有 core 模块是 MIT；`hypnoticcanvas-shaders` 里多数着色器是
                // CC-BY-NC-SA（非商用），**别加**。版本联动关系见 libs.versions.toml。
                implementation(libs.hypnoticcanvas)
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
                implementation(libs.nucleus.application)
                implementation(libs.nucleus.decorated.window.tao)
                implementation(libs.nucleus.graalvm.runtime)
                // 无 AWT 托盘与原生系统通知。通知模块按平台分支，两者都只在对应 OS 上加载
                // 自己的 JNI 库（`isAvailable` 为假时是纯 no-op），所以可以同时挂在类路径上。
                implementation(libs.composenativetray)
                implementation(libs.nucleus.notification.windows)
                implementation(libs.nucleus.notification.linux)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sentry.core)
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

// ---------------------------------------------------------------------------
// 桌面端现代 GC、JDK 25 与运行时极致调优策略
//
// 1. Generational Shenandoah (JDK 25 JEP 521 / JEP 404):
//    - 将堆划分为年轻代与老年代，分代并发回收；
//    - 针对 Compose UI 重组、音频缓冲流与网络解析这类短命对象实施高频微秒级并发清理，停顿 < 1ms，高刷（120/144Hz）绝不掉帧；
//    - 配合主动 Uncommit（-XX:+ShenandoahUncommit, -XX:ShenandoahUncommitDelay=30000），空闲时将闲置物理内存主动返还 OS，后台播放驻留降至 50MB~80MB；
//    - JDK 25 正式晋升为 Product 特性，无需实验参数。
// 2. Generational ZGC: 低至微秒级停顿，超大堆场景下的全并发低延迟策略。
// 3. Compact Object Headers (JDK 25 JEP 519 / JDK 24 JEP 450):
//    - 将 64 位 HotSpot 对象头压缩至 8 字节（节省 33%~50% 对象头空间）；
//    - 针对海量 SongItem / LyricLine / State 对象，全应用堆物理占用立减 15%~25%，大幅提升 CPU L1/L2 缓存行命中率；
//    - JDK 25 下正式晋升为 Product 特性（非默认开启，需显式传入 -XX:+UseCompactObjectHeaders；Gradle 构建自动嗅探运行时并在支持时注入，无需 Experimental 标志）。
// 4. 双轨执行架构 (Dual-Execution Strategy):
//    - 本地开发/调试测试：标准 JVM（JetBrains Runtime / OpenJDK）+ **Serial GC**
//      （与下面 Windows Native Image 的 collector 一致，见 afterEvaluate 里的 run 家族配置），
//      支持快速增量编译与热重载；
//    - Windows 生产发布：采用 GraalVM Native Image 与跨平台 Serial GC，生成无需 JIT 的独立原生机器码；
//    - Linux 生产发布：保持高兼容性与高稳定性标准 JVM（DEB / Tar.gz 交付）。
// ---------------------------------------------------------------------------
val isJdk25OrNewer: Boolean = runCatching {
    val targetReleaseFile = resolvedJbrHome?.resolve("release")
    if (targetReleaseFile?.isFile == true) {
        val text = targetReleaseFile.readText()
        if (text.contains("JAVA_VERSION=\"25") || text.contains("JAVA_RUNTIME_VERSION=\"25")) {
            return@runCatching true
        }
    }
    val ver = System.getProperty("java.version") ?: ""
    val major = ver.substringBefore('.').substringBefore('-').toIntOrNull() ?: 0
    major >= 25 || ((findProperty("cp.jdk25") ?: rootProject.findProperty("cp.jdk25")) as String?) == "true"
}.getOrDefault(false)

/**
 * 把 `cp.gc` 取值映射成 JVM 参数。
 *
 * 独立成函数是为了让本地 run 家族能**强制**换一套 collector（见 afterEvaluate），
 * 而不是对 `desktopCommonJvmArgs` 做字符串减法 —— 后者漏掉一个参数就会变成
 * "Multiple garbage collectors selected" 直接启动失败。
 */
fun gcJvmArgsFor(raw: String): List<String> = when (raw.lowercase()) {
    "shenandoah", "gen-shenandoah", "generational-shenandoah" -> listOf(
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseShenandoahGC",
        "-XX:ShenandoahGCMode=generational",
        "-XX:+ShenandoahUncommit",
        "-XX:ShenandoahUncommitDelay=30000",
        "-XX:+UseStringDeduplication",
    )
    "shenandoah-iu" -> listOf(
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseShenandoahGC",
        "-XX:ShenandoahGCMode=iu",
        "-XX:+ShenandoahUncommit",
        "-XX:ShenandoahUncommitDelay=60000",
        "-XX:+UseStringDeduplication",
    )
    "zgc" -> {
        val args = mutableListOf(
            "-XX:+UseZGC",
            "-XX:ZAllocationSpikeTolerance=5",
            "-XX:+UseStringDeduplication",
        )
        // ⚠️ -XX:+ZGenerational 在 JDK 24.0 起已被彻底移除（JDK 23 默认启用分代，24 移除单代）。
        // 在 JDK 24+ 上传此参数会触发 warning: "Ignoring option ZGenerational; support was removed in 24.0"。
        val ver = System.getProperty("java.version") ?: ""
        val major = ver.substringBefore('.').substringBefore('-').toIntOrNull() ?: 0
        if (!isJdk25OrNewer && major in 21..23) {
            args.add(1, "-XX:+ZGenerational")
        }
        args
    }
    // ⚠️ 这一支**以前是 `else`（catch-all）**：`-Pcp.gc=g1` 和任何拼错的值都会静默落到这里，
    // 于是实际跑的是 JDK 默认 GC（恰好是 G1），但你以为换了别的 GC —— 日志里一句解释都没有。
    // 现在显式列出，未知取值直接让配置期失败（宁可构建挂掉，也不要静默跑错 GC）。
    "g1", "g1gc", "default" -> listOf(
        "-XX:+UseG1GC",
        // 软顶必须**严格小于** -Xmx（256m），否则等于硬顶、形同虚设。
        // 2026-11-05 把 -Xmx 从 512m 降到 256m 后，原来的 256m 就变成了硬顶，因此下调到 192m。
        // ⚠️ 192m 未实测（本机默认走 Shenandoah，G1 路径没跑过）—— 但软顶只是「尽量压」，
        // 不是 OOM 兜底（兜底仍是 -Xmx），风险低。
        "-XX:SoftMaxHeapSize=192m",
        "-XX:MinHeapFreeRatio=10",
        "-XX:MaxHeapFreeRatio=30",
        "-XX:G1PeriodicGCInterval=60000",
        "-XX:+UseStringDeduplication",
    )
    // 本地 run 家族用的分支：与 Windows Native Image 的 collector 保持一致。
    "serial", "serialgc" -> listOf("-XX:+UseSerialGC")
    else -> throw GradleException(
        "cp.gc 不支持的取值：'$raw'。可选：shenandoah（默认）/ shenandoah-iu / zgc / g1 / serial",
    )
}

val chosenGc: String = ((rootProject.findProperty("cp.gc") ?: findProperty("cp.gc")) as String?)?.lowercase() ?: "shenandoah"
val gcJvmArgs: List<String> = gcJvmArgsFor(chosenGc)

/**
 * 所有 GC 选择 / 调参参数（不含 `-XX:+UnlockExperimentalVMOptions`，它只是开关）。
 *
 * run 家族要换成 Serial 时必须先把这组摘干净：JVM 同时看到
 * `-XX:+UseShenandoahGC` 与 `-XX:+UseSerialGC` 会拒绝启动。
 */
val gcSelectionFlags: Set<String> = setOf(
    "-XX:+UseShenandoahGC",
    "-XX:ShenandoahGCMode=generational",
    "-XX:ShenandoahGCMode=iu",
    "-XX:+ShenandoahUncommit",
    "-XX:ShenandoahUncommitDelay=30000",
    "-XX:ShenandoahUncommitDelay=60000",
    "-XX:+UseZGC",
    "-XX:ZAllocationSpikeTolerance=5",
    "-XX:+ZGenerational",
    "-XX:+UseG1GC",
    "-XX:SoftMaxHeapSize=192m",
    "-XX:MinHeapFreeRatio=10",
    "-XX:MaxHeapFreeRatio=30",
    "-XX:G1PeriodicGCInterval=60000",
    "-XX:+UseStringDeduplication",
    "-XX:+UseSerialGC",
)

val enableCompactHeaders: Boolean = ((rootProject.findProperty("cp.compactHeaders") ?: findProperty("cp.compactHeaders")) as String?) == "true" ||
    isJdk25OrNewer

val compactHeadersArgs: List<String> = if (enableCompactHeaders) {
    if (isJdk25OrNewer) {
        listOf("-XX:+UseCompactObjectHeaders")
    } else {
        listOf("-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders")
    }
} else {
    emptyList()
}

val previewJvmArgs: List<String> = if (isJdk25OrNewer) listOf("--enable-preview") else emptyList()

val desktopCommonJvmArgs = (listOf(
    "--enable-native-access=ALL-UNNAMED",
    "-Xms64m",
    "-Xmx256m",
    "-Dcp.player.versionName=$appVersionName",
    "-Dcp.player.versionCode=$appVersionCode",
    "-Dcp.player.releaseChannel=$appReleaseChannel",
    "-Dcp.player.gitSha=$gitSha",
) + previewJvmArgs + gcJvmArgs + compactHeadersArgs).distinct()

/**
 * 本地 run 家族的堆参数（打包产物仍用 `desktopCommonJvmArgs` 的 64/256）。
 *
 * - `-Xmx150m`：本地开发用更窄的堆，尽早暴露窄堆下的 GC 行为；
 * - `-Xmn24m`：Serial GC 的 minor GC 是 STW，年轻代越大单次停顿越长。24m 对应
 *   eden 约 16~19m，把单次 minor 压在几毫秒内，避免 120/144Hz 下整帧掉队；
 * - `-Xms64m` 沿用原值：实测存活集 55~60m，再低会在启动后立刻触发老年代
 *   扩容/Full GC。若还要压 RSS，必须连同峰值路径一起压测后再动。
 */
val desktopRunHeapArgs = listOf("-Xms64m", "-Xmx150m", "-Xmn24m")

nucleus {
    application {
        mainClass = "cp.player.app.MainKt"
        logger.lifecycle("CPPlayer: 桌面端 JVM 调优模式: GC=$chosenGc, CompactHeaders=$enableCompactHeaders, JDK25+=$isJdk25OrNewer")

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
                    "如需启用：-Pcp.jbrDownload=true 或 -Pcp.jbrHome=<dir>。",
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
        //   -Xms64m / -Xmx256m —— 初始堆压小；上限 256m（2026-11-05 由 512m 经 420m 下调）。
        //     实测（打包产物 / 启动 + 首页 + 空闲 120s，每组 60 次采样取尾段中位数）：
        //       | 配置                 | RSS 中位 | p90  | 峰值 |
        //       | 128/420（改前）      | 970MB    | 1084 | 1117 |
        //       | 64/256（现在）       | 886MB    | 934  | 961  |
        //     堆内实测：GC 后存活仅 55~60MB，committed 峰值 229M（89%），最长暂停 0.32ms、
        //     总暂停 10.6ms/110s（周期 26 次 vs 420m 时 13 次 —— 更勤但仍是亚毫秒）。
        //     ⚠️ -Xms 直接等于空闲时的堆常驻（uncommit 只会退到 Xms），所以 128→64 是
        //       稳态收益的主来源；-Xmx 主要压峰值。**再往下调之前必须先压测峰值路径**：
        //     批量歌单加载 / 大 provider 响应 / Base64 歌词。
        //     另：堆外大头是 skia 位图、127 个 jar 的 mmap、metaspace 88MB；
        //     换软件渲染实测还能再省 ~120MB（RSS 848 vs 970），但动画流畅度要让位，未默认启用。
        //   ⚠️ 以下四条**只在 `-Pcp.gc=g1` 时才会被加进去**（见上面的 gcJvmArgs 分支）：
        // 默认 GC 是分代 Shenandoah，它没有 SoftMaxHeapSize/FreeRatio 这套收缩逻辑，
        // 堆会直接贴着 -Xmx 扩张（实测 committed 229M / 上限 256M），靠 uncommit 归还。
        // ⚠️ 且 -Xmx 降到 256m 后，下面的 `SoftMaxHeapSize=256m` 已**等于硬顶、形同虚设**：
        //    走 G1 时若还想保留「常态软顶」的意图，把它改成 192m（必须严格小于 -Xmx）。
        //   -XX:SoftMaxHeapSize=256m —— 常态软顶：G1 会尽量把堆压在 256m 内，
        //     只有连续压力才越线（软顶不是硬顶，OOM 仍由 -Xmx 兜底）。
        //   -XX:MinHeapFreeRatio=10 / MaxHeapFreeRatio=30 —— 空闲比例从默认(20/45? /40/70)
        //     收紧：并发周期/Full GC 结束后按比例**收缩已提交堆**，把内存还给 OS。
        //   -XX:G1PeriodicGCInterval=180000 —— 空闲 3 分钟触发一次并发周期，
        //     配合上面两个 ratio 让「放歌暂停后内存缓慢回落」成立（G1 没有它就不收缩）。
        //   -XX:+UseStringDeduplication —— G1 免费午餐：歌单里海量重复字符串
        //     （歌手名 / 来源 / URL 前缀）共享同一份 char[]，省堆且几乎零成本。
        jvmArgs += desktopCommonJvmArgs
        nativeDistributions {
            // ⚠️ 故意没有 TargetFormat.Msi：Windows 安装包改由 Velopack 从 app-image 打
            // （见文件末尾的 `packageWindowsVelopack`）。jpackage 的 MSI 是内置 WiX 模板
            // 直出，`--resource-dir` 又不对外暴露 ⇒ 「装新版不继承上次的安装目录」
            // 「目录选择框是老式对话框」「向导步骤多」这些都属于 MSI 的固有行为，
            // 在本仓库的配置层面改不动。详见 RELEASE.md「Windows 安装包」。
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)

            // ⚠️ release 的 jlink 镜像是**按模块清单裁剪**的，这份清单必须手工维护
            // （2026-10-05 真实事故：1.4.3 安装包启动即死，本地 `desktopRun` 完全复现不了）。
            //
            // 机制（compose 插件 1.12.1 逐类 javap 核实）：
            //   - 默认模块集只有四个：java.base / java.desktop / java.logging / jdk.crypto.ec
            //     （`JvmApplicationDistributionsKt.DEFAULT_RUNTIME_MODULES`），jlink 再按
            //     `requires` 传递解析成 7 个。**这里面没有任何 JDK 内部/可选模块。**
            //   - `suggestModules`（内部是 jdeps）**只打日志、不参与打包** ——
            //     `AbstractSuggestModulesTask.run()` 把结果过滤后 `logger.quiet` 一行就完了，
            //     不会写进 `nativeDistributions.modules`。所以「插件会自动带上需要的模块」
            //     是错觉，必须手动抄进来。
            //   - `modules(...)` 是**追加**（`JvmApplicationDistributions.modules()` 走 `addAll`），
            //     不会顶掉上面那四个默认模块；但 `setModules(list)` 会整体替换 —— 别用那个。
            //
            // 下面的清单 = `./gradlew :app:suggestModules` 的输出原样抄录。
            // ⚠️ **增删依赖后要重跑 `:app:suggestModules` 并同步这一行**，否则又是「本地能跑、
            // 安装包缺类」——而且缺在类初始化期的话，表现是启动器只打一句
            // `Failed to launch JVM`，栈里连模块名都看不到（就是 1.4.3 的死法）。
            //   - `jdk.unsupported`：`JbrFileDialogBridge` 用 `sun.misc.Unsafe` 翻转
            //     `AccessibleObject.override`，才能调用 JBR 的 `JBRFileDialog` 扩展。
            //     **缺它 = 文件/目录选择器退回旧式对话框**（功能仍在，只是没有
            //     Windows 10/11 现代 Common Item Dialog 与文件夹选择模式）。
            //     1.4.3 的死法是缺模块 + 把 `Unsafe` 当静态类型用 ⇒
            //     `NoClassDefFoundError: sun/misc/Unsafe` 在类初始化期直接退出。
            //     代码侧已改成 `Class.forName("sun.misc.Unsafe")` 纯反射，缺模块只会降级。
            //     ⚠️ 但别因为「有反射兜底」就删掉这一行：JBR 文件对话框真的需要它。
            //   - `java.instrument` / `java.management` / `jdk.security.auth` / `jdk.httpserver`：jdeps 从运行时
            //     类路径里量出来的缺口，目前没有触发崩溃，但迟早会在某条分支上炸。
            modules("java.instrument", "java.management", "jdk.security.auth", "jdk.unsupported", "jdk.httpserver")

            packageName = cpPackageName
            packageVersion = appPackageVersion
            // 「添加/删除程序」里显示的那几行元信息。不填的话 MSI 里厂商是 Unknown、
            // 描述为空，用户在程序列表里认不出这是什么。
            // 注意：值来自上面的 appDescription，那里写了为什么必须是 ASCII。
            description = appDescription
            vendor = "CPPlayer"
            homepage = "https://github.com/Aurora-Nasa-1/CPPlayer-KMP"
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
                // electron-builder 生成 .deb 时需要明确的 maintainer 元数据。
                debMaintainer = "CPPlayer <noreply@github.com>"
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Nucleus 3.x & GraalVM Native Image 构建与运行配置
// 路线划分：
// 1. 本地开发与测试：走标准 JVM（支持 desktopRun / desktopRunMobile / desktopTest，快速迭代与热重载）
// 2. 生产发版：
//    - Windows：采用 Oracle GraalVM Native Image 与 Serial GC 编译为原生机器码；
//    - Linux：保持为通用 JVM 产物（deb 与 tar.gz 分发），不注册 Native Image 构建。
// ---------------------------------------------------------------------------
nucleus {
    application {
        mainClass = "cp.player.app.MainKt"
        graalvm {
            isEnabled.set(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
            imageName.set(cpPackageName)
            toolchain {
                distribution.set(dev.nucleusframework.desktop.application.dsl.GraalvmDistribution.ORACLE)
                // 3.0 的 Oracle 下载器把带点版本当“归档 patch”拼 URL
                // （download.oracle.com/.../archive/graalvm-jdk-25.3.4.1_...zip → 404）。
                // 25i3 走 GDS 创新版通道，实际内容仍是 GraalVM 25.3.4.1，
                // 与 CI 里固定的 GDS 归档一致；CI 的 GRAALVM_HOME 会优先命中，不重复下载。
                version.set("25i3")
            }
            // 无应用 PGO profile 时使用 Oracle 的 ML Profile Inference；应用 PGO 时 Native Image 会自动停用它。
            mlProfileInference.set(true)
            // ── GraalVM Native Image 反射 / 可达性元数据三级管理（Nucleus 3.0）────────
            // L1 通用跨平台：`nucleus.graalvm-runtime` JAR 自带的 reachability-metadata.json
            //   （300+ 常用类型、JNI 回调、资源 glob）随 desktopMain 依赖自动进镜像；
            //   .svg/.ttf/.otf、composeResources、META-INF/services 也由它登记，不再手抄。
            // L2 Oracle GraalVM Reachability Metadata Repository：按 runtimeClasspath
            //   自动解析第三方库元数据（Nucleus 3.0 默认开启）。显式钉住仓库版本，
            //   避免插件默认值升级时镜像内容悄悄变化；新增依赖无需再手写 reflection 条目。
            metadataRepository {
                enabled.set(true)
                version.set("1.1.4")
            }
            // L3 平台特定：插件 JAR 内 nucleus/graalvm/platform-metadata/windows-*.json
            //   （AWT / Java2D / 字体等）在 native-image 任务中自动注入。GUI 应用
            //   必须保持 headless = false，否则会连同 L3 GUI 元数据与 Skiko/AWT
            //   原生库一起跳过，镜像启动时才炸。
            headless.set(false)
            // 项目自身资源按 resource roots 自动登记：动态 getResourceAsStream(computedPath)
            // 无法被 native-image 静态发现，漏登记就是运行时静默少文件。
            autoIncludeResources.set(true)
            // 注解处理器按命名约定产出的类（Room *_Impl、Dagger/Hilt *_Factory 等）
            // 静态分析看不见，由插件补 public no-arg <init> 注册。
            detectOrphanProjectClasses.set(true)
            // 这是成品运行时 Java heap 上限（Nucleus 映射为 Native Image runtime 参数），不限制构建器 -Xmx。
            maxHeapSize.set("128m")
            val pgoProfile = layout.projectDirectory.file("graalvm/pgo/default.iprof")
            pgo {
                // 保留 runWithPgoInstrument 训练任务；Nucleus 仅在 profile 文件存在时给正式构建传 --pgo。
                enabled.set(true)
                profile.set(pgoProfile)
            }
            // Windows Native Image 使用 Serial；Windows 不支持 G1 Native Image collector。
            garbageCollector.set(NativeImageGarbageCollector.SERIAL)
            // instrumented 镜像只用于收集 PGO profile，不参与分发。
            // 采用 Quick Build Mode (-Ob) 加快 instrumented 构建并降低编译内存占用；
            // 生产正式镜像仍使用 -O3。
            val pgoInstrumenting: Boolean =
                (findProperty("nucleus.graalvm.pgo") as String?)?.equals("instrument", ignoreCase = true) == true ||
                    gradle.startParameter.taskNames.any {
                        val name = it.substringAfterLast(':')
                        name == "runWithPgoInstrument" || name.endsWith("WithPgoInstrument")
                    }
            if (pgoInstrumenting) {
                optimization.set(dev.nucleusframework.desktop.application.dsl.NativeImageOptimization.QUICK_BUILD)
            } else {
                // Oracle-only 最高优化级别；25.3.4.1 对符合条件的数组算术循环默认执行 SIMD 向量化。
                optimization.set(dev.nucleusframework.desktop.application.dsl.NativeImageOptimization.LEVEL_3)
            }
            buildArgs.addAll(
                "-H:+UnlockExperimentalVMOptions",
                "-H:+CompactingOldGen",
                // ── Serial GC 的帧率优先参数（Compose / Skiko）──────────────────
                // Native Image 的 Serial GC 只有 STW 的 minor / major 两种停顿：
                // 年轻代越大，单次 minor 的复制量越大；120/144Hz 的帧预算只有
                // 8.3/6.9ms，超过预算就是肉眼可见的掉帧。
                // 24m 年轻代（eden 约 16~18m）让 minor 更频繁但每次更短；
                // 老年代仍有 ~104m，相对 55~60m 的存活集有足够晋升余量，
                // 减少必须做整堆 compaction 的 Full GC。`-Xmx` 仍由上面的
                // `maxHeapSize=128m` 写进镜像（Nucleus -> -R:MaxHeapSize=128m）。
                "-R:MaxNewSize=24m",
                // 显式钉住 25.3 的默认策略 Adaptive2，避免升级 GraalVM 时
                // 默认 collector 策略漂移导致停顿曲线悄悄变化。
                "-R:InitialCollectionPolicy=Adaptive2",
                // Oracle Native Image 默认启用 32 位压缩引用；显式固定以保持运行时对象引用占用。
                "-H:+UseCompressedReferences",
                // 引入所有字符集编码支持
                "-H:+AddAllCharsets",
                // 不强制第三方 GUI/native 类在构建期初始化；使用 GraalVM 对安全类的默认判定，
                // 避免把宿主机 Skia、Win32、X11、字体或 AWT 状态写入镜像。
                // Native state/OS handles stay runtime-initialized. Skiko/Skia must not load
                // during analysis; Win32 JNA and Windows AWT backends need target OS libraries.
                "--initialize-at-run-time=kotlin.coroutines.intrinsics.CoroutineSingletons,kotlinx.coroutines.internal.FastServiceLoader,kotlinx.coroutines.EventLoopImplBase,io.ktor.utils.io.jvm.javaio.Pollers,cp.player.core.playback.flick.FlickNativeEngine,cp.player.core.playback.DesktopHiResEngine,cp.player.core.provider.typed.QuickJsWarmup,dev.nucleusframework.composenativetray,dev.nucleusframework.notification,org.jetbrains.skiko,org.jetbrains.skia,org.jetbrains.skia.impl,com.sun.jna.platform.win32,sun.awt.windows,sun.font",
                // 确保生成 100% 独立原生机器码产物，禁止任何回落 JVM 依赖
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "--enable-native-access=ALL-UNNAMED",
            )
            if (pgoInstrumenting) {
                // 实测 Oracle GraalVM 在 --pgo-instrument 下强制使用 level 2 优化（218k 方法），
                // 忽略 -Ob 降级；若无包前缀限制则全量插桩 JDK/第三方全部 218k 方法，极度消耗内存与编译时间。
                // 1. 限制只对应用业务、UI 渲染与核心协程进行插桩剖析；
                // 2. 限制并发编译线程为 1，将 IR 图编译内存峰值砍半（稳态约 3.5~4.5G），防止最后几个巨型方法瞬时 OOM；
                // 3. 搭配 12g 虚拟内存上限与默认 G1GC，留足动态 Region 缓冲；
                // 4. 关闭看门狗超时中断，防止低配 CI 机器在元数据编码期被误杀。
                buildArgs.addAll(
                    "-J-Xmx12g",
                    "-H:NumberOfThreads=1",
                    "-H:DeadlockWatchdogInterval=0",
                    "-H:-DeadlockWatchdogExitOnTimeout",
                    "-H:ProfilingPackagePrefixes=cp.player,dev.nucleusframework,androidx.compose,org.jetbrains.skia,org.jetbrains.skiko,kotlinx.coroutines",
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 运行时镜像深度压缩与符号裁剪（jlink 优化）：
// 默认情况下 jlink 不开启压缩与字节码符号剥离，导致 lib/modules 达 55.6MB。
// 启用 --compress=zip-6、--strip-debug、--no-header-files、--no-man-pages
// 可直接把 modules 压至 25.3MB（骤减 54.5%），分发包全目录仅 57.3MB。
tasks.withType<dev.nucleusframework.desktop.application.tasks.AbstractJLinkTask>().configureEach {
    freeArgs.addAll(
        "--compress=zip-6",
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
    )
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

        // 打包前将预编译原生动态库（如 libsentry.so、librust_lib_flick_player.so）注入到 Linux app-image
        val nativeDir = project.file("native")
        if (nativeDir.isDirectory) {
            val targets = listOf(
                appDir.resolve("lib"),
                appDir.resolve("lib/app"),
                appDir.resolve("lib/runtime/lib"),
            )
            nativeDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                targets.forEach { targetDir ->
                    if (targetDir.isDirectory) {
                        val dest = targetDir.resolve(f.name)
                        f.copyTo(dest, overwrite = true)
                        logger.lifecycle("已注入原生动态库至 Linux 安装镜像: ${f.name} -> ${dest.absolutePath}")
                    }
                }
            }
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
// ⚠️ **它跳过的只是「打包期静态检查」，运行期契约照旧成立，别以为这就完事了**：
// 安装/升级/卸载时，安装器会用 `--veloapp-install|-updated|-obsolete|-uninstall`
// 把主 exe 当钩子拉起，要求 15~30 秒内退出（超时直接杀进程）。不处理它们的后果是
// 安装器弹「安装未完成」——而应用其实已经装好了（2026-11-05 的真实症状）。
// 钩子判据在 `app/src/desktopMain/kotlin/cp/player/app/VelopackHooks.kt`。
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
        val nativeTask = tasks.findByName("packageGraalvmNative")
        if (nativeTask != null) {
            dependsOn(nativeTask)
        } else {
            dependsOn(tasks.named("createDistributable"))
        }
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

        // 生产发版（Windows）：优先采用 GraalVM Native Image 编译产出的独立原生应用镜像
        val graalvmAppDir = layout.buildDirectory.dir("compose/binaries/main/graalvm-app/$cpPackageName").get().asFile
        if (graalvmAppDir.resolve("$cpPackageName.exe").isFile) {
            appDir.mkdirs()
            graalvmAppDir.copyRecursively(appDir, overwrite = true)
            logger.lifecycle("已装配 GraalVM Native Image 原生应用镜像: ${graalvmAppDir.absolutePath} -> ${appDir.absolutePath}")
        } else {
            val nativeCandidates = listOf(
                layout.buildDirectory.file("compose/binaries/main/graalvm-app/$cpPackageName/$cpPackageName.exe").get().asFile,
                layout.buildDirectory.file("compose/tmp/main/graalvm/nativeCompile/$cpPackageName.exe").get().asFile,
                layout.buildDirectory.file("compose/tmp/main/graalvm/output/$cpPackageName.exe").get().asFile,
                layout.buildDirectory.file("nucleus/binaries/main/$cpPackageName.exe").get().asFile,
                layout.buildDirectory.file("native/nativeCompile/$cpPackageName.exe").get().asFile,
            )
            val foundNativeExe = nativeCandidates.firstOrNull { it.isFile }
            if (foundNativeExe != null) {
                appDir.mkdirs()
                val destExe = appDir.resolve("$cpPackageName.exe")
                foundNativeExe.copyTo(destExe, overwrite = true)
                logger.lifecycle("已装配 GraalVM Native Image 原生可执行文件: ${foundNativeExe.absolutePath} -> ${destExe.absolutePath}")
            }
        }

        check(appDir.resolve("$cpPackageName.exe").isFile) {
            "未找到主程序 $cpPackageName.exe（路径：${appDir.absolutePath}）。"
        }

        // 打包前将预编译原生动态库（如 rust_lib_flick_player.dll）注入到 app-image 多重关键位置（exe 同级、app 目录、JRE bin 目录）
        val nativeDir = project.file("native")
        if (nativeDir.isDirectory) {
            val targets = listOf(
                appDir,
                appDir.resolve("app"),
                appDir.resolve("runtime/bin"),
            )
            nativeDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                targets.forEach { targetDir ->
                    if (targetDir.isDirectory) {
                        val dest = targetDir.resolve(f.name)
                        f.copyTo(dest, overwrite = true)
                        logger.lifecycle("已注入原生动态库至安装镜像: ${f.name} -> ${dest.absolutePath}")
                    }
                }
            }
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
// 桌面端手机版界面预览运行任务（desktopRunMobile / runMobile）
// ---------------------------------------------------------------------------
val desktopRunMobile = tasks.register<JavaExec>("desktopRunMobile") {
    group = "compose desktop"
    description = "以手机版界面（窄屏 / 手机布局）在桌面端运行应用（基于 desktopMain classes 与 runtimeClasspath）"
    classpath(
        tasks.named("desktopMainClasses"),
        configurations.named("desktopRuntimeClasspath"),
    )
    mainClass.set("cp.player.app.MainMobileKt")
    args("--mobile")
    jvmArgs(desktopCommonJvmArgs)
    jvmArgs("-Dcp.player.mobilePreview=true")
}

val runMobile = tasks.register<JavaExec>("runMobile") {
    group = "compose desktop"
    description = "以手机版界面（窄屏 / 手机布局）在桌面端运行应用（别名）"
    classpath(
        tasks.named("desktopMainClasses"),
        configurations.named("desktopRuntimeClasspath"),
    )
    mainClass.set("cp.player.app.MainMobileKt")
    args("--mobile")
    jvmArgs(desktopCommonJvmArgs)
    jvmArgs("-Dcp.player.mobilePreview=true")
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
    //   发行版固定跑 JBR；开发态若用别的 JDK，字体回退、Skia 渲染与
    //   JBR 文件对话框都会和用户机器不一致，问题只能等打包后才暴露。
    //
    // KotlinJvmRun 继承 JavaExec：javaLauncher 非 null 时**优先于** executable，
    // 所以必须先把它清空再设 executable，否则赋值被无声忽略。
    val runOnJbrJava = resolvedJbrHome
        ?.let { File(it, if (hostJbrPlatform == "windows") "bin/java.exe" else "bin/java") }
        ?.takeIf { it.isFile }
    if (resolvedJbrHome != null && runOnJbrJava == null) {
        logger.warn("CPPlayer: JBR 目录里找不到 bin/java，desktopRun 家族将退回守护进程 JDK（字体/渲染/文件对话框可能与发行版不一致）")
    }
    val runFamilyTaskNames = listOf(
        "run",
        "runMobile",
        "runRelease",
        "desktopRun",
        "desktopRunMobile",
        "desktopRunHot", // hotRunDesktop 的废弃别名，留着以防旧脚本/IDE 配置还在用
        "hotRunDesktop",
        "hotRunDesktopAsync",
    )
    val configureRunTask: (JavaExec) -> Unit = { task ->
        val existing = task.jvmArgs ?: emptyList()
        // 本地 run 家族固定 Serial GC：与 Windows 生产 Native Image 的 collector 一致，
        // 少几个 GC 线程、启动更直接，也不会让 Shenandoah 的低延迟调参掩盖问题。
        // ⚠️ 必须先把 `desktopCommonJvmArgs` 里那套 GC 参数摘掉再追加，否则 JVM 同时看到
        // 两个 collector 会直接报 "Multiple garbage collectors selected" 起不来。
        val merged = (listOf("-XX:+UnlockExperimentalVMOptions") + existing + desktopCommonJvmArgs)
            .filterNot { it.startsWith("-Dcp.player.releaseChannel=") }
            .filterNot { it in gcSelectionFlags }
            // 堆参数由 run 家族自己拥有：避免共享的 256m 与本地的 150m 同时出现。
            .filterNot { it.startsWith("-Xms") || it.startsWith("-Xmx") || it.startsWith("-Xmn") }
            .plus(gcJvmArgsFor("serial"))
            .plus(desktopRunHeapArgs)
            .plus(
                listOf(
                    "-Dcp.player.releaseChannel=debug",
                    "-Dcp.player.isDesktopRun=true",
                    // GC 日志直接打到终端（刻意不带 `file=`）：本地排查停顿 / 堆曲线只看
                    // 运行窗口这一处，不用再去翻日志文件。留出 uptime 便于和 Skiko 帧率对照。
                    "-Xlog:gc:stdout:time,uptime,level,tags",
                    // Skiko 每帧统计（FPS / 帧耗时）走 stdout，和上面的 GC 日志同屏，
                    // 用来判断掉帧是不是 GC 停顿造成的。
                    "-Dskiko.fps.enabled=true",
                ),
            )
            .distinct()
        task.setJvmArgs(merged)
    }
    tasks.withType(JavaExec::class.java)
        .matching { it.name in runFamilyTaskNames }
        .configureEach(configureRunTask)
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
                            override fun getLanguageVersion() = JavaLanguageVersion.of(if (isJdk25OrNewer) 25 else 21)
                            override fun getJavaRuntimeVersion() = if (isJdk25OrNewer) "25" else "21"
                            override fun getJvmVersion() = if (isJdk25OrNewer) "25" else "21"
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
