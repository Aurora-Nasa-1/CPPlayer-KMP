package cp.player.core.playback

import cp.player.core.util.SettingsStorage

/**
 * 「保留上次播放」的持久化契约。
 *
 * ### 这是什么
 * 一个用户开关：打开后，应用启动时把**上次的播放队列与当前曲目**恢复出来
 * （停在原进度、**不自动播放**），等用户手动点播放才从该进度起播。
 *
 * ### 为什么把键放这里而不是散在两边
 * 写这个开关的是前端（设置页 → [cp.player.app.AppModel]），读它并落盘快照的是
 * 后端（[PlaybackControllerImpl]）。键与默认值只写一份、两边共用同一个常量，
 * 否则「设置页写的键」和「播放内核读的键」一旦拼错就会**静默失效**：
 * 开关看着能点、能持久化，播放内核却永远读到 null 而按默认值走。
 *
 * 与 [LyricsSourceMode.SETTINGS_KEY] 是同一套做法。
 */
object PlaybackSessionSettings {

    /**
     * 开关键。
     *
     * 值语义：`"true"` / `"false"`（[Boolean.toString]）。缺失时按
     * [DEFAULT_KEEP_LAST_PLAYBACK] 处理。
     */
    const val KEY_KEEP_LAST_PLAYBACK = "playback_keep_last"

    /**
     * 上次会话快照键。
     *
     * 内容是 [PlaybackControllerImpl] 自己编码的 JSON（队列 mediaId 列表 + 当前下标 +
     * 来源 id + 进度毫秒）。**不要**在别处解析它 —— 形状由写它的一方唯一决定。
     */
    const val KEY_LAST_SESSION = "playback_last_session"

    /**
     * 开关默认值：**开**。
     *
     * 「记忆播放」是绝大多数播放器的默认行为，用户对它的预期就是「打开应用还在上次那首」。
     * 关掉它才需要显式操作。
     */
    const val DEFAULT_KEEP_LAST_PLAYBACK = true

    /**
     * 播放引擎选择键。
     *
     * 值：[ENGINE_STANDARD] (标准) 或 [ENGINE_FLICK_HIRES] (发烧级专有引擎)。
     *
     * ⚠️ **桌面端不读这个键**：桌面只有 FlickEngine 一个后端（原生库缺失时直接
     * 无输出，不存在「换回系统引擎」这条路），真正的选择是
     * [KEY_WASAPI_EXCLUSIVE]（共享 / 独占）。这个键只在 Android 上决定
     * ExoPlayer 与 FlickEngine 之间切谁。
     */
    const val KEY_AUDIO_ENGINE = "playback_audio_engine"
    const val ENGINE_STANDARD = "standard"
    const val ENGINE_FLICK_HIRES = "flick_hires"

    /**
     * WASAPI 硬件独占（Bit-Perfect 直通）开关键，**仅 Windows 桌面生效**。
     *
     * 值语义：`"true"` / `"false"`。缺失时按 [DEFAULT_WASAPI_EXCLUSIVE] 处理。
     *
     * ### 为什么必须是独立的一个键（而不是拿 [KEY_AUDIO_ENGINE] 兼职）
     * 「用哪个内核」与「是否抢走声卡」是两件事：桌面内核没得选（只有 FlickEngine），
     * 独占才是那个需要用户点头的决定。旧实现把两者合并在一个键里，
     * 结果是「选了发烧内核 = 默认独占」，VIP 用户还没看过任何说明就被抢走了声卡。
     *
     * ### 为什么默认关
     * 独占期间**其它应用全部静音**（系统提示音、网页、会议软件），这是能让人错过
     * 会议邀请的副作用，不能由程序替用户默认决定。默认共享、想要时再开，
     * 是这件事唯一站得住的默认值。
     */
    const val KEY_WASAPI_EXCLUSIVE = "playback_wasapi_exclusive"

    /** 独占模式默认值：**关**（理由见 [KEY_WASAPI_EXCLUSIVE]）。 */
    const val DEFAULT_WASAPI_EXCLUSIVE = false

    /** 读取 WASAPI 独占开关；缺失或非法值回落到 [DEFAULT_WASAPI_EXCLUSIVE]。 */
    fun wasapiExclusive(storage: SettingsStorage): Boolean =
        storage.getString(KEY_WASAPI_EXCLUSIVE)?.toBooleanStrictOrNull()
            ?: DEFAULT_WASAPI_EXCLUSIVE

    /** 读取开关；缺失或非法值回落到 [DEFAULT_KEEP_LAST_PLAYBACK]。 */
    fun keepLastPlayback(storage: SettingsStorage): Boolean =
        storage.getString(KEY_KEEP_LAST_PLAYBACK)?.toBooleanStrictOrNull()
            ?: DEFAULT_KEEP_LAST_PLAYBACK

