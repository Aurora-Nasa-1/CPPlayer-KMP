package cp.player.app.ui.screen

import cp.player.app.AppModel
import cp.player.app.i18n.CpStringsEn
import cp.player.app.i18n.CpStringsZh
import cp.player.app.ui.component.extractAutoPlayTargetId
import cp.player.core.playback.AutoPlayCustomType
import cp.player.core.playback.AutoPlayMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoPlaySettingTest {

    @Test
    fun autoPlayModeDefaultsToOff() {
        val orig = AppModel.autoPlayMode()
        try {
            AppModel.setAutoPlayMode(AutoPlayMode.OFF)
            assertEquals(AutoPlayMode.OFF, AppModel.autoPlayMode())
            assertEquals(AutoPlayMode.OFF, AppModel.autoPlayModeFlow.value)
        } finally {
            AppModel.setAutoPlayMode(orig)
        }
    }

    @Test
    fun autoPlayModeTogglesAndEnablesKeepLastPlaybackForContinue() {
        val origMode = AppModel.autoPlayMode()
        val origKeep = AppModel.keepLastPlayback()
        try {
            AppModel.setKeepLastPlayback(false)
            assertFalse(AppModel.keepLastPlayback())

            // 设为 CONTINUE 时，应联动开启 keepLastPlayback
            AppModel.setAutoPlayMode(AutoPlayMode.CONTINUE)
            assertEquals(AutoPlayMode.CONTINUE, AppModel.autoPlayMode())
            assertEquals(AutoPlayMode.CONTINUE, AppModel.autoPlayModeFlow.value)
            assertTrue(AppModel.keepLastPlayback())

            // 关闭 keepLastPlayback 时，CONTINUE 模式应自动退回到 OFF
            AppModel.setKeepLastPlayback(false)
            assertEquals(AutoPlayMode.OFF, AppModel.autoPlayMode())

            // 切换到 DAILY
            AppModel.setAutoPlayMode(AutoPlayMode.DAILY)
            assertEquals(AutoPlayMode.DAILY, AppModel.autoPlayMode())

            // 切换到 CUSTOM
            AppModel.setAutoPlayMode(AutoPlayMode.CUSTOM)
            assertEquals(AutoPlayMode.CUSTOM, AppModel.autoPlayMode())
        } finally {
            AppModel.setAutoPlayMode(origMode)
            AppModel.setKeepLastPlayback(origKeep)
        }
    }

    @Test
    fun autoPlayCustomTargetSettingAndClearing() {
        val origType = AppModel.autoPlayCustomType()
        val origId = AppModel.autoPlayCustomId()
        val origTitle = AppModel.autoPlayCustomTitle()
        try {
            AppModel.setAutoPlayCustomTarget(
                type = AutoPlayCustomType.PLAYLIST,
                id = "my-playlist-999",
                title = "我的最爱歌单",
            )
            assertEquals(AutoPlayCustomType.PLAYLIST, AppModel.autoPlayCustomType())
            assertEquals(AutoPlayCustomType.PLAYLIST, AppModel.autoPlayCustomTypeFlow.value)
            assertEquals("my-playlist-999", AppModel.autoPlayCustomId())
            assertEquals("my-playlist-999", AppModel.autoPlayCustomIdFlow.value)
            assertEquals("我的最爱歌单", AppModel.autoPlayCustomTitle())
            assertEquals("我的最爱歌单", AppModel.autoPlayCustomTitleFlow.value)

            // 清除
            AppModel.clearAutoPlayCustomTarget()
            assertEquals("", AppModel.autoPlayCustomId())
            assertEquals("", AppModel.autoPlayCustomTitle())
        } finally {
            AppModel.setAutoPlayCustomTarget(origType, origId, origTitle)
        }
    }

    @Test
    fun extractTargetIdFromVariousInputs() {
        assertEquals("12345", extractAutoPlayTargetId("12345"))
        assertEquals("3778678", extractAutoPlayTargetId("https://music.163.com/#/playlist?id=3778678"))
        assertEquals("186016", extractAutoPlayTargetId("https://music.163.com/song?id=186016"))
        assertEquals("998877", extractAutoPlayTargetId("   /playlist/998877   "))
        assertEquals("custom_id_abc", extractAutoPlayTargetId("  custom_id_abc  "))
    }

    @Test
    fun autoPlayStringsAreNonBlank() {
        assertTrue(CpStringsZh.playback.sectionAutoPlay.isNotBlank())
        assertTrue(CpStringsZh.playback.autoPlayMode.isNotBlank())
        assertTrue(CpStringsZh.playback.autoPlayModeNote.isNotBlank())
        assertTrue(CpStringsZh.playback.autoPlayModeOff.isNotBlank())
        assertTrue(CpStringsZh.playback.autoPlayModeContinue.isNotBlank())
        assertTrue(CpStringsZh.playback.autoPlayModeDaily.isNotBlank())
        assertTrue(CpStringsZh.playback.autoPlayModeCustom.isNotBlank())
        assertTrue(CpStringsZh.playback.autoPlayCustomTarget.isNotBlank())
        assertTrue(CpStringsZh.playback.autoPlayCustomDialogTitle.isNotBlank())

        assertTrue(CpStringsEn.playback.sectionAutoPlay.isNotBlank())
        assertTrue(CpStringsEn.playback.autoPlayMode.isNotBlank())
        assertTrue(CpStringsEn.playback.autoPlayModeNote.isNotBlank())
        assertTrue(CpStringsEn.playback.autoPlayModeOff.isNotBlank())
        assertTrue(CpStringsEn.playback.autoPlayModeContinue.isNotBlank())
        assertTrue(CpStringsEn.playback.autoPlayModeDaily.isNotBlank())
        assertTrue(CpStringsEn.playback.autoPlayModeCustom.isNotBlank())
        assertTrue(CpStringsEn.playback.autoPlayCustomTarget.isNotBlank())
        assertTrue(CpStringsEn.playback.autoPlayCustomDialogTitle.isNotBlank())
    }
}
