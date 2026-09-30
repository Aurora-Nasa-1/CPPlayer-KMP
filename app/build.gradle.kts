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
        compileSdk = 36
        minSdk = 29
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
        }
    }
}