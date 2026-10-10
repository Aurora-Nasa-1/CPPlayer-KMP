/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/data/SystemLyricSettingsAccess.kt
 *          (SettingsManager.KEY_LYRICON_* / KEY_SUPER_LYRIC_* / KEY_COLOROS_* / KEY_TICKER_* 等 25 个 key)
 * Changes: 存储由 DataStore Preferences 换成 SettingsStorage（只有 String，布尔走 "true"/"false"）；
 *          25 个分散的 key 收敛成一份 LyricPushConfig，全部默认关闭（对外投放一律 fail-closed，
 *          与 LocalServerConfig 的安全默认策略一致）；Halcyon 里「翻译 / 注音」两个互斥布尔
 *          合成 LyricSecondaryMode 三态枚举，互斥性由类型本身保证而不是靠运行时互相关掉。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import cp.player.core.util.SettingsStorage

/**
 * 副行（第二行）传递内容。
 *
 * 三态而不是两个布尔：Halcyon 里 `translation` 与 `pronunciation` 可以同时为真，
 * 只能靠 ViewModel 在 collect 到其中一个变 true 时把另一个写回 false，
 * 而「写回」在设置页直接改 DataStore 的那条路径上并不可靠。
 */
enum class LyricSecondaryMode {
    /** 只传原文。 */
    OFF,

    /** 副行传翻译。 */
    TRANSLATION,

    /** 副行传注音 / 罗马音。 */
    PRONUNCIATION,
}

/** ColorOS 锁屏岛的歌词传递模式。 */
enum class OPlusLyricMode {
    /** 系统 `lyricInfo`：只给整首行级 LRC，兼容性最好。 */
    SYSTEM,

    /** 模块 `rawLyric`：额外给逐字时间轴与翻译行，需要接收端是 Bridge 4.0 模块。 */
    MODULE,
}

/** 投递给外部渠道的**主歌词**内容（超级岛与 Live Update 共用）。 */
enum class LyricContentMode { ORIGINAL, TRANSLATION, PRONUNCIATION }

/** Android 16 实时活动通知的歌词显示范围。 */
enum class LiveUpdateDisplayMode { WORD_WINDOW, FULL_LINE }

/** Android 16 实时活动通知的副标题内容。 */
enum class LiveUpdateSecondaryMode { SONG, TRANSLATION, PRONUNCIATION }

/**
 * 歌词对外投放的**唯一配置**。
 *
 * ### 为什么默认全关
 * 这些开关的共同点是「把用户正在听什么、听到哪一句，写到进程之外的地方」。
 * 与 `LocalServerConfig` 的对外面同源同理：默认关，用户显式打开才生效。
 *
 * ### 为什么合成一份而不是 8 组独立开关
 * 设置页要一次性读出来渲染，后端也要一次性 `applyConfig`。分散成 8 组读取
 * 会让「改一个开关要不要重发歌词」这个问题在每个调用点各答一遍。
 */
