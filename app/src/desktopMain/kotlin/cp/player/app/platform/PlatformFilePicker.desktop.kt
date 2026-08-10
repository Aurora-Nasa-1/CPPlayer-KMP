package cp.player.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import javax.swing.JFileChooser
import javax.swing.JFrame

@Composable
actual fun rememberZipPicker(onPicked: (zipPath: String?) -> Unit): () -> Unit {
    return remember(onPicked) {
        {
            val frame = JFrame().apply { isVisible = false }
            try {
                val dialog = FileDialog(frame, "选择音源模块 (.zip)", FileDialog.LOAD).apply {
                    setFilenameFilter { _, name -> name.lowercase().endsWith(".zip") }
                    isVisible = true
                }
                val file = dialog.file
                val dir = dialog.directory
                if (file != null && dir != null) {
                    onPicked(java.io.File(dir, file).absolutePath)
                } else {
                    onPicked(null)
                }
            } finally {
                frame.dispose()
            }
        }
    }
}

actual fun sendPlatformToast(message: String): Unit = Unit

@Composable
actual fun rememberDirectoryPicker(onPicked: (dirPath: String?) -> Unit): () -> Unit {
    return remember(onPicked) {
        {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "选择目录"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                onPicked(chooser.selectedFile?.absolutePath)
            } else {
                onPicked(null)
            }
        }
    }
}