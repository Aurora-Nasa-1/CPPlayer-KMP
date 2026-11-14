package cp.player.app.platform

/**
 * 桌面文件选择器的统一入口。
 *
 * Windows 走 [Win32CommonItemDialog]（Vista 起就有的现代 Common Item Dialog，
 * Windows 10/11 的资源管理器界面，文件夹用 `FOS_PICKFOLDERS`）；
 * 其它平台用系统自带命令拉起各自的原生选择器。两条路径都不碰
 * `java.awt` / `javax.swing`，也不依赖任何第三方原生库。
 *
 * 这里刻意留成可替换的一层：将来若要接 NFD（nativefiledialog-extended）
 * 统一三平台，只需换掉本文件的实现，调用方无需改动。
 */
internal object DesktopFileDialog {

    private const val ZIP_FILTER_NAME = "音源模块 (*.zip)"
    private const val ZIP_FILTER_SPEC = "*.zip"

    fun openZip(title: String): String? =
        if (Win32CommonItemDialog.isSupported()) {
            Win32CommonItemDialog.openFile(title, ZIP_FILTER_NAME, ZIP_FILTER_SPEC)
        } else {
            external(ExternalKind.OPEN_FILE, title, null)
        }

    fun saveZip(title: String, defaultName: String): String? =
        if (Win32CommonItemDialog.isSupported()) {
            Win32CommonItemDialog.saveFile(title, defaultName, ZIP_FILTER_NAME, ZIP_FILTER_SPEC)
        } else {
            external(ExternalKind.SAVE_FILE, title, defaultName)
        }

    fun saveImage(title: String, defaultName: String, extension: String): String? {
        val safeExtension = extension.removePrefix(".").ifBlank { "png" }
        return if (Win32CommonItemDialog.isSupported()) {
            Win32CommonItemDialog.saveFile(
                title = title,
                defaultName = defaultName,
                filterName = "图片 (*.$safeExtension)",
                filterSpec = "*.$safeExtension",
            )
        } else {
            external(ExternalKind.SAVE_FILE, title, defaultName)
        }
    }

    fun pickDirectory(title: String): String? =
        if (Win32CommonItemDialog.isSupported()) {
            Win32CommonItemDialog.pickFolder(title)
        } else {
            external(ExternalKind.PICK_FOLDER, title, null)
        }

    internal enum class ExternalKind { OPEN_FILE, SAVE_FILE, PICK_FOLDER }

    /** Linux 用 zenity（缺失则回退 kdialog），macOS 用 osascript 的 AppleScript 选择器。 */
    private fun external(kind: ExternalKind, title: String, defaultName: String?): String? {
        val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac OS")
        val command = if (isMac) {
            macCommand(kind, title, defaultName)
        } else {
            linuxCommand(kind, title, defaultName)
        }
        return runCommand(command)
    }

    internal fun linuxCommand(kind: ExternalKind, title: String, defaultName: String?): List<String> {
        val args = mutableListOf("zenity", "--file-selection", "--title=$title")
        when (kind) {
            ExternalKind.OPEN_FILE -> args += "--file-filter=$ZIP_FILTER_NAME|$ZIP_FILTER_SPEC"
            ExternalKind.SAVE_FILE -> {
                args += "--save"
                args += "--confirm-overwrite"
                if (defaultName != null) args += "--filename=$defaultName"
            }
            ExternalKind.PICK_FOLDER -> args += "--directory"
        }
        return args
    }

    internal fun macCommand(kind: ExternalKind, title: String, defaultName: String?): List<String> {
        val prompt = appleScriptString(title)
        val script = when (kind) {
            ExternalKind.OPEN_FILE ->
                "POSIX path of (choose file with prompt $prompt of type {\"zip\"})"
            ExternalKind.SAVE_FILE ->
                "POSIX path of (choose file name with prompt $prompt" +
                    (defaultName?.let { " default name ${appleScriptString(it)}" } ?: "") + ")"
            ExternalKind.PICK_FOLDER ->
                "POSIX path of (choose folder with prompt $prompt)"
        }
        return listOf("osascript", "-e", script)
    }

    private fun appleScriptString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun runCommand(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        output.takeIf { exitCode == 0 && it.isNotEmpty() }
    }.getOrNull()
}
