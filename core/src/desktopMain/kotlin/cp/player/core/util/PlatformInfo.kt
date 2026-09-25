package cp.player.core.util

actual object PlatformInfo {
    // Desktop JVM 仅运行 x86_64；声明顺序供模块入口解析参考
    actual val supportedAbis: List<String> = listOf("x86_64", "amd64", "x86")

    actual fun modulesDirectory(context: PlatformContext): String =
        DesktopDataDir.directory("modules").absolutePath

    actual fun downloadsDirectory(context: PlatformContext): String =
        DesktopDataDir.directory("downloads").absolutePath

    actual fun dataDirectory(context: PlatformContext): String =
        DesktopDataDir.root().absolutePath
}