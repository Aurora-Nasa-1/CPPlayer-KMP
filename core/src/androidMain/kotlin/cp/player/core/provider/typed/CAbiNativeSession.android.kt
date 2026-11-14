package cp.player.core.provider.typed

import java.io.File

/**
 * Android 没有 `java.lang.foreign`（FFM 是 JDK 的 API，ART 不提供），所以 C ABI 只在桌面端可用。
 * Android 继续走 JNI 路径：`nativeLibraryClassLoader` / `nativePackageClassLoader` 的 androidMain 实现。
 */
internal actual fun cAbiSessionFactory(library: File): CAbiSessionFactory? = null
