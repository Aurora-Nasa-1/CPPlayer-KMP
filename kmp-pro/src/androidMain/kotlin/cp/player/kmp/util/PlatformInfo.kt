package cp.player.kmp.util

import android.os.Build
import java.io.File

actual object PlatformInfo {
    actual val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()

    actual fun modulesDirectory(context: PlatformContext): String {
        val ctx = context.androidContext() ?: error("PlatformContext needs Android Context")
        return File(ctx.filesDir, "modules").apply { if (!exists()) mkdirs() }.absolutePath
    }
}