package cp.player.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowDecorChoiceTest {
    @Test
    fun windowsAutoUsesSystemDecorationsWithOrWithoutJbr() {
        assertEquals(true, WindowDecorChoice.useSystemDecorations(WindowDecorChoice.Mode.AUTO, isWindows = true, jbrSupported = true))
        assertEquals(true, WindowDecorChoice.useSystemDecorations(WindowDecorChoice.Mode.AUTO, isWindows = true, jbrSupported = false))
    }

    @Test
    fun windowsJbrRequestFallsBackToSystemDecorationsWhenUnsupported() {
        assertEquals(true, WindowDecorChoice.useSystemDecorations(WindowDecorChoice.Mode.JBR, isWindows = true, jbrSupported = false))
        assertEquals(true, WindowDecorChoice.useSystemDecorations(WindowDecorChoice.Mode.JBR, isWindows = true, jbrSupported = true))
    }

    @Test
    fun explicitUndecoratedIsHonoredOnWindows() {
        assertEquals(false, WindowDecorChoice.useSystemDecorations(WindowDecorChoice.Mode.UNDECORATED, isWindows = true, jbrSupported = true))
        assertEquals(false, WindowDecorChoice.useSystemDecorations(WindowDecorChoice.Mode.UNDECORATED, isWindows = true, jbrSupported = false))
    }

    @Test
    fun nonWindowsKeepsPreviousJbrAvailabilityBehavior() {
        assertEquals(true, WindowDecorChoice.useSystemDecorations(WindowDecorChoice.Mode.AUTO, isWindows = false, jbrSupported = true))
        assertEquals(false, WindowDecorChoice.useSystemDecorations(WindowDecorChoice.Mode.AUTO, isWindows = false, jbrSupported = false))
        assertEquals(true, WindowDecorChoice.useSystemDecorations(WindowDecorChoice.Mode.JBR, isWindows = false, jbrSupported = true))
        assertEquals(false, WindowDecorChoice.useSystemDecorations(WindowDecorChoice.Mode.JBR, isWindows = false, jbrSupported = false))
        assertEquals(false, WindowDecorChoice.useSystemDecorations(WindowDecorChoice.Mode.UNDECORATED, isWindows = false, jbrSupported = true))
    }
}