data class LyricPushConfig(
    // —— 词幕（Lyricon）——
    val lyriconEnabled: Boolean = false,
    val lyriconSecondary: LyricSecondaryMode = LyricSecondaryMode.TRANSLATION,
    // —— SuperLyric ——
    val superLyricEnabled: Boolean = false,
    val superLyricSecondary: LyricSecondaryMode = LyricSecondaryMode.TRANSLATION,
    // —— Lyric Getter ——
    val lyricGetterEnabled: Boolean = false,
    // —— HyperOS 超级小岛 ——
    val xiaomiSuperIslandEnabled: Boolean = false,
    val xiaomiSuperIsland: XiaomiSuperIslandConfig = XiaomiSuperIslandConfig(),
    // —— ColorOS 锁屏岛 ——
    val colorOsEnabled: Boolean = false,
    val colorOsMode: OPlusLyricMode = OPlusLyricMode.SYSTEM,
    // —— 状态栏歌词（Flyme Ticker）/ 浮动通知歌词 ——
    val statusBarLyricEnabled: Boolean = false,
    val statusBarSecondary: LyricSecondaryMode = LyricSecondaryMode.OFF,
    val headsUpLyricEnabled: Boolean = false,
    // —— Android 16 实时活动（Live Update）——
    val liveUpdateEnabled: Boolean = false,
    val liveUpdateContent: LyricContentMode = LyricContentMode.ORIGINAL,
    val liveUpdateDisplay: LiveUpdateDisplayMode = LiveUpdateDisplayMode.WORD_WINDOW,
    val liveUpdateSecondary: LiveUpdateSecondaryMode = LiveUpdateSecondaryMode.SONG,
    // —— 媒体通知歌词（蓝牙 / 车机 / 媒体中心）——
    val mediaNotificationLyricEnabled: Boolean = false,
    val mediaNotificationSecondary: LyricSecondaryMode = LyricSecondaryMode.TRANSLATION,
) {
    /** 是否至少有一个渠道开启。全关时后端可以完全不驱动投放。 */
    val anyEnabled: Boolean
        get() = lyriconEnabled || superLyricEnabled || lyricGetterEnabled ||
            xiaomiSuperIslandEnabled || colorOsEnabled || statusBarLyricEnabled ||
            headsUpLyricEnabled || liveUpdateEnabled || mediaNotificationLyricEnabled

    companion object {
        // —— SettingsStorage 键名（唯一事实源；拼错会静默失效）——
        const val KEY_LYRICON_ENABLED = "lyric_push_lyricon_enabled"
        const val KEY_LYRICON_SECONDARY = "lyric_push_lyricon_secondary"
        const val KEY_SUPER_LYRIC_ENABLED = "lyric_push_super_lyric_enabled"
        const val KEY_SUPER_LYRIC_SECONDARY = "lyric_push_super_lyric_secondary"
        const val KEY_LYRIC_GETTER_ENABLED = "lyric_push_lyric_getter_enabled"
        const val KEY_XIAOMI_ISLAND_ENABLED = "lyric_push_xiaomi_island_enabled"
        const val KEY_XIAOMI_ISLAND_SETTINGS = "lyric_push_xiaomi_island_settings"
        const val KEY_COLOROS_ENABLED = "lyric_push_coloros_enabled"
        const val KEY_COLOROS_MODE = "lyric_push_coloros_mode"
        const val KEY_STATUS_BAR_ENABLED = "lyric_push_status_bar_enabled"
        const val KEY_STATUS_BAR_SECONDARY = "lyric_push_status_bar_secondary"
        const val KEY_HEADS_UP_ENABLED = "lyric_push_heads_up_enabled"
        const val KEY_LIVE_UPDATE_ENABLED = "lyric_push_live_update_enabled"
        const val KEY_LIVE_UPDATE_CONTENT = "lyric_push_live_update_content"
        const val KEY_LIVE_UPDATE_DISPLAY = "lyric_push_live_update_display"
        const val KEY_LIVE_UPDATE_SECONDARY = "lyric_push_live_update_secondary"
        const val KEY_MEDIA_NOTIFY_ENABLED = "lyric_push_media_notify_enabled"
        const val KEY_MEDIA_NOTIFY_SECONDARY = "lyric_push_media_notify_secondary"

        val DEFAULT = LyricPushConfig()
    }
}

/** [LyricPushConfig] 的持久化读写。 */
object LyricPushConfigStore {

    fun read(settings: SettingsStorage): LyricPushConfig = LyricPushConfig(
        lyriconEnabled = settings.bool(LyricPushConfig.KEY_LYRICON_ENABLED, false),
        lyriconSecondary = settings.enum(
            LyricPushConfig.KEY_LYRICON_SECONDARY,
            LyricSecondaryMode.TRANSLATION,
        ),
        superLyricEnabled = settings.bool(LyricPushConfig.KEY_SUPER_LYRIC_ENABLED, false),
        superLyricSecondary = settings.enum(
            LyricPushConfig.KEY_SUPER_LYRIC_SECONDARY,
            LyricSecondaryMode.TRANSLATION,
        ),
        lyricGetterEnabled = settings.bool(LyricPushConfig.KEY_LYRIC_GETTER_ENABLED, false),
        xiaomiSuperIslandEnabled = settings.bool(LyricPushConfig.KEY_XIAOMI_ISLAND_ENABLED, false),
        xiaomiSuperIsland = XiaomiSuperIslandConfig.decode(
            settings.getString(LyricPushConfig.KEY_XIAOMI_ISLAND_SETTINGS),
        ),
        colorOsEnabled = settings.bool(LyricPushConfig.KEY_COLOROS_ENABLED, false),
        colorOsMode = settings.enum(LyricPushConfig.KEY_COLOROS_MODE, OPlusLyricMode.SYSTEM),
        statusBarLyricEnabled = settings.bool(LyricPushConfig.KEY_STATUS_BAR_ENABLED, false),
        statusBarSecondary = settings.enum(
            LyricPushConfig.KEY_STATUS_BAR_SECONDARY,
            LyricSecondaryMode.OFF,
        ),
        headsUpLyricEnabled = settings.bool(LyricPushConfig.KEY_HEADS_UP_ENABLED, false),
        liveUpdateEnabled = settings.bool(LyricPushConfig.KEY_LIVE_UPDATE_ENABLED, false),
        liveUpdateContent = settings.enum(
            LyricPushConfig.KEY_LIVE_UPDATE_CONTENT,
            LyricContentMode.ORIGINAL,
        ),
        liveUpdateDisplay = settings.enum(
            LyricPushConfig.KEY_LIVE_UPDATE_DISPLAY,
            LiveUpdateDisplayMode.WORD_WINDOW,
        ),
        liveUpdateSecondary = settings.enum(
            LyricPushConfig.KEY_LIVE_UPDATE_SECONDARY,
            LiveUpdateSecondaryMode.SONG,
        ),
        mediaNotificationLyricEnabled = settings.bool(LyricPushConfig.KEY_MEDIA_NOTIFY_ENABLED, false),
        mediaNotificationSecondary = settings.enum(
            LyricPushConfig.KEY_MEDIA_NOTIFY_SECONDARY,
            LyricSecondaryMode.TRANSLATION,
        ),
    )

