import java.time.Year
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

group = "cp.player"
version = providers.gradleProperty("app.versionName").orElse("1.0.0").get()
val appVersionName = providers.gradleProperty("app.versionName").orElse("1.0.0").get()
val appPackageVersion = appVersionName.substringBefore('-').ifBlank { "1.0.0" }

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
                implementation(compose.components.resources)
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
        // FFM 直接调 Win32（DWM 圆角，见 WindowsWindowCorners）在 JDK 24+ 需要显式放行原生访问。
        // 不放行目前只是打警告，但后续 JDK 会直接拒绝，所以现在就加上。
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            packageName = "CPPlayer"
            packageVersion = appPackageVersion
            // 「添加/删除程序」里显示的那几行元信息。不填的话 MSI 里厂商是 Unknown、
            // 描述为空，用户在程序列表里认不出这是什么。
            description = "跨平台音乐播放器（Material 3 Expressive）"
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