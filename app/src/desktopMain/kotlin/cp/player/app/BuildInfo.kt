package cp.player.app

/** Desktop metadata; launchers may override values with JVM system properties. */
object BuildInfo {
    val VERSION_NAME: String = System.getProperty("cp.player.versionName", "1.0.0")
    val VERSION_CODE: Int = System.getProperty("cp.player.versionCode", "1").toIntOrNull() ?: 1
    val GIT_SHA: String = System.getProperty("cp.player.gitSha", "unknown")
}
