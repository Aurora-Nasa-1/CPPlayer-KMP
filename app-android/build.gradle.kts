// 版本元数据只有一处实现：根 build.gradle.kts。这里只做取值，**不要再自己算**。
// 曾经本文件自带一份 `getGitSha()` + `appVersionCode.orElse("1")`，
// 与 CI 的 `-Papp.versionCode=${GITHUB_RUN_NUMBER}`、`scripts/release.ps1` 的
// `major*10000+minor*100+patch` 三份并存 —— 同一个 tag 会得到不同的 versionCode。
val appVersionName: String = rootProject.extra["cpAppVersionName"] as String
val appVersionCode: Int = rootProject.extra["cpAppVersionCode"] as Int
val appReleaseChannel: String = rootProject.extra["cpAppReleaseChannel"] as String
val gitSha: String = rootProject.extra["cpGitSha"] as String

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
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
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
            // R8（2026-10-04 开启，此前 isMinifyEnabled = false）：
            // 收益 = DEX 里删掉未用类/方法 + 资源收缩，包体与运行时类元数据
            // （Metaspace 的安卓对应物）都显著变小。规则见 proguard-rules.pro，
            // 那里逐条注释了为什么 keep（JNI 符号绑定 / getMethod 反射 /
            // kotlinx.serialization / Voyager 的 Java 序列化返回栈）。
            //
            // ⚠️ Compose 资源（字体）打进 **assets**，resource shrinker 不碰 assets，
            // shrinkResources 对它们是安全的。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
