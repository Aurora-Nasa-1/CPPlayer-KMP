package cp.player.app.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets

/**
 * Windows 10/11 现代通用文件对话框（Common Item Dialog）。
 *
 * 直接用 FFM 调 Win32 COM `IFileDialog`，不经过 AWT `FileDialog`、Swing `JFileChooser`，
 * 也不依赖 JBR 的 `JBRFileDialog` 扩展。Windows 后端与 NFD
 * （nativefiledialog-extended）是同一个系统 API，但不需要引入任何原生库：
 *
 * - 选文件：`CLSID_FileOpenDialog` + `SetFileTypes` 过滤 zip。
 * - 存文件：`CLSID_FileSaveDialog` + `SetFileName` 给默认名。
 * - 选目录：`CLSID_FileOpenDialog` + `FOS_PICKFOLDERS`。
 *
 * ## 为什么不用 AWT
 *
 * `JFileChooser` 在 GraalVM 镜像里会连崩两次：`ShellFolder.<clinit>` 靠
 * `Class.forName` 反射加载 `Win32ShellFolderManager2`（静态分析看不见），
 * 回落到基类后无参构造又没登记反射；即便修好，`UIManager.getUI` 也拿不到
 * `WindowsFileChooserUI`。`java.awt.FileDialog` 则是 Win95 风格旧对话框。
 * 这条实现彻底不碰 `java.awt` / `javax.swing`。
 *
 * ## vtable 索引（Windows SDK `shobjidl_core.h` 核对）
 *
 * `IUnknown`: 0 QueryInterface / 1 AddRef / 2 Release；`IModalWindow`: 3 Show。
 * `IFileDialog`: 4 SetFileTypes / 9 SetOptions / 15 SetFileName / 17 SetTitle / 20 GetResult。
 * `IShellItem`: 5 GetDisplayName。
 *
 * ⚠️ 顺序有讲究：`SetFileTypes` 必须在设置 `FOS_PICKFOLDERS` **之前**调用，
 * 文件夹模式下 shell 会以 `E_INVALIDARG` 拒绝文件类型过滤器（`app/graalvm/README.md`
 * 里的迷你镜像实测过）。
 *
 * ## 原生镜像
 *
 * 每个 FFM downcall 描述符都要在 `app/graalvm/reachability-metadata.json` 的
 * `foreign.downcalls` 登记，否则 `Linker.downcallHandle` 抛
 * `MissingForeignRegistrationError` 并被这里的 `runCatching` 吞掉，表现为「点了没反应」。
 * 本文件新增 6 条签名；复用的 `jint(jlong)` / `jint(jlong,void*)` 已有登记。
 * 改动前请用 `-Ob` 迷你镜像验一次（做法见 `app/graalvm/README.md`）。
 */
internal object Win32CommonItemDialog {

    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val CLSCTX_ALL = 0x17

    private const val FOS_OVERWRITEPROMPT = 0x2
    private const val FOS_NOCHANGEDIR = 0x8
    private const val FOS_PICKFOLDERS = 0x20
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val FOS_PATHMUSTEXIST = 0x800
    private const val FOS_FILEMUSTEXIST = 0x1000

    /** `SIGDN_FILESYSPATH` = 0x80058000，写成十进制避开 Kotlin 的常量表达式限制。 */
    private const val SIGDN_FILESYSPATH = -2147123200

    /** `COMDLG_FILTERSPEC` = 两个 LPCWSTR 指针。 */
    private const val FILTER_SPEC_BYTES = 16L
    private const val POINTER_BYTES = 8L
    private const val GUID_BYTES = 16L
    private const val MAX_PATH_CHARS = 32767L

