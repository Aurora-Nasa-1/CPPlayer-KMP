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
        namespace = "cp.player.kmp"
        compileSdk = 35
        minSdk = 24
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

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.datasource.okhttp)
        }

        val fxOsClassifier = when {
            System.getProperty("os.name").startsWith("Mac") ->
                if (System.getProperty("os.arch").contains("aarch64")) "mac-aarch64" else "mac"
            System.getProperty("os.name").startsWith("Windows") -> "win"
            else -> "linux"
        }
        desktopMain.dependencies {
            implementation("org.openjfx:javafx-media:${libs.versions.javafx.get()}:$fxOsClassifier")
            implementation("org.openjfx:javafx-graphics:${libs.versions.javafx.get()}:$fxOsClassifier")
            implementation("org.openjfx:javafx-base:${libs.versions.javafx.get()}:$fxOsClassifier")
        }
    }
}