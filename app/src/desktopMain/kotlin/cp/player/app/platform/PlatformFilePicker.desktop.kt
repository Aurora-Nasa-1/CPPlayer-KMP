package cp.player.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面端文件与目录选择器实现。
 *
 * ## JBR Windows 10/11 现代原生文件对话框
 *
 * 在 Windows 平台上，JDK 原生 `FileDialog`（基于 Win32 `GetOpenFileNameW`）默认会弹出
 * 古老的 Windows 95/XP 风格旧对话框。JetBrains Runtime (JBR) 提供了基于 COM `IFileDialog`
 * (`CLSID_FileOpenDialog` / `CLSID_FileSaveDialog`) 的 Windows 10/11 现代通用文件选择对话框
 * （Common Item Dialog），支持高 DPI 自适应、系统侧边栏、搜索与快速访问。
 *
 * 该特性受 JVM 属性 `sun.awt.windows.useCommonItemDialog=true` 控制。
 * 此外，JBR 的 `com.jetbrains.desktop.JBRFileDialog` 扩展允许配置专属的文件类型过滤说明以及
 * `SELECT_DIRECTORIES_HINT`（文件夹选择模式，底层开启 `FOS_PICKFOLDERS`），
 * 使得文件夹选择无需回退至过时的 Swing `JFileChooser`。
 *
 * 本实现通过纯反射与 Unsafe 桥接 JBR API，在未集成 JBR 时平滑回退，保障跨平台与跨 JDK 兼容性。
 */
internal object JbrFileDialogBridge {
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    init {
        ensureModernDialogProperty()
    }

    fun ensureModernDialogProperty() {
        if (isWindows && System.getProperty("sun.awt.windows.useCommonItemDialog") != "true") {
            System.setProperty("sun.awt.windows.useCommonItemDialog", "true")
        }
    }

    private class AccessibleOverrideLayout {
        var first = false

        @Volatile
        var second: Any? = null
    }

    private val unsafeAccess by lazy {
        runCatching {
            val cls = Class.forName("sun.misc.Unsafe")
            val theUnsafe = cls.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)!!
            val offsetMethod = cls.getMethod("objectFieldOffset", Field::class.java)
            val putBoolMethod = cls.getMethod(
                "putBooleanVolatile",
                Any::class.java,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            Triple(theUnsafe, offsetMethod, putBoolMethod)
        }.getOrNull()
    }

    private fun forceAccessible(obj: AccessibleObject) {
        if (obj.trySetAccessible()) return
        val (unsafe, offsetMethod, putBoolMethod) = unsafeAccess ?: return
        val offset = offsetMethod.invoke(unsafe, AccessibleOverrideLayout::class.java.getDeclaredField("first")) as Long
        putBoolMethod.invoke(unsafe, obj, offset, true)
    }

    private val jbrClass: Class<*>? by lazy {
        runCatching { Class.forName("com.jetbrains.desktop.JBRFileDialog") }.getOrNull()
    }

    private val getJbrDialogMethod: Method? by lazy {
        jbrClass?.let { cls ->
            runCatching {
                cls.getMethod("get", FileDialog::class.java).also { forceAccessible(it) }
            }.getOrNull()
        }
    }

    private val setHintsMethod: Method? by lazy {
        jbrClass?.let { cls ->
            runCatching {
                cls.getMethod("setHints", Int::class.javaPrimitiveType).also { forceAccessible(it) }
            }.getOrNull()
        }
    }

    private val getHintsMethod: Method? by lazy {
        jbrClass?.let { cls ->
            runCatching {
                cls.getMethod("getHints").also { forceAccessible(it) }
            }.getOrNull()
        }
    }

    private val selectDirectoriesHint: Int by lazy {
        jbrClass?.let { cls ->
            runCatching {
                cls.getField("SELECT_DIRECTORIES_HINT").also { forceAccessible(it) }.getInt(null)
            }.getOrDefault(2)
        } ?: 2
    }

    private val setFileFilterExtMethod: Method? by lazy {
        jbrClass?.let { cls ->
            runCatching {
                cls.getMethod("setFileFilterExtensions", String::class.java, Array<String>::class.java)
                    .also { forceAccessible(it) }
            }.getOrNull()
        }
    }

    val isFolderPickerSupported: Boolean
        get() = isWindows && jbrClass != null && getJbrDialogMethod != null && setHintsMethod != null

    fun configureZipFilter(
        dialog: FileDialog,
        description: String = "音源模块 (*.zip)",
        extensions: Array<String> = arrayOf("zip"),
    ) {
        ensureModernDialogProperty()
        val jbr = getJbrDialog(dialog) ?: return
        runCatching {
            setFileFilterExtMethod?.invoke(jbr, description, extensions)
        }
    }

