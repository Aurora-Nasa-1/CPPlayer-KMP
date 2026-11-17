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
                // GraalVM Native Image 里类文件不是可读资源（getResourceAsStream 恒为 null），
                // 所以这里绝不能只写 checkNotNull(x)：无 message 时只会显示
                // 「Required value was null.」，看不出是什么坏了。JNI 音源在原生构建里
                // 由 installedNativeSdkProvider 提前拦掉，这里只是兜底说明。
                val stream = checkNotNull(parent.getResourceAsStream(path)) {
                    "Cannot define $path per package: class files are not readable resources in " +
                        "this runtime (JNI providers require a JVM; GraalVM Native Image is not supported)"
                }
                val bytes = stream.use { it.readBytes() }
                defineClass(name, bytes, 0, bytes.size)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
