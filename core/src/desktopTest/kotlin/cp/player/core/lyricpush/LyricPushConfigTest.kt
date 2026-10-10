package cp.player.core.lyricpush

import cp.player.core.util.SettingsStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 投放配置的持久化契约 + SuperLyric 退避曲线。
 *
 * 这一层最容易出的事不是崩溃，而是**静默失效**：键拼错 → 开关打开后重启又变回关；
 * 枚举名字写错 → `enumValueOf` 抛异常把整页设置打死；退避曲线写错 → 没有接收方的
 * 机器上每帧一次跨进程广播。三种都测。
 */
class LyricPushConfigTest {

    private class FakeSettings : SettingsStorage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() = map.clear()
    }

    // =======================================================================
    // 1. 出厂默认：必须全关
    // =======================================================================

    @Test
    fun `empty storage yields an all-off config`() {
        val config = LyricPushConfigStore.read(FakeSettings())
        assertEquals(LyricPushConfig.DEFAULT, config)
        assertFalse(config.anyEnabled, "出厂状态不得开启任何对外渠道")
    }

    @Test
    fun `every channel is covered by anyEnabled`() {
        // anyEnabled 是「要不要驱动投放」的总判据。漏掉一个渠道 ⇒ 那个渠道单独打开时
        // 协调器整条链路不跑，表现为「开关打开了但完全没反应」。
        val base = LyricPushConfig()
        val toggles = listOf<Pair<String, (LyricPushConfig) -> LyricPushConfig>>(
            "lyricon" to { it.copy(lyriconEnabled = true) },
            "superLyric" to { it.copy(superLyricEnabled = true) },
            "lyricGetter" to { it.copy(lyricGetterEnabled = true) },
            "xiaomiSuperIsland" to { it.copy(xiaomiSuperIslandEnabled = true) },
            "colorOs" to { it.copy(colorOsEnabled = true) },
            "statusBar" to { it.copy(statusBarLyricEnabled = true) },
            "headsUp" to { it.copy(headsUpLyricEnabled = true) },
            "liveUpdate" to { it.copy(liveUpdateEnabled = true) },
            "mediaNotification" to { it.copy(mediaNotificationLyricEnabled = true) },
        )
        toggles.forEach { (name, enable) ->
            assertTrue(enable(base).anyEnabled, "anyEnabled 漏了 $name")
        }
    }

    // =======================================================================
    // 2. 往返
    // =======================================================================

    @Test
    fun `config round trips through storage`() {
        val storage = FakeSettings()
        val config = LyricPushConfig(
            lyriconEnabled = true,
            lyriconSecondary = LyricSecondaryMode.PRONUNCIATION,
            superLyricEnabled = true,
            superLyricSecondary = LyricSecondaryMode.OFF,
            lyricGetterEnabled = true,
            xiaomiSuperIslandEnabled = true,
            xiaomiSuperIsland = XiaomiSuperIslandConfig(
                content = LyricContentMode.TRANSLATION,
                lyricMode = XiaomiSuperIslandConfig.LyricMode.STANDARD,
                scrollingEnabled = false,
                rightTextChars = 9,
                textColorEnabled = true,
                colorSource = XiaomiSuperIslandConfig.IslandColorSource.CUSTOM,
                customColor = 0xFF112233.toInt(),
                dismissDelayMs = 3_000,
            ),
            colorOsEnabled = true,
            colorOsMode = OPlusLyricMode.MODULE,
            statusBarLyricEnabled = true,
            statusBarSecondary = LyricSecondaryMode.TRANSLATION,
            headsUpLyricEnabled = true,
            liveUpdateEnabled = true,
            liveUpdateContent = LyricContentMode.PRONUNCIATION,
            liveUpdateDisplay = LiveUpdateDisplayMode.FULL_LINE,
            liveUpdateSecondary = LiveUpdateSecondaryMode.PRONUNCIATION,
            mediaNotificationLyricEnabled = true,
            mediaNotificationSecondary = LyricSecondaryMode.TRANSLATION,
        )
        LyricPushConfigStore.write(storage, config)
        assertEquals(config, LyricPushConfigStore.read(storage))
    }

    @Test
    fun `unknown enum names fall back to the default instead of throwing`() {
        val storage = FakeSettings()
        // 模拟「枚举改名 / 删项之后老配置里留着旧名字」。
        storage.putString(LyricPushConfig.KEY_LYRICON_SECONDARY, "ROMANIZED")
        storage.putString(LyricPushConfig.KEY_COLOROS_MODE, "BRIDGE_5")
        storage.putString(LyricPushConfig.KEY_LIVE_UPDATE_DISPLAY, "SCROLLING")

        val config = LyricPushConfigStore.read(storage)
        assertEquals(LyricSecondaryMode.TRANSLATION, config.lyriconSecondary)
        assertEquals(OPlusLyricMode.SYSTEM, config.colorOsMode)
        assertEquals(LiveUpdateDisplayMode.WORD_WINDOW, config.liveUpdateDisplay)
    }

    @Test
    fun `non boolean strings fall back to false rather than throwing`() {
        val storage = FakeSettings()
        storage.putString(LyricPushConfig.KEY_LYRICON_ENABLED, "yes")
        assertFalse(LyricPushConfigStore.read(storage).lyriconEnabled, "非法布尔按「关」处理（fail-closed）")
    }

    // =======================================================================
    // 3. 超级岛配置
    // =======================================================================

    @Test
    fun `island config round trips`() {
        val config = XiaomiSuperIslandConfig(
            content = LyricContentMode.TRANSLATION,
            lyricMode = XiaomiSuperIslandConfig.LyricMode.STANDARD,
            fullLyricShowLeftCover = true,
            scrollingEnabled = false,
            rightTextChars = 11,
            leftWithCoverTextChars = 8,
            leftWithoutCoverTextChars = 12,
            textColorEnabled = true,
            colorSource = XiaomiSuperIslandConfig.IslandColorSource.CUSTOM,
            customColor = 0xFF00FF00.toInt(),
            progressColorEnabled = true,
            dismissDelayMs = 1_000,
            xmsfMode = XiaomiSuperIslandConfig.XmsfIsolationMode.ENHANCED,
            xmsfBlockDurationMs = 300,
        )
        assertEquals(config, XiaomiSuperIslandConfig.decode(config.encode()))
    }

    @Test
    fun `island xmsf block duration snaps to the nearest preset`() {
        // 阻断时长在设置页只能按预设选；手改配置留下一个中间值时必须吸附，
        // 否则滑杆的 selectedIndex 会 indexOf 落空、显示成第一档。
        assertEquals(
            200,
            XiaomiSuperIslandConfig(xmsfBlockDurationMs = 180).sanitized().xmsfBlockDurationMs,
        )
        assertEquals(
            500,
            XiaomiSuperIslandConfig(xmsfBlockDurationMs = 9_999).sanitized().xmsfBlockDurationMs,
        )
        assertEquals(
            100,
            XiaomiSuperIslandConfig(xmsfBlockDurationMs = -50).sanitized().xmsfBlockDurationMs,
        )
        // 默认值本身必须在预设里，否则「默认配置往返」会被静默改写。
        assertTrue(
            XiaomiSuperIslandConfig.DEFAULT_XMSF_BLOCK_MS in XiaomiSuperIslandConfig.XMSF_BLOCK_PRESETS_MS,
        )
    }

    @Test
    fun `unknown xmsf mode falls back to standard`() {
        // 枚举改名 / 删项之后老配置里留着旧名字，必须回退默认而不是抛异常。
        val storage = FakeSettings()
        storage.putString(
            LyricPushConfig.KEY_XIAOMI_ISLAND_SETTINGS,
            XiaomiSuperIslandConfig().encode().replace("STANDARD", "AGGRESSIVE_5"),
        )
        assertEquals(
            XiaomiSuperIslandConfig.XmsfIsolationMode.STANDARD,
            LyricPushConfigStore.read(storage).xiaomiSuperIsland.xmsfMode,
        )
    }

    @Test
    fun `blank or corrupt island config falls back to defaults`() {
        assertEquals(XiaomiSuperIslandConfig(), XiaomiSuperIslandConfig.decode(null))
        assertEquals(XiaomiSuperIslandConfig(), XiaomiSuperIslandConfig.decode(""))
        assertEquals(XiaomiSuperIslandConfig(), XiaomiSuperIslandConfig.decode("not json at all"))
    }

    @Test
    fun `island config sanitizes out of range values`() {
        val broken = XiaomiSuperIslandConfig(
            rightTextChars = 999,
            leftWithCoverTextChars = 0,
            leftWithoutCoverTextChars = -4,
            dismissDelayMs = 777,
        ).sanitized()

        assertTrue(broken.rightTextChars in XiaomiSuperIslandConfig.RIGHT_CHARS_RANGE)
        assertTrue(broken.leftWithCoverTextChars in XiaomiSuperIslandConfig.LEFT_WITH_COVER_RANGE)
        assertTrue(broken.leftWithoutCoverTextChars in XiaomiSuperIslandConfig.LEFT_WITHOUT_COVER_RANGE)
        // 不在白名单里的收起延迟一律归零（= 立即收起），而不是取最近的合法值：
        // 「暂停后过 777ms 收起」不是任何一档，说明配置已经坏了。
        assertEquals(0, broken.dismissDelayMs)
    }

    @Test
    fun `island custom color is forced opaque`() {
        // 只有 RGB 没有 alpha 时补上 FF：focus-api 的色字段带 alpha，缺了会被渲染成全透明。
        assertEquals(0xFF123456.toInt(), XiaomiSuperIslandConfig(customColor = 0x123456).sanitized().customColor)
    }

    // =======================================================================
    // 4. SuperLyric 退避
    // =======================================================================

    @Test
    fun `super lyric retry backs off exponentially and caps at five minutes`() {
        // 曲线：30s → 30s → 60s → 120s → 240s → 300s（封顶）→ 300s …
        assertEquals(30_000L, superLyricRetryDelayMs(0), "非法输入按第一次失败处理")
        assertEquals(30_000L, superLyricRetryDelayMs(1))
        assertEquals(60_000L, superLyricRetryDelayMs(2))
        assertEquals(120_000L, superLyricRetryDelayMs(3))
        assertEquals(240_000L, superLyricRetryDelayMs(4))
        // 封顶：再失败也不超过 5 分钟。否则用户「刚装上 SuperLyric」要等太久才生效。
        assertEquals(SUPER_LYRIC_MAX_RETRY_MS, superLyricRetryDelayMs(5))
        assertEquals(SUPER_LYRIC_MAX_RETRY_MS, superLyricRetryDelayMs(500))
    }

    @Test
    fun `super lyric retry never goes below the initial delay`() {
        (0..40).forEach { failures ->
            assertTrue(
                superLyricRetryDelayMs(failures) >= SUPER_LYRIC_INITIAL_RETRY_MS,
                "第 $failures 次失败的等待时间短于起点 —— 会退化成广播风暴",
            )
        }
    }
}
