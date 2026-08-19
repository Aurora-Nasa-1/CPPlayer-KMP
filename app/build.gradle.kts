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
                api(project(":kmp-pro"))
                implementation(libs.composemediaplayer.audio)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
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
    }
}

compose.desktop {
    application {
        mainClass = "cp.player.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            packageName = "CPPlayer"
            packageVersion = appPackageVersion
        }
    }
}