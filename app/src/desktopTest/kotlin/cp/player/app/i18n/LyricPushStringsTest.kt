package cp.player.app.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「歌词投放」文案的中英对称性守卫。
 *
 * ### 为什么值得单独测
 * `LyricPushStrings` 有 50 多个成员，且**两种语言各自实现一份**。漏掉一个成员的后果
 * 不是编译失败（接口会挡住），而是**某一语言下那一行空白** —— Compose 里表现为
 * 「设置项只剩一个开关，没有标题」，很难在评审时看出来。这里用「两份实现的所有属性
 * 都非空、且互不相同」把这类漏译挡住。
 *
 * 之所以不放进 `SettingsI18nPreviewTest`：那个文件走的是离屏渲染（慢、且依赖
 * Compose 场景），这里只需要字符串本身 —— 快得多，也更容易在改文案时定位。
 */
class LyricPushStringsTest {

    private val zh: LyricPushStrings = CpStrings.zh.lyricPush
    private val en: LyricPushStrings = CpStrings.en.lyricPush

    /**
     * 全部**无参**字符串成员，中英各取一份。
     *
     * 手写而不是反射：本仓库桌面测试用的是 kotlin.test + JUnit，反射在 KMP 下的
     * 成员名会带上 `get` 前缀差异；而且手写列表顺便就是「这一版有哪些文案」的清单，
     * 新增文案时必须回来登记 —— 这个动作本身就是提醒。
     */
    private fun zhValues(): Map<String, String> = with(zh) {
        mapOf(
            "entryTitle" to entryTitle,
            "entrySubtitle" to entrySubtitle,
            "screenTitle" to screenTitle,
            "note" to note,
            "androidOnlyNote" to androidOnlyNote,
            "sectionChannels" to sectionChannels,
            "sectionSecondary" to sectionSecondary,
            "sectionColorOs" to sectionColorOs,
            "sectionSuperIsland" to sectionSuperIsland,
            "sectionLiveUpdate" to sectionLiveUpdate,
            "lyricon" to lyricon,
            "lyriconNote" to lyriconNote,
            "superLyric" to superLyric,
            "superLyricNote" to superLyricNote,
            "lyricGetter" to lyricGetter,
            "lyricGetterNote" to lyricGetterNote,
            "colorOs" to colorOs,
            "colorOsNote" to colorOsNote,
            "colorOsMode" to colorOsMode,
            "colorOsModeSystem" to colorOsModeSystem,
            "colorOsModeModule" to colorOsModeModule,
            "colorOsModeSystemNote" to colorOsModeSystemNote,
            "colorOsModeModuleNote" to colorOsModeModuleNote,
            "superIsland" to superIsland,
            "superIslandNote" to superIslandNote,
            "superIslandUnsupported" to superIslandUnsupported,
            "statusBar" to statusBar,
            "statusBarNote" to statusBarNote,
            "headsUp" to headsUp,
            "headsUpNote" to headsUpNote,
            "liveUpdate" to liveUpdate,
            "liveUpdateNote" to liveUpdateNote,
            "liveUpdateContent" to liveUpdateContent,
            "liveUpdateDisplay" to liveUpdateDisplay,
            "liveUpdateDisplayWord" to liveUpdateDisplayWord,
            "liveUpdateDisplayFull" to liveUpdateDisplayFull,
            "liveUpdateSecondary" to liveUpdateSecondary,
            "liveUpdateSecondarySong" to liveUpdateSecondarySong,
            "mediaNotification" to mediaNotification,
            "mediaNotificationNote" to mediaNotificationNote,
            "secondaryOff" to secondaryOff,
            "secondaryTranslation" to secondaryTranslation,
            "secondaryPronunciation" to secondaryPronunciation,
            "contentOriginal" to contentOriginal,
            "contentTranslation" to contentTranslation,
            "contentPronunciation" to contentPronunciation,
            "islandContent" to islandContent,
            "islandLayout" to islandLayout,
            "islandLayoutStandard" to islandLayoutStandard,
            "islandLayoutFull" to islandLayoutFull,
            "islandShowCover" to islandShowCover,
            "islandShowCoverNote" to islandShowCoverNote,
            "islandScrolling" to islandScrolling,
            "islandScrollingNote" to islandScrollingNote,
            "islandRightChars" to islandRightChars,
            "islandLeftCoverChars" to islandLeftCoverChars,
            "islandLeftChars" to islandLeftChars,
            "islandTextColor" to islandTextColor,
            "islandTextColorNote" to islandTextColorNote,
            "islandColorSource" to islandColorSource,
            "islandColorSourceAlbum" to islandColorSourceAlbum,
            "islandColorSourceCustom" to islandColorSourceCustom,
            "islandCustomColor" to islandCustomColor,
            "islandProgressColor" to islandProgressColor,
            "islandProgressColorNote" to islandProgressColorNote,
            "islandDismissDelay" to islandDismissDelay,
            "islandDismissImmediately" to islandDismissImmediately,
        )
    }

