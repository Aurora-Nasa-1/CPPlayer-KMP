import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `maven-publish`
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

group = "cp.player"
version = "0.1.0"

publishing {
    repositories {
        maven {
            name = "ProviderPreview"
            url = rootProject.layout.projectDirectory.dir("artifacts/provider-sdk/maven").asFile.toURI()
        }
    }
}

kotlin {
    android {
        namespace = "cp.player.sdk"
        compileSdk = 37
        minSdk = 24
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }
    sourceSets {
        val jvmMain by creating
        jvmMain.dependsOn(commonMain.get())
        androidMain.get().dependsOn(jvmMain)
        getByName("desktopMain").dependsOn(jvmMain)
        commonMain.dependencies { api(libs.kotlinx.coroutines.core) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
