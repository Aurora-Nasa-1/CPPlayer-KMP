package cp.player.app.ui.util

/** 把毫秒格式化为 m:ss。 */
fun formatTimeMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}