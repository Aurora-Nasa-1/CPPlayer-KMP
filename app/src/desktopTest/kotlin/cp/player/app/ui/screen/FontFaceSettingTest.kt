package cp.player.app.ui.screen

import cp.player.app.AppModel
import cp.player.app.i18n.CpStringsEn
import cp.player.app.i18n.CpStringsZh
import cp.player.app.ui.theme.AppFontFace
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontFaceSettingTest {

    @Test
    fun `fontFace preference toggles and updates state flow individually`() {
        val originalGoogleSans = AppModel.isFontFaceEnabled(AppFontFace.GOOGLE_SANS_FLEX)
        val originalNoto = AppModel.isFontFaceEnabled(AppFontFace.NOTO_SANS_JP)
        val originalMiSans = AppModel.isFontFaceEnabled(AppFontFace.MISANS)

        try {
            AppModel.setFontFaceEnabled(AppFontFace.GOOGLE_SANS_FLEX, false)
            assertFalse(AppModel.isFontFaceEnabled(AppFontFace.GOOGLE_SANS_FLEX))
            assertFalse(AppModel.fontFaceEnabledFlow.value[AppFontFace.GOOGLE_SANS_FLEX] ?: true)
            // Noto and MiSans should remain untouched
            assertTrue(AppModel.isFontFaceEnabled(AppFontFace.NOTO_SANS_JP))
            assertTrue(AppModel.isFontFaceEnabled(AppFontFace.MISANS))

            AppModel.setFontFaceEnabled(AppFontFace.NOTO_SANS_JP, false)
            assertFalse(AppModel.isFontFaceEnabled(AppFontFace.NOTO_SANS_JP))
            assertFalse(AppModel.fontFaceEnabledFlow.value[AppFontFace.NOTO_SANS_JP] ?: true)

            AppModel.setFontFaceEnabled(AppFontFace.MISANS, false)
            assertFalse(AppModel.isFontFaceEnabled(AppFontFace.MISANS))
            assertFalse(AppModel.fontFaceEnabledFlow.value[AppFontFace.MISANS] ?: true)

            // Re-enable individually
            AppModel.setFontFaceEnabled(AppFontFace.GOOGLE_SANS_FLEX, true)
            assertTrue(AppModel.isFontFaceEnabled(AppFontFace.GOOGLE_SANS_FLEX))
            assertTrue(AppModel.fontFaceEnabledFlow.value[AppFontFace.GOOGLE_SANS_FLEX] ?: false)
            assertFalse(AppModel.isFontFaceEnabled(AppFontFace.NOTO_SANS_JP))
            assertFalse(AppModel.isFontFaceEnabled(AppFontFace.MISANS))

            AppModel.setFontFaceEnabled(AppFontFace.MISANS, true)
            assertTrue(AppModel.isFontFaceEnabled(AppFontFace.MISANS))
            assertTrue(AppModel.fontFaceEnabledFlow.value[AppFontFace.MISANS] ?: false)
            assertFalse(AppModel.isFontFaceEnabled(AppFontFace.NOTO_SANS_JP))
        } finally {
            AppModel.setFontFaceEnabled(AppFontFace.GOOGLE_SANS_FLEX, originalGoogleSans)
            AppModel.setFontFaceEnabled(AppFontFace.NOTO_SANS_JP, originalNoto)
            AppModel.setFontFaceEnabled(AppFontFace.MISANS, originalMiSans)
        }
    }

    @Test
    fun `i18n strings for fontPacks are non-blank`() {
        assertTrue(CpStringsZh.fontPacks.googleSansTitle.isNotBlank())
        assertTrue(CpStringsZh.fontPacks.googleSansNote.isNotBlank())
        assertTrue(CpStringsZh.fontPacks.japaneseTitle.isNotBlank())
        assertTrue(CpStringsZh.fontPacks.japaneseNote.isNotBlank())
        assertTrue(CpStringsZh.fontPacks.misansTitle.isNotBlank())
        assertTrue(CpStringsZh.fontPacks.misansNote.isNotBlank())

        assertTrue(CpStringsEn.fontPacks.googleSansTitle.isNotBlank())
        assertTrue(CpStringsEn.fontPacks.googleSansNote.isNotBlank())
        assertTrue(CpStringsEn.fontPacks.japaneseTitle.isNotBlank())
        assertTrue(CpStringsEn.fontPacks.japaneseNote.isNotBlank())
        assertTrue(CpStringsEn.fontPacks.misansTitle.isNotBlank())
        assertTrue(CpStringsEn.fontPacks.misansNote.isNotBlank())
    }
}
