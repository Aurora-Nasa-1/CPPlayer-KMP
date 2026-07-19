package cp.player.kmp.util

import java.io.File

actual object PlatformInfo {
    // Desktop JVM 仅运行 x86_64；声明顺序供模块入口解析参考
    actual val supportedAbis: List<String> = listOf("x86_64", "amd64", "x86")

    actual fun modulesDirectory(context: PlatformContext): String {
        return File(System.getProperty("user.home"), ".kmp-pro/modules")
            .apply { if (!exists()) mkdirs() }.absolutePath
    }
}