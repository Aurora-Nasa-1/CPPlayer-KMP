package cp.player.core.playback

import cp.player.core.util.SettingsStorage

/**
 * 淡入淡出（fade in / out）配置。
 *
 * ### 是什么
 *
 * **只改音量**的过渡：切歌时从 0 淡入到目标音量、曲末从目标音量淡出到 0。
 * 它**不是**交叉淡入淡出（crossfade）—— 那需要两首歌同时出声、即两个解码实例并存，
 * 本仓的 [PlatformPlayer] 是单实例，桌面端底层 DLL 也没有第二个输出流的能力。
 * 所以这里做的是「单播放器淡入淡出」，参考实现里叫 `Single Fade` 的那一档。
 *
 * ### 为什么时长是单一值而不是分「淡入 / 淡出」两个
 *
 * 用户心智里「淡入淡出」就是一个旋钮（参考实现的设置页也是一条滑杆）。
 * 拆成两个值会让设置页出现两个语义相近、数值又必须大致相等的滑杆，
 * 用户调半天发现「怎么切歌的过渡和曲末的不一样」——那是 bug 观感，不是功能。
 *
 * @param enabled 总开关。默认**关**（见 [FadeSettings.DEFAULT_ENABLED]）。
 * @param durationMs 单程时长（毫秒），淡入与淡出各自都用这个值。
 *   取值 [MIN_DURATION_MS] ~ [MAX_DURATION_MS]。
 * @param fadeIn 是否在**开始播放**时淡入。
 *   与 [fadeOut] 拆开是因为这两个的适用场景不同：单曲循环重播时，
 *   用户往往只想要「曲末别硬切」而不想每次重播都从静音爬上来。
 * @param fadeOut 是否在**曲末 / 切歌离开**时淡出。
 */
data class FadeConfig(
    val enabled: Boolean = false,
    val durationMs: Int = DEFAULT_DURATION_MS,
    val fadeIn: Boolean = true,
    val fadeOut: Boolean = true,
) {
    /** 时长是否落在合法区间（非法值一律由 [FadeSettings.read] 钳回区间）。 */
    val durationMsClamped: Int
        get() = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

    companion object {
        /** 时长范围（毫秒）。
         *
         * 下限 500ms：再短就和「硬切」听不出区别，等于给了个坏控件。
         * 上限 10s：参考实现（`cp-player-legacy`）的滑杆就是 0~10s，
         * 超过 10 秒的淡出会让用户以为播放器卡住了。 */
        const val MIN_DURATION_MS = 500
        const val MAX_DURATION_MS = 10_000

        /** 默认时长：3 秒。参考实现默认 2s，但那是交叉淡入淡出的值；
         * 单播放器淡出时整首会「越听越小」，稍长一点过渡反而更自然。 */
        const val DEFAULT_DURATION_MS = 3_000

        /** 时长滑杆步进（毫秒）：500ms 一档，10 档正好铺满区间。 */
        const val DURATION_STEP_MS = 500

        /** 关闭时的直通配置。 */
        val OFF = FadeConfig()
    }
}

/**
 * 淡入淡出的持久化契约。
 *
 * 与 [AudioEffectSettings] / [PlaybackSessionSettings] 同一套做法：键名、默认值、
 * 编码格式只写一份，前端（设置页 → `AppModel`）写、后端（[PlaybackControllerImpl]）读。
 * 分开两处写会让「设置页写的键」和「播放内核读的键」拼错时**静默失效**：
 * 开关看着能点、能持久化，播放内核却永远读到 null 而按默认走。
 */
object FadeSettings {

    /** 总开关。值：`"true"` / `"false"`。 */
    const val KEY_ENABLED = "fade_enabled"

    /** 单程时长（毫秒）。值：整数字符串，如 `"3000"`。 */
    const val KEY_DURATION_MS = "fade_duration_ms"

    /** 淡入开关。值：`"true"` / `"false"`。 */
    const val KEY_FADE_IN = "fade_in"

    /** 淡出开关。值：`"true"` / `"false"`。 */
    const val KEY_FADE_OUT = "fade_out"

    // -----------------------------------------------------------------------
    // 默认值
    // -----------------------------------------------------------------------

    /**
     * 总开关默认值：**关**。
     *
     * 与音效同一个理由：淡入淡出会改动「听到的音频」——曲末最后几秒会被压低，
     * 这**不是**原始录音的样子。默认开启等于替用户做了取舍，
     * 而且是在他毫不知情的情况下（用户会以为是音源本身的问题）。
     * 与 [AudioEffectSettings.DEFAULT_ENABLED] 保持一致的判断标准。
     */
    const val DEFAULT_ENABLED = false

    const val DEFAULT_DURATION_MS = FadeConfig.DEFAULT_DURATION_MS

    /** 淡入 / 淡出子开关默认**开**：总开关一打开就该看到完整效果，
     * 而不是「开关开了但什么都没发生」（两个子开关都得再手动勾一遍）。 */
    const val DEFAULT_FADE_IN = true
    const val DEFAULT_FADE_OUT = true

    // -----------------------------------------------------------------------
    // 读 / 写
    // -----------------------------------------------------------------------

    /**
     * 从存储读回淡入淡出配置。
     *
     * 任何一项缺失或非法都**单独回落到该项默认值** —— 用户手改过配置文件时，
     * 其余设置不该被一起清空。
     */
    fun read(storage: SettingsStorage): FadeConfig {
        val duration = storage.getString(KEY_DURATION_MS)
            ?.toIntOrNull()
            ?.coerceIn(FadeConfig.MIN_DURATION_MS, FadeConfig.MAX_DURATION_MS)
            ?: DEFAULT_DURATION_MS
        return FadeConfig(
            enabled = storage.boolean(KEY_ENABLED, DEFAULT_ENABLED),
            durationMs = duration,
            fadeIn = storage.boolean(KEY_FADE_IN, DEFAULT_FADE_IN),
            fadeOut = storage.boolean(KEY_FADE_OUT, DEFAULT_FADE_OUT),
        )
    }

    /** 全量写回（理由同 [AudioEffectSettings.write]）。 */
    fun write(storage: SettingsStorage, config: FadeConfig) {
        storage.putString(KEY_ENABLED, config.enabled.toString())
        storage.putString(KEY_DURATION_MS, config.durationMsClamped.toString())
        storage.putString(KEY_FADE_IN, config.fadeIn.toString())
        storage.putString(KEY_FADE_OUT, config.fadeOut.toString())
    }

    private fun SettingsStorage.boolean(key: String, default: Boolean): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default
}
