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

    /** 读取开关；缺失或非法值回落到 [DEFAULT_KEEP_LAST_PLAYBACK]。 */
    fun keepLastPlayback(storage: SettingsStorage): Boolean =
        storage.getString(KEY_KEEP_LAST_PLAYBACK)?.toBooleanStrictOrNull()
            ?: DEFAULT_KEEP_LAST_PLAYBACK
}
