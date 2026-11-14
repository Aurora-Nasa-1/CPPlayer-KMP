package cp.player.app.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets

/**
 * 用 Win32 剪贴板 API 写 Unicode 文本，替代 `java.awt.Toolkit` + `StringSelection`。
 *
 * AWT 剪贴板必须在 EDT 上调用（原实现为此挂了 `SwingUtilities.invokeLater`），
 * 而且会把整个 `java.desktop` / AWT 工具包拉进原生镜像。这里只调
 * `user32` / `kernel32` 的 8 个 C 函数，Win32 剪贴板本身与线程无关。
 *
 * ⚠️ 新增的 `jlong(jint, jlong)`（`GlobalAlloc` / `SetClipboardData`）签名登记在
 * `app/graalvm/reachability-metadata.json`；其余复用已有的
 * `jint()` / `jint(jlong)` / `jlong(jlong)`。
 */
internal object Win32Clipboard {

    private const val GMEM_MOVEABLE = 0x2
    private const val CF_UNICODETEXT = 13

    fun isSupported(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    fun copy(text: String): Boolean {
        if (!isSupported()) return false
        return runCatching {
            Arena.ofConfined().use { arena ->
                val linker = Linker.nativeLinker()
                val user32 = SymbolLookup.libraryLookup("user32", arena)
                val kernel32 = SymbolLookup.libraryLookup("kernel32", arena)

                val openClipboard = down(user32, "OpenClipboard", ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
                val emptyClipboard = down(user32, "EmptyClipboard", ValueLayout.JAVA_INT)
                val setClipboardData = down(
                    user32, "SetClipboardData", ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                )
                val closeClipboard = down(user32, "CloseClipboard", ValueLayout.JAVA_INT)
                val globalAlloc = down(
                    kernel32, "GlobalAlloc", ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                )
                val globalLock = down(kernel32, "GlobalLock", ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
                val globalUnlock = down(kernel32, "GlobalUnlock", ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
                val globalFree = down(kernel32, "GlobalFree", ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)

                val bytes = text.toByteArray(StandardCharsets.UTF_16LE)
                val size = bytes.size.toLong() + 2L
                val handle = globalAlloc.invokeWithArguments(GMEM_MOVEABLE, size) as Long
                if (handle == 0L) return@use false
                var transferred = false
                try {
                    val locked = globalLock.invokeWithArguments(handle) as Long
                    if (locked == 0L) return@use false
                    val segment = MemorySegment.ofAddress(locked).reinterpret(size)
                    segment.asByteBuffer().put(bytes)
                    segment.set(ValueLayout.JAVA_SHORT, bytes.size.toLong(), 0.toShort())
                    globalUnlock.invokeWithArguments(handle)

                    if (openClipboard.invokeWithArguments(0L) as Int == 0) return@use false
                    try {
                        emptyClipboard.invokeWithArguments()
                        val placed = setClipboardData.invokeWithArguments(CF_UNICODETEXT, handle) as Long
                        // 成功后内存所有权归系统，不能再 GlobalFree。
                        transferred = placed != 0L
                    } finally {
                        closeClipboard.invokeWithArguments()
                    }
                    transferred
                } finally {
                    if (!transferred) globalFree.invokeWithArguments(handle)
                }
            }
        }.getOrDefault(false)
    }

    private fun down(
        library: SymbolLookup,
        name: String,
        returns: java.lang.foreign.MemoryLayout,
        vararg parameters: java.lang.foreign.MemoryLayout,
    ): MethodHandle = Linker.nativeLinker().downcallHandle(
        library.find(name).orElseThrow(),
        FunctionDescriptor.of(returns, *parameters),
    )
}