    fun configureDirectoryPicker(dialog: FileDialog) {
        ensureModernDialogProperty()
        val jbr = getJbrDialog(dialog) ?: return
        runCatching {
            val current = getHintsMethod?.invoke(jbr) as? Int ?: 0
            setHintsMethod?.invoke(jbr, current or selectDirectoriesHint)
        }
    }

    private fun getJbrDialog(dialog: FileDialog): Any? {
        val getter = getJbrDialogMethod ?: return null
        return runCatching { getter.invoke(null, dialog) }.getOrNull()
    }
}

private fun findActiveFrame(): Frame? {
    return runCatching {
        val windows = Window.getWindows()
        windows.firstOrNull { it.isVisible && it.isFocused } as? Frame
            ?: windows.firstOrNull { it.isVisible } as? Frame
    }.getOrNull()
}

@Composable
actual fun rememberZipPicker(onPicked: (zipPath: String?) -> Unit): () -> Unit {
    return remember(onPicked) {
        {
            val parentFrame = findActiveFrame()
            val dialog = FileDialog(parentFrame, "选择音源模块 (.zip)", FileDialog.LOAD).apply {
                JbrFileDialogBridge.configureZipFilter(this, "音源模块 (*.zip)", arrayOf("zip"))
                file = "*.zip"
                setFilenameFilter { _, name -> name.lowercase().endsWith(".zip") }
                isVisible = true
            }
            val picked = dialog.files?.firstOrNull()?.absolutePath
                ?: if (dialog.directory != null && dialog.file != null) {
                    java.io.File(dialog.directory, dialog.file).absolutePath
                } else {
                    dialog.file
                }
            onPicked(picked)
        }
    }
}

@Composable
actual fun rememberZipSaver(fileName: String, onWriteTo: (destPath: String?) -> Boolean): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember(fileName, onWriteTo) {
        {
            val parentFrame = findActiveFrame()
            val dialog = FileDialog(parentFrame, "导出音源模块 (.zip)", FileDialog.SAVE).apply {
                JbrFileDialogBridge.configureZipFilter(this, "音源模块 (*.zip)", arrayOf("zip"))
                file = fileName
                isVisible = true
            }
            val picked = dialog.files?.firstOrNull()?.absolutePath
                ?: if (dialog.directory != null && dialog.file != null) {
                    java.io.File(dialog.directory, dialog.file).absolutePath
                } else {
                    dialog.file
                }
            if (picked != null) {
                // FileDialog.SAVE 在部分平台（Windows）不强制扩展名，缺 .zip 时补上。
                val chosen = if (picked.lowercase().endsWith(".zip")) picked else "$picked.zip"
                // 写 zip 不阻塞 UI 线程（EDT）；结果经 model 的 message StateFlow 反馈。
                scope.launch(Dispatchers.IO) { onWriteTo(chosen) }
            } else {
                onWriteTo(null)
            }
        }
    }
}

actual fun sendPlatformToast(message: String): Unit = Unit

@Composable
actual fun rememberDirectoryPicker(onPicked: (dirPath: String?) -> Unit): () -> Unit {
    return remember(onPicked) {
        {
            val parentFrame = findActiveFrame()
            val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac OS")
            val picked = if (JbrFileDialogBridge.isFolderPickerSupported || isMac) {
                val prevMacProp = if (isMac) System.getProperty("apple.awt.fileDialogForDirectories") else null
                try {
                    if (isMac) System.setProperty("apple.awt.fileDialogForDirectories", "true")
                    val dialog = FileDialog(parentFrame, "选择目录", FileDialog.LOAD).apply {
                        if (JbrFileDialogBridge.isFolderPickerSupported) {
                            JbrFileDialogBridge.configureDirectoryPicker(this)
                        }
                        isVisible = true
                    }
                    dialog.files?.firstOrNull()?.absolutePath
                        ?: if (dialog.directory != null && dialog.file != null) {
                            java.io.File(dialog.directory, dialog.file).absolutePath
                        } else {
                            dialog.file ?: dialog.directory
                        }
                } finally {
                    if (isMac) {
                        if (prevMacProp != null) {
                            System.setProperty("apple.awt.fileDialogForDirectories", prevMacProp)
                        } else {
                            System.clearProperty("apple.awt.fileDialogForDirectories")
                        }
                    }
                }
            } else {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "选择目录"
                }
                if (chooser.showOpenDialog(parentFrame) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile?.absolutePath
                } else {
                    null
                }
            }
            onPicked(picked)
        }
    }
}
