package cp.player.app.ui.util

import cp.player.core.util.localDateTimeOf

/** 把毫秒格式化为 m:ss。 */
fun formatTimeMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

/**
 * 私信场景的时间戳：今天只给 `HH:mm`，其余给 `MM-dd HH:mm`。
 *
 * ⚠️ **不用 kotlinx-datetime**。那个库在本工程的运行时类路径上解析到的是 0.7.x
 * （编译期是 0.6.x），而 0.7 起 `kotlinx.datetime.Instant` 变成了指向 `kotlin.time.Instant`
 * 的 typealias —— 类文件不存在，一碰就 `NoClassDefFoundError`。
 * 日期换算统一走 `cp.player.core.util.localDateTimeOf`（expect/actual，与系统时区一致）。
 */
fun formatChatTime(ms: Long): String {
    if (ms <= 0L) return ""
    val dt = runCatching { localDateTimeOf(ms) }.getOrNull() ?: return ""
    val now = runCatching { localDateTimeOf(cp.player.core.util.currentTimeMillis()) }.getOrNull()
        ?: return ""
    val hhmm = "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
    val sameDay = dt.year == now.year && dt.month == now.month && dt.day == now.day
    return if (sameDay) hhmm else {
        "${dt.month.toString().padStart(2, '0')}-${dt.day.toString().padStart(2, '0')} $hhmm"
    }
}