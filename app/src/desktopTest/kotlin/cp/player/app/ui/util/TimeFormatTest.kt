package cp.player.app.ui.util

import cp.player.core.util.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeFormatTest {

    @Test
    fun testFormatTimeMs() {
        assertEquals("0:00", formatTimeMs(0))
        assertEquals("0:00", formatTimeMs(-100))
        assertEquals("0:05", formatTimeMs(5000))
        assertEquals("1:00", formatTimeMs(60000))
        assertEquals("3:45", formatTimeMs(225000))
    }

    @Test
    fun testFormatChatListTime() {
        assertEquals("", formatChatListTime(0))
        assertEquals("", formatChatListTime(-1))

        val now = currentTimeMillis()
        val todayStr = formatChatListTime(now)
        // 今天应该只包含时分 HH:mm
        assertTrue(todayStr.matches(Regex("\\d{2}:\\d{2}")), "Today should be HH:mm, got: $todayStr")

        // 去年某时间 (例如 2024-01-01 12:00:00 UTC = 1704110400000L)
        val lastYearMs = 1704110400000L
        val lastYearStr = formatChatListTime(lastYearMs)
        // 跨年应该为 yyyy-MM-dd
        assertTrue(lastYearStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}")), "Past year should be yyyy-MM-dd, got: $lastYearStr")
    }

    @Test
    fun testFormatChatTime() {
        assertEquals("", formatChatTime(0))
        assertEquals("", formatChatTime(-1))

        val now = currentTimeMillis()
        val todayStr = formatChatTime(now)
        // 今天应该只包含时分 HH:mm
        assertTrue(todayStr.matches(Regex("\\d{2}:\\d{2}")), "Today should be HH:mm, got: $todayStr")

        // 去年某时间
        val lastYearMs = 1704110400000L
        val lastYearStr = formatChatTime(lastYearMs)
        // 跨年应该为 yyyy-MM-dd HH:mm
        assertTrue(lastYearStr.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")), "Past year should be yyyy-MM-dd HH:mm, got: $lastYearStr")
    }
}