    fun isSupported(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows")

    fun openFile(title: String, filterName: String, filterSpec: String): String? =
        show(
            save = false,
            pickFolders = false,
            title = title,
            defaultName = null,
            filterName = filterName,
            filterSpec = filterSpec,
        )

    fun saveFile(title: String, defaultName: String, filterName: String, filterSpec: String): String? =
        show(
            save = true,
            pickFolders = false,
            title = title,
            defaultName = defaultName,
            filterName = filterName,
            filterSpec = filterSpec,
        )

    fun pickFolder(title: String): String? =
        show(
            save = false,
            pickFolders = true,
            title = title,
            defaultName = null,
            filterName = null,
            filterSpec = null,
        )

    private fun show(
        save: Boolean,
        pickFolders: Boolean,
        title: String,
        defaultName: String?,
        filterName: String?,
        filterSpec: String?,
    ): String? {
        if (!isSupported()) return null
        return runCatching {
            Arena.ofConfined().use { arena ->
                val linker = Linker.nativeLinker()
                val ole32 = SymbolLookup.libraryLookup("ole32", arena)

                val coInitializeEx = downcall(
                    linker, ole32, "CoInitializeEx",
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                )
                val coCreateInstance = downcall(
                    linker, ole32, "CoCreateInstance",
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                )
                val coTaskMemFree = downcall(
                    linker, ole32, "CoTaskMemFree", null, ValueLayout.ADDRESS,
                )
                val coUninitialize = downcall(linker, ole32, "CoUninitialize", null)

                // S_OK 与 S_FALSE 都算「本次成功初始化」，都要配对 CoUninitialize。
                // RPC_E_CHANGED_MODE 说明宿主已用别的线程模型初始化过 COM，继续用即可。
                val initHr = coInitializeEx.invokeWithArguments(
                    MemorySegment.NULL, COINIT_APARTMENTTHREADED,
                ) as Int
                val shouldUninitialize = initHr >= 0

                var dialog = 0L
                var shellItem = 0L
                var displayName: MemorySegment? = null
                try {
                    val clsid = if (save) clsidFileSaveDialog(arena) else clsidFileOpenDialog(arena)
                    val iid = iidFileDialog(arena)
                    val dialogOut = arena.allocate(ValueLayout.ADDRESS)
                    val createHr = coCreateInstance.invokeWithArguments(
                        clsid, MemorySegment.NULL, CLSCTX_ALL, iid, dialogOut,
                    ) as Int
                    if (createHr < 0) return@use null
                    dialog = dialogOut.get(ValueLayout.ADDRESS, 0L).address()
                    if (dialog == 0L) return@use null

                    // 文件夹模式不允许文件类型过滤器，因此过滤器必须在 SetOptions 之前装好。
                    if (!pickFolders && filterName != null && filterSpec != null) {
                        val filter = arena.allocate(FILTER_SPEC_BYTES)
                        filter.set(ValueLayout.ADDRESS, 0L, wide(arena, filterName))
                        filter.set(ValueLayout.ADDRESS, POINTER_BYTES, wide(arena, filterSpec))
                        // 过滤器只是体验项，失败不阻断对话框。
                        com(
                            linker, dialog, 4, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ).invokeWithArguments(dialog, 1, filter)
                    }

                    val modeFlag = when {
                        pickFolders -> FOS_PICKFOLDERS
                        save -> FOS_OVERWRITEPROMPT
                        else -> FOS_FILEMUSTEXIST
                    }
                    val options = FOS_NOCHANGEDIR or FOS_FORCEFILESYSTEM or
                        FOS_PATHMUSTEXIST or modeFlag
                    com(
                        linker, dialog, 9, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ).invokeWithArguments(dialog, options)

                    com(
                        linker, dialog, 17, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                    ).invokeWithArguments(dialog, wide(arena, title))

                    if (defaultName != null) {
                        com(
                            linker, dialog, 15, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ).invokeWithArguments(dialog, wide(arena, defaultName))
                    }

                    // NULL owner：Tao 窗口没有 AWT 句柄，Common Item Dialog 自己就是顶层模态窗口。
                    val showHr = com(
                        linker, dialog, 3, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                    ).invokeWithArguments(dialog, MemorySegment.NULL) as Int
                    // 取消是 HRESULT_FROM_WIN32(ERROR_CANCELLED)，与真正失败一样按 null 处理。
                    if (showHr < 0) return@use null

                    val itemOut = arena.allocate(ValueLayout.ADDRESS)
                    val resultHr = com(
                        linker, dialog, 20, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                    ).invokeWithArguments(dialog, itemOut) as Int
                    if (resultHr < 0) return@use null
                    shellItem = itemOut.get(ValueLayout.ADDRESS, 0L).address()
                    if (shellItem == 0L) return@use null

                    val nameOut = arena.allocate(ValueLayout.ADDRESS)
                    val nameHr = com(
                        linker, shellItem, 5, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ).invokeWithArguments(shellItem, SIGDN_FILESYSPATH, nameOut) as Int
                    if (nameHr < 0) return@use null
                    displayName = nameOut.get(ValueLayout.ADDRESS, 0L)
                    readWide(displayName)
                } finally {
                    displayName?.let { ptr -> runCatching { coTaskMemFree.invokeWithArguments(ptr) } }
                    if (shellItem != 0L) release(linker, shellItem)
                    if (dialog != 0L) release(linker, dialog)
                    if (shouldUninitialize) runCatching { coUninitialize.invokeWithArguments() }
                }
            }
        }.onFailure {
            println("[Win32CommonItemDialog] 原生文件对话框不可用：$it")
        }.getOrNull()
    }

    private fun release(linker: Linker, self: Long) {
        runCatching {
            com(linker, self, 2, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
                .invokeWithArguments(self)
        }
    }

    /** 从 COM 对象取 vtable：对象头 8 字节是 vtable 指针，再按索引取函数地址。 */
    private fun com(
        linker: Linker,
        self: Long,
        index: Int,
        returns: MemoryLayout,
        vararg parameters: MemoryLayout,
    ): MethodHandle {
        val vtable = MemorySegment.ofAddress(self).reinterpret(POINTER_BYTES)
            .get(ValueLayout.ADDRESS, 0L)
        val function = vtable.reinterpret(POINTER_BYTES * (index + 1))
            .get(ValueLayout.ADDRESS, POINTER_BYTES * index)
        return linker.downcallHandle(function, FunctionDescriptor.of(returns, *parameters))
    }

    private fun downcall(
        linker: Linker,
        library: SymbolLookup,
        name: String,
        returns: MemoryLayout?,
        vararg parameters: MemoryLayout,
    ): MethodHandle {
        val descriptor = if (returns == null) {
            FunctionDescriptor.ofVoid(*parameters)
        } else {
            FunctionDescriptor.of(returns, *parameters)
        }
        return linker.downcallHandle(library.find(name).orElseThrow(), descriptor)
    }

    /** GUID 在内存里是小端 `{DWORD, WORD, WORD, BYTE[8]}`。 */
    private fun guid(arena: Arena, d1: Int, d2: Int, d3: Int, tail: IntArray): MemorySegment =
        arena.allocate(GUID_BYTES).also { g ->
            g.set(ValueLayout.JAVA_INT, 0L, d1)
            g.set(ValueLayout.JAVA_SHORT, 4L, d2.toShort())
            g.set(ValueLayout.JAVA_SHORT, 6L, d3.toShort())
            tail.forEachIndexed { i, b -> g.set(ValueLayout.JAVA_BYTE, 8L + i, b.toByte()) }
        }

    /** `CLSID_FileOpenDialog` = DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7。 */
    private fun clsidFileOpenDialog(arena: Arena): MemorySegment = guid(
        arena, 0xDC1C5A9C.toInt(), 0xE88A, 0x4DDE,
        intArrayOf(0xA5, 0xA1, 0x60, 0xF8, 0x2A, 0x20, 0xAE, 0xF7),
    )

    /** `CLSID_FileSaveDialog` = C0B4E2F3-BA21-4773-BA91-3C3E4D1F0B6B。 */
    private fun clsidFileSaveDialog(arena: Arena): MemorySegment = guid(
        arena, 0xC0B4E2F3.toInt(), 0xBA21, 0x4773,
        intArrayOf(0xBA, 0x91, 0x3C, 0x3E, 0x4D, 0x1F, 0x0B, 0x6B),
    )

    /** `IID_IFileDialog` = 42F85136-DB7E-439C-85F1-E4075D135FC8（两种 CLSID 都实现）。 */
    private fun iidFileDialog(arena: Arena): MemorySegment = guid(
        arena, 0x42F85136, 0xDB7E, 0x439C,
        intArrayOf(0x85, 0xF1, 0xE4, 0x07, 0x5D, 0x13, 0x5F, 0xC8),
    )

    /** LPCWSTR：UTF-16LE 字节 + 2 字节 NUL 终止符。 */
    private fun wide(arena: Arena, value: String): MemorySegment {
        val bytes = value.toByteArray(StandardCharsets.UTF_16LE)
        val segment = arena.allocate(bytes.size.toLong() + 2L)
        segment.asByteBuffer().put(bytes)
        segment.set(ValueLayout.JAVA_SHORT, bytes.size.toLong(), 0.toShort())
        return segment
    }

    /** 读 `GetDisplayName` 用 CoTaskMemAlloc 分配的 LPWSTR。 */
    private fun readWide(pointer: MemorySegment): String {
        val segment = pointer.reinterpret(MAX_PATH_CHARS * 2L)
        val result = StringBuilder()
        var offset = 0L
        while (offset < MAX_PATH_CHARS * 2L) {
            val ch = segment.get(ValueLayout.JAVA_SHORT, offset).toInt() and 0xFFFF
            if (ch == 0) break
            result.append(ch.toChar())
            offset += 2L
        }
        return result.toString()
    }
}
