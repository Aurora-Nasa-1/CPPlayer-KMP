package cp.player.core.provider.typed

import cp.player.sdk.jni.NativeProviderFactory
import cp.player.sdk.jni.NativeSession
import cp.player.core.util.PlatformContext
import java.io.File
import java.net.URLClassLoader

internal actual fun nativePackageClassLoader(entry: File, libraryDirectory: File): ClassLoader =
    object : URLClassLoader(arrayOf(entry.toURI().toURL()), NativeProviderFactory::class.java.classLoader) {
        override fun findLibrary(libname: String): String? {
            val library = File(libraryDirectory, System.mapLibraryName(libname)).canonicalFile
            require(library.parentFile == libraryDirectory.canonicalFile) { "Invalid native library name" }
            return library.takeIf { it.isFile }?.absolutePath
        }
    }

internal actual fun nativeLibraryClassLoader(context: PlatformContext?): ClassLoader =
    object : ClassLoader(NativeSession::class.java.classLoader) {
        // Each package needs its own native-method bindings, even with identical JNI symbol names.
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            if (name != NativeSession::class.java.name) return super.loadClass(name, resolve)
            val loaded = findLoadedClass(name) ?: run {
                val path = name.replace('.', '/') + ".class"
                val bytes = checkNotNull(parent.getResourceAsStream(path)).use { it.readBytes() }
                defineClass(name, bytes, 0, bytes.size)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
