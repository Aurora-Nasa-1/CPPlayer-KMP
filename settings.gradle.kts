pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // SuperLyricApi 与 Lyric-Getter-Api 只在 JitPack 发布；词幕 provider 与
        // HyperOS focus-api 在 Maven Central，不需要它。加仓库只为这两个。
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "CPPlayer"

include(":core")
include(":app")
include(":app-android")