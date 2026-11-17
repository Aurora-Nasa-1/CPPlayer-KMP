package cp.player.app.presentation.util

/**
 * 字节数的人类可读格式（框架无关）。
 * commonMain 无 String.format / java.util.Locale，用 round() 手工取整。
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "未知"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kotlin.math.round(kb).toLong()} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${(kotlin.math.round(mb * 10) / 10.0)} MB"
    val gb = mb / 1024.0
    return "${(kotlin.math.round(gb * 100) / 100.0)} GB"
}
