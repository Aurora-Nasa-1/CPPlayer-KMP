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
    compileSdk = 36
    defaultConfig {
        applicationId = "cp.player.app"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_SHA", "\"${getGitSha()}\"")
        buildConfigField("String", "RELEASE_CHANNEL", "\"$appReleaseChannel\"")
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
}
