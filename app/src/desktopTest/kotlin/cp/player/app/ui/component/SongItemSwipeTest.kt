package cp.player.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.CpStringsEn
import cp.player.app.i18n.CpStringsZh
import cp.player.app.ui.screen.settingsEntries
import cp.player.app.ui.screen.visibleSubItems
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.util.DesktopShell
import cp.player.core.music.TrackSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongItemSwipeTest {

    private val sampleTrack = TrackSummary(
        id = "swipe-track-1",
        name = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        coverUrl = null,
        durationMs = 269_000L,
    )

    private inline fun withMobilePreviewAndSwipe(
        mobilePreview: Boolean,
        swipeSetting: Boolean,
        block: () -> Unit,
    ) {
        val savedMobile = DesktopShell.isMobileMode
        val savedSwipe = AppModel.songItemSwipeActions()
        DesktopShell.isMobileMode = mobilePreview
        AppModel.setSongItemSwipeActions(swipeSetting)
        try {
            block()
        } finally {
            DesktopShell.isMobileMode = savedMobile
            AppModel.setSongItemSwipeActions(savedSwipe)
        }
    }

    private fun performTouchSwipe(
        dx: Float,
        dy: Float = 0f,
        mobilePreview: Boolean = true,
        swipeSetting: Boolean = true,
        swipeEnabled: Boolean = true,
        selectionMode: Boolean = false,
    ): SwipeOutcome {
        var clicks = 0
        var longClicks = 0
        var playNextCount = 0
        var addToQueueCount = 0

        withMobilePreviewAndSwipe(mobilePreview = mobilePreview, swipeSetting = swipeSetting) {
            val scene = ImageComposeScene(width = 400, height = 96, density = Density(1f)) {
                CpTheme(themeMode = ThemeMode.DARK) {
                    Box(Modifier.width(400.dp)) {
                        SongItem(
                            track = sampleTrack,
                            onClick = { clicks++ },
                            onLongClick = { longClicks++ },
                            onPlayNext = { playNextCount++ },
                            onAddToQueue = { addToQueueCount++ },
                            swipeEnabled = swipeEnabled,
                            selectionMode = selectionMode,
                        )
                    }
                }
            }
            try {
                var t = 0L
                repeat(4) {
                    scene.render(t)
                    t += 16_000_000L
                }
                val start = Offset(200f, 36f)
                val steps = 16
                scene.sendPointerEvent(PointerEventType.Press, start, Offset.Zero, 0L, PointerType.Touch)
                repeat(steps) { i ->
                    scene.sendPointerEvent(
                        PointerEventType.Move,
                        Offset(start.x + dx * (i + 1) / steps, start.y + dy * (i + 1) / steps),
                        Offset.Zero,
                        16L * (i + 1),
                        PointerType.Touch,
                    )
                    scene.render(t)
                    t += 16_000_000L
                }
                scene.sendPointerEvent(
                    PointerEventType.Release,
                    Offset(start.x + dx, start.y + dy),
                    Offset.Zero,
                    300L,
                    PointerType.Touch,
                )
                repeat(12) {
                    scene.render(t)
                    t += 16_000_000L
                }
            } finally {
                scene.close()
            }
        }
        return SwipeOutcome(clicks, longClicks, playNextCount, addToQueueCount)
    }

    private data class SwipeOutcome(
        val clicks: Int,
        val longClicks: Int,
        val playNextCount: Int,
        val addToQueueCount: Int,
    )

    @Test
    fun `songItemSwipeActions setting defaults to true and updates flow`() {
        val original = AppModel.songItemSwipeActions()
        try {
            AppModel.setSongItemSwipeActions(false)
            assertFalse(AppModel.songItemSwipeActions())
            assertFalse(AppModel.songItemSwipeActionsFlow.value)

            AppModel.setSongItemSwipeActions(true)
            assertTrue(AppModel.songItemSwipeActions())
            assertTrue(AppModel.songItemSwipeActionsFlow.value)
        } finally {
            AppModel.setSongItemSwipeActions(original)
        }
    }

    @Test
    fun `i18n strings and settings registry entry are configured`() {
        assertTrue(CpStringsZh.appearance.songSwipeActions.isNotBlank())
        assertTrue(CpStringsZh.appearance.songSwipeActionsNote.isNotBlank())
        assertTrue(CpStringsEn.appearance.songSwipeActions.isNotBlank())
        assertTrue(CpStringsEn.appearance.songSwipeActionsNote.isNotBlank())

        val appearanceEntry = settingsEntries().first { it.id == "appearance" }
        val rawSubItem = appearanceEntry.subItems.firstOrNull { it.id == "appearance_song_swipe_actions" }
        assertTrue(rawSubItem != null, "appearance_song_swipe_actions must exist in SettingsRegistry")
        assertTrue(rawSubItem.androidOnly, "appearance_song_swipe_actions must be androidOnly")
        // Desktop visibleSubItems() hides androidOnly entries
        assertFalse(
            appearanceEntry.visibleSubItems().any { it.id == "appearance_song_swipe_actions" },
            "Desktop must hide song swipe setting",
        )
    }

    @Test
    fun `right swipe triggers playNext without firing click or longClick`() {
        val out = performTouchSwipe(dx = 110f, mobilePreview = true, swipeSetting = true)
        assertEquals(1, out.playNextCount)
        assertEquals(0, out.addToQueueCount)
        assertEquals(0, out.clicks)
        assertEquals(0, out.longClicks)
    }

    @Test
    fun `left swipe triggers addToQueue without firing click or longClick`() {
        val out = performTouchSwipe(dx = -110f, mobilePreview = true, swipeSetting = true)
        assertEquals(0, out.playNextCount)
        assertEquals(1, out.addToQueueCount)
        assertEquals(0, out.clicks)
        assertEquals(0, out.longClicks)
    }

    @Test
    fun `short swipe below threshold does not trigger action`() {
        val out = performTouchSwipe(dx = 40f, mobilePreview = true, swipeSetting = true)
        assertEquals(0, out.playNextCount)
        assertEquals(0, out.addToQueueCount)
        assertEquals(0, out.clicks)
    }

    @Test
    fun `desktop mode ignores swipe actions`() {
        val right = performTouchSwipe(dx = 120f, mobilePreview = false, swipeSetting = true)
        assertEquals(0, right.playNextCount)
        assertEquals(0, right.addToQueueCount)

        val left = performTouchSwipe(dx = -120f, mobilePreview = false, swipeSetting = true)
        assertEquals(0, left.playNextCount)
        assertEquals(0, left.addToQueueCount)
    }

    @Test
    fun `disabled setting or selectionMode disables swipe actions`() {
        val disabledBySetting = performTouchSwipe(dx = 120f, mobilePreview = true, swipeSetting = false)
        assertEquals(0, disabledBySetting.playNextCount)
        assertEquals(0, disabledBySetting.addToQueueCount)

        val disabledBySelection = performTouchSwipe(dx = 120f, mobilePreview = true, swipeSetting = true, selectionMode = true)
        assertEquals(0, disabledBySelection.playNextCount)
        assertEquals(0, disabledBySelection.addToQueueCount)

        val disabledByParam = performTouchSwipe(dx = -120f, mobilePreview = true, swipeSetting = true, swipeEnabled = false)
        assertEquals(0, disabledByParam.playNextCount)
        assertEquals(0, disabledByParam.addToQueueCount)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tap and longClick still work cleanly when swipe is enabled`() = runComposeUiTest {
        var clicks = 0
        var longClicks = 0
        var playNextCount = 0
        var addToQueueCount = 0

        withMobilePreviewAndSwipe(mobilePreview = true, swipeSetting = true) {
            setContent {
                CpTheme(themeMode = ThemeMode.DARK) {
                    SongItem(
                        track = sampleTrack,
                        onClick = { clicks++ },
                        onLongClick = { longClicks++ },
                        onPlayNext = { playNextCount++ },
                        onAddToQueue = { addToQueueCount++ },
                        modifier = Modifier.width(400.dp).testTag("song_item"),
                    )
                }
            }

            onNodeWithTag("song_item").performTouchInput { click() }
            waitForIdle()
            assertEquals(1, clicks)
            assertEquals(0, longClicks)
            assertEquals(0, playNextCount)
            assertEquals(0, addToQueueCount)

            onNodeWithTag("song_item").performTouchInput { longClick() }
            waitForIdle()
            assertEquals(1, clicks)
            assertEquals(1, longClicks)
            assertEquals(0, playNextCount)
            assertEquals(0, addToQueueCount)
        }
    }
}
