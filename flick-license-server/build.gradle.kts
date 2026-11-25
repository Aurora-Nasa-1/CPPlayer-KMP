plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(libs.ktor.server.cio)
}

application {
    mainClass.set("cp.player.flick.server.MainKt")
}
