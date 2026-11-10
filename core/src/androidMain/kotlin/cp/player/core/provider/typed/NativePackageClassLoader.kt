package cp.player.core.provider.typed

import cp.player.sdk.jni.NativeProviderFactory
import cp.player.sdk.jni.NativeSession
import cp.player.core.util.PlatformContext
import dalvik.system.DexClassLoader
import java.io.File

internal actual fun nativePackageClassLoader(entry: File, libraryDirectory: File): ClassLoader {
    // Android 14+ requires dynamically loaded code to be read-only.
    check(entry.setReadOnly()) { "Cannot mark native SDK adapter read-only" }
    val optimized = File(entry.parentFile, ".dex").apply { mkdirs() }
    return DexClassLoader(
        entry.absolutePath, optimized.absolutePath, libraryDirectory.absolutePath,
        NativeProviderFactory::class.java.classLoader,
    )
}

internal actual fun nativeLibraryClassLoader(context: PlatformContext?): ClassLoader {
    val app = checkNotNull(context?.raw as? android.content.Context) { "Native SDK loading requires Android context" }
    val code = (listOf(app.applicationInfo.sourceDir) + app.applicationInfo.splitSourceDirs.orEmpty())
        .joinToString(File.pathSeparator)
    return object : DexClassLoader(code, app.codeCacheDir.absolutePath, null, NativeSession::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (name != NativeSession::class.java.name) return super.loadClass(name, resolve)
            val loaded = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
}
