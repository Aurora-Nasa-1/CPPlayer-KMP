package cp.player.app.ui.screen

import cp.player.app.AppModel
import cp.player.app.i18n.CpStringsEn
import cp.player.app.i18n.CpStringsZh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenTogetherAddNextSettingTest {

    @Test
    fun `listenTogetherAddNext defaults to true`() {
        // 初始或重置后默认行为应为「添加到下一首播放」
        assertTrue(AppModel.listenTogetherAddNext())
        assertTrue(AppModel.listenTogetherAddNextFlow.value)
    }

    @Test
    fun `listenTogetherAddNext preference toggles and updates state flow`() {
        val original = AppModel.listenTogetherAddNext()
        try {
            AppModel.setListenTogetherAddNext(false)
            assertFalse(AppModel.listenTogetherAddNext())
            assertFalse(AppModel.listenTogetherAddNextFlow.value)

            AppModel.setListenTogetherAddNext(true)
            assertTrue(AppModel.listenTogetherAddNext())
            assertTrue(AppModel.listenTogetherAddNextFlow.value)

            AppModel.setListenTogetherAddNext(false)
            assertFalse(AppModel.listenTogetherAddNext())
            assertFalse(AppModel.listenTogetherAddNextFlow.value)
        } finally {
            AppModel.setListenTogetherAddNext(original)
        }
    }

    @Test
    fun `together strings for addNextOnPlay and toast are non-blank`() {
        assertTrue(CpStringsZh.social.together.sectionSettings.isNotBlank())
        assertTrue(CpStringsZh.social.together.addNextOnPlay.isNotBlank())
        assertTrue(CpStringsZh.social.together.addNextOnPlayNote.isNotBlank())
        assertTrue(CpStringsZh.social.together.addedToNextToast.isNotBlank())

        assertTrue(CpStringsEn.social.together.sectionSettings.isNotBlank())
        assertTrue(CpStringsEn.social.together.addNextOnPlay.isNotBlank())
        assertTrue(CpStringsEn.social.together.addNextOnPlayNote.isNotBlank())
        assertTrue(CpStringsEn.social.together.addedToNextToast.isNotBlank())

        assertEquals("一起听中：已添加到下一首播放", CpStringsZh.social.together.addedToNextToast)
    }
}