    /**
     * 下一首歌曲预加载开关键。
     *
     * 值语义：`"true"` / `"false"`。缺失时按 [DEFAULT_PRELOAD_NEXT] 处理。
     */
    const val KEY_PRELOAD_NEXT = "playback_preload_next"

    /** 开关默认值：**开**。 */
    const val DEFAULT_PRELOAD_NEXT = true

    /** 读取下一首预加载开关；缺失或非法值回落到 [DEFAULT_PRELOAD_NEXT]。 */
    fun preloadNextEnabled(storage: SettingsStorage): Boolean =
        storage.getString(KEY_PRELOAD_NEXT)?.toBooleanStrictOrNull()
            ?: DEFAULT_PRELOAD_NEXT

    // ============ 打开软件自动播放 ============

    /**
     * 自动播放模式开关键。
     *
     * 值见 [AutoPlayMode.storageKey]。缺失时默认 [DEFAULT_AUTO_PLAY_MODE] (关闭)。
     */
    const val KEY_AUTO_PLAY_MODE = "playback_auto_play_mode"

    const val AUTO_PLAY_MODE_OFF = "off"
    const val AUTO_PLAY_MODE_CONTINUE = "continue"
    const val AUTO_PLAY_MODE_DAILY = "daily"
    const val AUTO_PLAY_MODE_CUSTOM = "custom"

    val DEFAULT_AUTO_PLAY_MODE = AutoPlayMode.OFF

    /** 读取打开软件自动播放模式。 */
    fun autoPlayMode(storage: SettingsStorage): AutoPlayMode =
        AutoPlayMode.of(storage.getString(KEY_AUTO_PLAY_MODE))

    /**
     * 自动播放指定目标类型键。
     *
     * 值见 [AutoPlayCustomType.storageKey]。默认 [DEFAULT_AUTO_PLAY_CUSTOM_TYPE] (歌单)。
     */
    const val KEY_AUTO_PLAY_CUSTOM_TYPE = "playback_auto_play_custom_type"

    const val AUTO_PLAY_CUSTOM_TYPE_PLAYLIST = "playlist"
    const val AUTO_PLAY_CUSTOM_TYPE_TRACK = "track"

    val DEFAULT_AUTO_PLAY_CUSTOM_TYPE = AutoPlayCustomType.PLAYLIST

    /** 读取自动播放指定目标类型。 */
    fun autoPlayCustomType(storage: SettingsStorage): AutoPlayCustomType =
        AutoPlayCustomType.of(storage.getString(KEY_AUTO_PLAY_CUSTOM_TYPE))

    /** 自动播放指定目标 ID 键（歌单 resourceId 或单曲 ID）。 */
    const val KEY_AUTO_PLAY_CUSTOM_ID = "playback_auto_play_custom_id"

    /** 读取自动播放指定目标 ID。 */
    fun autoPlayCustomId(storage: SettingsStorage): String =
        storage.getString(KEY_AUTO_PLAY_CUSTOM_ID).orEmpty()

    /** 自动播放指定目标展示名称键。 */
    const val KEY_AUTO_PLAY_CUSTOM_TITLE = "playback_auto_play_custom_title"

    /** 读取自动播放指定目标展示名称。 */
    fun autoPlayCustomTitle(storage: SettingsStorage): String =
        storage.getString(KEY_AUTO_PLAY_CUSTOM_TITLE).orEmpty()
}

/**
 * 打开软件自动播放模式。
 */
enum class AutoPlayMode(val storageKey: String) {
    /** 关闭自动播放（默认）。 */
    OFF(PlaybackSessionSettings.AUTO_PLAY_MODE_OFF),
    /** 继续上次播放。 */
    CONTINUE(PlaybackSessionSettings.AUTO_PLAY_MODE_CONTINUE),
    /** 每日推荐。 */
    DAILY(PlaybackSessionSettings.AUTO_PLAY_MODE_DAILY),
    /** 指定歌单或单曲。 */
    CUSTOM(PlaybackSessionSettings.AUTO_PLAY_MODE_CUSTOM);

    companion object {
        fun of(key: String?): AutoPlayMode =
            entries.firstOrNull { it.storageKey == key } ?: OFF
    }
}

/**
 * 自动播放指定目标的类型：歌单或单曲。
 */
enum class AutoPlayCustomType(val storageKey: String) {
    /** 歌单。 */
    PLAYLIST(PlaybackSessionSettings.AUTO_PLAY_CUSTOM_TYPE_PLAYLIST),
    /** 单曲。 */
    TRACK(PlaybackSessionSettings.AUTO_PLAY_CUSTOM_TYPE_TRACK);

    companion object {
        fun of(key: String?): AutoPlayCustomType =
            entries.firstOrNull { it.storageKey == key } ?: PLAYLIST
    }
}

