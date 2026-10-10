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
            // ⚠️ 版本由 1.15.0 提到 1.17.0（gradle/libs.versions.toml）。
            // 原因：歌词投放要用的 `NotificationCompat.setShortCriticalText` /
            // `setRequestPromotedOngoing`（Android 16 实时活动）是 core 1.16+ 才有的 API。
            // 词幕的 AAR 本来就会把 1.17.0 顶上来，所以**实际解析结果没变** ——
            // 显式声明只是让「这行依赖是刻意的」这件事不依赖传递依赖的偶然。
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.datasource.okhttp)
            // SimpleCache / StandaloneDatabaseProvider —— 音频流磁盘缓存
            implementation(libs.androidx.media3.database)
            // —— 歌词对外投放（系统级歌词渠道）——
            // 只放 androidMain：桌面端无对应生态，commonMain 只保留可测的纯协议层。
            // 用 implementation 而非 api：消费方（app / app-android）只通过
            // cp.player.core.lyricpush.LyricPusher 这个自家接口打交道，不直接碰 SDK 类型。
            implementation(libs.lyricon.provider)      // 词幕（Lyricon）
            implementation(libs.superlyric.api)        // SuperLyric
            implementation(libs.lyric.getter.api)      // Lyric Getter
            implementation(libs.hyper.focus.api)       // HyperOS 超级小岛
            // —— 超级岛歌词的 XMSF 临时断网隔离（联网显示的关键）——
            // shizuku-api 传递引入 aidl + shared（`moe.shizuku.server.IShizukuService` 在 aidl 里）；
            // shizuku-provider 只为 `rikka.shizuku.ShizukuProvider` 这个类能被宿主清单按名字找到，
            // core 的代码不直接引用它。
            implementation(libs.shizuku.api)
            implementation(libs.shizuku.provider)
            implementation(libs.hidden.api.bypass)
        }

        // 注意：这里曾声明 org.openjfx:javafx-graphics / javafx-base，但全仓 Kotlin 源码
        // 对 javafx.* 零引用（音频走 nucleus.rodio 的 Rust JNI，SMTC 走 JMTC 的 JNA），
        // 属死依赖，已移除。JavaFX 的 Prism 自带 DWM/vblank vsync 线程，一旦被谁初始化
        // 就会多出一个帧节奏参与者，在 VRR 显示器上属风险项。
    }
}