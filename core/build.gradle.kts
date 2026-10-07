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
        // 与 app-android 对齐：material-kolor 5.x 要求 compileSdk ≥ 37。
        compileSdk = 37
        // 24 = Android 7.0。降到 24 之后 `java.time` 不再是系统 API，必须靠
        // app 模块的核心库脱糖补上（见 libs.versions.toml 里 desugar-jdk-libs 的注释）。
        minSdk = 24
        // AGP 9 的 KMP library 插件**没有** consumerProguardFiles —— consumer keep 规则
        // 改由 optimization.consumerKeepRules 提供（已 javap 核实 KmpOptimization 的 DSL）。
        // 此前 core/consumer-rules.pro 从未被任何构建引用（是个死文件），这里接上。
        // 注意：app-android 的 release 目前 isMinifyEnabled = false，所以这些规则暂时
        // 不会被读取；一旦开启 R8，这里就是防止 Provider/API 层被剥掉的那道保险。
        optimization {
            consumerKeepRules.apply {
                file(layout.projectDirectory.file("consumer-rules.pro"))
                publish = true
            }
        }
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
            // 歌词源插件的 JS 运行时（Lyrico Plugin API 宿主）。Android 与桌面共用。
            implementation(libs.rhino)
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