import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

group = "cp.player"
version = "1.0.0"

kotlin {
    android {
        namespace = "cp.player.core"
        compileSdk = 36
        minSdk = 29
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        val jvmMain by creating
        jvmMain.dependsOn(commonMain.get())
        androidMain.get().dependsOn(jvmMain)
        val desktopMain by getting
        desktopMain.dependsOn(jvmMain)

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        commonMain.dependencies {
            implementation(libs.composemediaplayer.audio)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // 本地服务器（REST 控制 API）——Ktor CIO，Android 与 Desktop 共用
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.datasource.okhttp)
            // SimpleCache / StandaloneDatabaseProvider —— 音频流磁盘缓存
            implementation(libs.androidx.media3.database)
        }

        // 注意：这里曾声明 org.openjfx:javafx-graphics / javafx-base，但全仓 Kotlin 源码
        // 对 javafx.* 零引用（音频走 nucleus.rodio 的 Rust JNI，SMTC 走 JMTC 的 JNA），
        // 属死依赖，已移除。JavaFX 的 Prism 自带 DWM/vblank vsync 线程，一旦被谁初始化
        // 就会多出一个帧节奏参与者，在 VRR 显示器上属风险项。
    }
}