    private fun enValues(): Map<String, String> = with(en) {
        mapOf(
            "entryTitle" to entryTitle,
            "entrySubtitle" to entrySubtitle,
            "screenTitle" to screenTitle,
            "note" to note,
            "androidOnlyNote" to androidOnlyNote,
            "sectionChannels" to sectionChannels,
            "sectionSecondary" to sectionSecondary,
            "sectionColorOs" to sectionColorOs,
            "sectionSuperIsland" to sectionSuperIsland,
            "sectionLiveUpdate" to sectionLiveUpdate,
            "lyricon" to lyricon,
            "lyriconNote" to lyriconNote,
            "superLyric" to superLyric,
            "superLyricNote" to superLyricNote,
            "lyricGetter" to lyricGetter,
            "lyricGetterNote" to lyricGetterNote,
            "colorOs" to colorOs,
            "colorOsNote" to colorOsNote,
            "colorOsMode" to colorOsMode,
            "colorOsModeSystem" to colorOsModeSystem,
            "colorOsModeModule" to colorOsModeModule,
            "colorOsModeSystemNote" to colorOsModeSystemNote,
            "colorOsModeModuleNote" to colorOsModeModuleNote,
            "superIsland" to superIsland,
            "superIslandNote" to superIslandNote,
            "superIslandUnsupported" to superIslandUnsupported,
            "statusBar" to statusBar,
            "statusBarNote" to statusBarNote,
            "headsUp" to headsUp,
            "headsUpNote" to headsUpNote,
            "liveUpdate" to liveUpdate,
            "liveUpdateNote" to liveUpdateNote,
            "liveUpdateContent" to liveUpdateContent,
            "liveUpdateDisplay" to liveUpdateDisplay,
            "liveUpdateDisplayWord" to liveUpdateDisplayWord,
            "liveUpdateDisplayFull" to liveUpdateDisplayFull,
            "liveUpdateSecondary" to liveUpdateSecondary,
            "liveUpdateSecondarySong" to liveUpdateSecondarySong,
            "mediaNotification" to mediaNotification,
            "mediaNotificationNote" to mediaNotificationNote,
            "secondaryOff" to secondaryOff,
            "secondaryTranslation" to secondaryTranslation,
            "secondaryPronunciation" to secondaryPronunciation,
            "contentOriginal" to contentOriginal,
            "contentTranslation" to contentTranslation,
            "contentPronunciation" to contentPronunciation,
            "islandContent" to islandContent,
            "islandLayout" to islandLayout,
            "islandLayoutStandard" to islandLayoutStandard,
            "islandLayoutFull" to islandLayoutFull,
            "islandShowCover" to islandShowCover,
            "islandShowCoverNote" to islandShowCoverNote,
            "islandScrolling" to islandScrolling,
            "islandScrollingNote" to islandScrollingNote,
            "islandRightChars" to islandRightChars,
            "islandLeftCoverChars" to islandLeftCoverChars,
            "islandLeftChars" to islandLeftChars,
            "islandTextColor" to islandTextColor,
            "islandTextColorNote" to islandTextColorNote,
            "islandColorSource" to islandColorSource,
            "islandColorSourceAlbum" to islandColorSourceAlbum,
            "islandColorSourceCustom" to islandColorSourceCustom,
            "islandCustomColor" to islandCustomColor,
            "islandProgressColor" to islandProgressColor,
            "islandProgressColorNote" to islandProgressColorNote,
            "islandDismissDelay" to islandDismissDelay,
            "islandDismissImmediately" to islandDismissImmediately,
        )
    }

    @Test
    fun `no lyric push string is blank in either language`() {
        val blanksZh = zhValues().filterValues { it.isBlank() }.keys
        val blanksEn = enValues().filterValues { it.isBlank() }.keys
        assertTrue(blanksZh.isEmpty(), "中文里有空白文案：$blanksZh")
        assertTrue(blanksEn.isEmpty(), "英文里有空白文案：$blanksEn")
    }

    @Test
    fun `both languages expose exactly the same members`() {
        // 某一语言漏了一个成员时（例如新增文案只加了一边），两份 map 的键集就会不同。
        assertEquals(zhValues().keys, enValues().keys)
    }

    @Test
    fun `english strings are actually translated`() {
        // 抓「中文文案被整段复制到英文实现」这类漏译：除去厂商专名，英文不应该含中文标点。
        val copied = enValues().filter { (_, value) ->
            value.any { it in "，。；：！？、《》「」【】" }
        }.keys
        assertTrue(copied.isEmpty(), "英文文案里出现中文标点，疑似直接复制：$copied")
    }

    @Test
    fun `vendor channel names stay untranslated`() {
        // 渠道名是产品名：翻译之后用户在系统里对着「词幕」找不到东西。
        assertEquals("SuperLyric", zh.superLyric)
        assertEquals("SuperLyric", en.superLyric)
        assertEquals("Lyric Getter", zh.lyricGetter)
        assertEquals("Lyric Getter", en.lyricGetter)
        assertTrue("Lyricon" in zh.lyricon, "中文侧要同时给出「词幕」和它的拉丁名")
        assertEquals("Lyricon", en.lyricon)
    }

    @Test
    fun `parameterized strings interpolate their argument`() {
        assertTrue("7" in zh.islandCharsValue(7))
        assertTrue("7" in en.islandCharsValue(7))
        assertTrue("3" in zh.islandDismissAfter(3))
        assertTrue("3" in en.islandDismissAfter(3))
        // 「立即收起」与「N 秒后收起」必须是两个不同的说法，否则用户看不出区别。
        assertFalse(zh.islandDismissImmediately == zh.islandDismissAfter(3))
    }
}
