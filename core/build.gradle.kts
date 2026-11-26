import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

group = "cp.player"
version = "1.0.0"

val flickVipPublicKey = providers.gradleProperty("flick.vipPublicKey")
    .orElse(providers.environmentVariable("FLICK_VIP_PUBLIC_KEY"))
    .orElse("")
val flickVipLeaseUrl = providers.gradleProperty("flick.vipLeaseUrl")
    .orElse(providers.environmentVariable("FLICK_VIP_LEASE_URL"))
    .orElse("")
val flickLicenseSources = layout.buildDirectory.dir("generated/flickLicense")
val generateFlickLicenseKey = tasks.register("generateFlickLicenseKey") {
    inputs.property("publicKey", flickVipPublicKey)
    inputs.property("leaseUrl", flickVipLeaseUrl)
    outputs.dir(flickLicenseSources)
    doLast {
        val encoded = flickVipPublicKey.get().trim()
        val leaseUrl = flickVipLeaseUrl.get().trim()
        if (leaseUrl.isNotEmpty()) {
            val uri = URI(leaseUrl)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null) {
                "FLICK_VIP_LEASE_URL must be an HTTPS endpoint without credentials, query or fragment"
            }
        }
        if (encoded.isNotEmpty()) {
            val bytes = Base64.getDecoder().decode(encoded)
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
            require(key is RSAPublicKey && key.modulus.bitLength() in 2048..8192) {
                "FLICK_VIP_PUBLIC_KEY must be a 2048–8192 bit RSA public key"
            }
        }
        val output = flickLicenseSources.get().file("FlickLicenseKey.kt").asFile
        output.parentFile.mkdirs()
        val url = Base64.getEncoder().encodeToString(leaseUrl.toByteArray(Charsets.UTF_8))
        output.writeText(
            "package cp.player.core.license\ninternal const val FLICK_VIP_PUBLIC_KEY = \"$encoded\"\n" +
                "internal const val FLICK_VIP_LEASE_URL_B64 = \"$url\"\n",
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
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
        // Retired WIP sources may remain on disk in shared workspaces; never ship them.
        commonMain.get().kotlin.exclude("cp/player/core/provider/compat/**")
        val jvmMain by creating
        jvmMain.dependsOn(commonMain.get())
        jvmMain.kotlin.srcDir(generateFlickLicenseKey)
        androidMain.get().dependsOn(jvmMain)
        val desktopMain by getting
        desktopMain.dependsOn(jvmMain)
        desktopMain.kotlin.exclude("cp/player/core/provider/FfmProvider.kt")

        val desktopTest by getting {
            kotlin.srcDir(rootProject.file("provider-sdk-kmp/examples/kotlin"))
            kotlin.srcDir(rootProject.file("provider-sdk-kmp/examples/jni/kotlin"))
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // WebView DRM 协议测试：充当「播放页」角色连接本地桥接服务
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.websockets)
            }
        }

        commonMain.dependencies {
            api(project(":provider-sdk-kmp"))
            implementation(libs.accompanist.lyrics.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.quickjs)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // WebView EME DRM 桥：本地播放页经 WebSocket 与宿主通信（license 由宿主转发）
            implementation(libs.ktor.server.websockets)
            // 本地服务器（REST 控制 API）——Ktor CIO，Android 与 Desktop 共用
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
        desktopMain.dependencies {
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
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.datasource.okhttp)
            // SimpleCache / StandaloneDatabaseProvider —— 音频流磁盘缓存
            implementation(libs.androidx.media3.database)
        }

        // 注意：这里曾声明 org.openjfx:javafx-graphics / javafx-base，但全仓 Kotlin 源码
        // 对 javafx.* 零引用（音频走 FlickEngine Rust JNI，播控走 FFM），
        // 属死依赖，已移除。JavaFX 的 Prism 自带 DWM/vblank vsync 线程，一旦被谁初始化
        // 就会多出一个帧节奏参与者，在 VRR 显示器上属风险项。
    }
}

tasks.named<Test>("desktopTest") {
    // `--enable-native-access`：C ABI 原生音源走 FFM（SymbolLookup.libraryLookup），
    // 不显式开启会在每个用例里打 restricted-method 警告，将来版本会直接拒绝。
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
}

// Developer smoke test for an already-built provider ZIP. This runs the real
// ModuleManager/ProviderManager/ProviderHost chain, not a provider factory in
// isolation, so manifest and session behavior are covered too.
tasks.register<JavaExec>("providerConformance") {
    group = "verification"
    description = "Run kmp-sdk-v1 conformance checks against a provider ZIP"
    dependsOn("desktopMainClasses")
    // Relative -PproviderPackage paths are written from the repository root.
    workingDir = rootProject.projectDir
    val desktopMain = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = files(desktopMain.output.allOutputs, desktopMain.runtimeDependencyFiles)
    mainClass.set("cp.player.core.provider.conformance.ProviderConformanceMainKt")
    val providerPackage = providers.gradleProperty("providerPackage")
    providerPackage.orNull?.let { args(it) }
    providers.gradleProperty("providerArgs").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { args(it.split(Regex("\\s+"))) }
    doFirst {
        if (!providerPackage.isPresent) {
            throw GradleException(
                "Use -PproviderPackage=<provider.zip> [-PproviderArgs=\"--json --xml report.xml\"]",
            )
        }
    }
}
