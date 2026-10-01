fun getGitSha(): String {
    return try {
        ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) { "unknown" }
}

val appVersionName = providers.gradleProperty("app.versionName").orElse("1.0.0").get()
val appVersionCode = providers.gradleProperty("app.versionCode").orElse("1").get().toInt()
val appReleaseChannel = providers.gradleProperty("app.releaseChannel").orElse("stable").get()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cp.player.app"
    // material-kolor 5.x 的 android 产物要求 compileSdk ≥ 37（AAR metadata 强制），
    // targetSdk 保持 35（运行时行为不变），minSdk 保持 29。
    compileSdk = 37
    defaultConfig {
        applicationId = "cp.player"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_SHA", "\"${getGitSha()}\"")
        buildConfigField("String", "RELEASE_CHANNEL", "\"$appReleaseChannel\"")
    }
    signingConfigs {
        create("release") {
            // 三个条件缺一就保持未签名（assembleRelease 照样产出 *-unsigned.apk，
            // 本地验证过）：KEYSTORE_FILE 指向存在且非空的文件，且密码齐全。
            // 否则一旦把空文件/缺密码配进去，失败的是签名步骤，报错和真正的
            // 编译问题混在一起极难定位（见 release.yml Decode Keystore 的守卫）。
            val keystorePath = System.getenv("KEYSTORE_FILE")
            val storePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")
            if (!keystorePath.isNullOrBlank() && !storePassword.isNullOrBlank()
                && !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()
                && file(keystorePath).let { it.exists() && it.length() > 0 }
            ) {
                storeFile = file(keystorePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // 绑定签名配置
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
        
        create("fastrelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":app"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer)
}