    fun write(settings: SettingsStorage, config: LyricPushConfig) {
        settings.putString(LyricPushConfig.KEY_LYRICON_ENABLED, config.lyriconEnabled.toString())
        settings.putString(LyricPushConfig.KEY_LYRICON_SECONDARY, config.lyriconSecondary.name)
        settings.putString(LyricPushConfig.KEY_SUPER_LYRIC_ENABLED, config.superLyricEnabled.toString())
        settings.putString(LyricPushConfig.KEY_SUPER_LYRIC_SECONDARY, config.superLyricSecondary.name)
        settings.putString(LyricPushConfig.KEY_LYRIC_GETTER_ENABLED, config.lyricGetterEnabled.toString())
        settings.putString(LyricPushConfig.KEY_XIAOMI_ISLAND_ENABLED, config.xiaomiSuperIslandEnabled.toString())
        settings.putString(
            LyricPushConfig.KEY_XIAOMI_ISLAND_SETTINGS,
            config.xiaomiSuperIsland.sanitized().encode(),
        )
        settings.putString(LyricPushConfig.KEY_COLOROS_ENABLED, config.colorOsEnabled.toString())
        settings.putString(LyricPushConfig.KEY_COLOROS_MODE, config.colorOsMode.name)
        settings.putString(LyricPushConfig.KEY_STATUS_BAR_ENABLED, config.statusBarLyricEnabled.toString())
        settings.putString(LyricPushConfig.KEY_STATUS_BAR_SECONDARY, config.statusBarSecondary.name)
        settings.putString(LyricPushConfig.KEY_HEADS_UP_ENABLED, config.headsUpLyricEnabled.toString())
        settings.putString(LyricPushConfig.KEY_LIVE_UPDATE_ENABLED, config.liveUpdateEnabled.toString())
        settings.putString(LyricPushConfig.KEY_LIVE_UPDATE_CONTENT, config.liveUpdateContent.name)
        settings.putString(LyricPushConfig.KEY_LIVE_UPDATE_DISPLAY, config.liveUpdateDisplay.name)
        settings.putString(LyricPushConfig.KEY_LIVE_UPDATE_SECONDARY, config.liveUpdateSecondary.name)
        settings.putString(LyricPushConfig.KEY_MEDIA_NOTIFY_ENABLED, config.mediaNotificationLyricEnabled.toString())
        settings.putString(LyricPushConfig.KEY_MEDIA_NOTIFY_SECONDARY, config.mediaNotificationSecondary.name)
    }
}

// ============ SettingsStorage 的小工具（只在本包内用） ============

private fun SettingsStorage.bool(key: String, default: Boolean): Boolean =
    getString(key)?.toBooleanStrictOrNull() ?: default

/**
 * 读枚举。存的是 `name`，但**非法值回退默认而不是抛异常** —— 枚举改名 / 删项后
 * 老配置里留着旧名字，抛异常会让整个设置页打不开。
 */
private inline fun <reified T : Enum<T>> SettingsStorage.enum(key: String, default: T): T =
    getString(key)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
