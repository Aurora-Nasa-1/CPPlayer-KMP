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
 * 私信会话列表的时间戳：
 * - 今天：只给 `HH:mm`（如 `15:04`）
 * - 同一年其他天：给 `MM-dd`（如 `10-25`），避免在宽度有限的列表项挤压昵称空间
 * - 跨年：给 `yyyy-MM-dd`（如 `2025-10-25`）
 */
fun formatChatListTime(ms: Long): String {
    if (ms <= 0L) return ""
    val dt = runCatching { localDateTimeOf(ms) }.getOrNull() ?: return ""
    val now = runCatching { localDateTimeOf(cp.player.core.util.currentTimeMillis()) }.getOrNull()
        ?: return ""
    return when {
        dt.year == now.year && dt.month == now.month && dt.day == now.day -> {
            "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
        }
        dt.year == now.year -> {
            "${dt.month.toString().padStart(2, '0')}-${dt.day.toString().padStart(2, '0')}"
        }
        else -> {
            val yyyy = dt.year.toString().padStart(4, '0')
            val mm = dt.month.toString().padStart(2, '0')
            val dd = dt.day.toString().padStart(2, '0')
            "$yyyy-$mm-$dd"
        }
    }
}

/**
 * 私信场景的时间戳：今天只给 `HH:mm`，同一年给 `MM-dd HH:mm`，跨年补齐 `yyyy-MM-dd HH:mm`。
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
    return when {
        sameDay -> hhmm
        dt.year == now.year -> {
            "${dt.month.toString().padStart(2, '0')}-${dt.day.toString().padStart(2, '0')} $hhmm"
        }
        else -> {
            val yyyy = dt.year.toString().padStart(4, '0')
            val mm = dt.month.toString().padStart(2, '0')
            val dd = dt.day.toString().padStart(2, '0')
            "$yyyy-$mm-$dd $hhmm"
        }
    }
}

/**
 * 将发行时间毫秒时间戳格式化为 yyyy-MM-dd。
 *
 * 走 [localDateTimeOf]（expect/actual），不依赖 kotlinx-datetime，全平台安全。
 */
fun formatPublishDate(ms: Long): String {
    if (ms <= 0L) return ""
    val dt = runCatching { localDateTimeOf(ms) }.getOrNull() ?: return ""
    val yyyy = dt.year.toString().padStart(4, '0')
    val mm = dt.month.toString().padStart(2, '0')
    val dd = dt.day.toString().padStart(2, '0')
    return "$yyyy-$mm-$dd"
}

/**
 * 字节数的人类可读格式。
 * commonMain 无 String.format / java.util.Locale，用 roundToInt() 手工取整。
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
