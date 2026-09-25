package cp.player.kmp.util

import android.os.Build
import android.os.Environment
import java.io.File

actual object PlatformInfo {
    actual val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()

    actual fun modulesDirectory(context: PlatformContext): String {
        val ctx = context.androidContext() ?: error("PlatformContext needs Android Context")
        return File(ctx.filesDir, "modules").apply { if (!exists()) mkdirs() }.absolutePath
    }

    actual fun downloadsDirectory(context: PlatformContext): String {
        val ctx = context.androidContext() ?: error("PlatformContext needs Android Context")
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(ctx.filesDir, "downloads")
        return File(base, "CPPlayer").apply { if (!exists()) mkdirs() }.absolutePath
    }

    actual fun dataDirectory(context: PlatformContext): String {
        val ctx = context.androidContext() ?: error("PlatformContext needs Android Context")
        return ctx.filesDir.absolutePath
    }
}