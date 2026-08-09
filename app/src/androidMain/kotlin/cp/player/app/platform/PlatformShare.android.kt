package cp.player.app.platform

import android.content.Intent

actual fun shareText(text: String) {
    val ctx = ctxOrNull ?: return
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    } catch (_: Exception) {
    }
}
