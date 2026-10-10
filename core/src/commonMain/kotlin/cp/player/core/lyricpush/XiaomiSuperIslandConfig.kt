/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/data/XiaomiSuperIslandSettings.kt
 * Changes: org.json.JSONObject 换成包内 LyricPushJson（commonMain 没有 org.json）；
 *          媒体控制按钮 / 分享卡片 / 通知样式仍删掉（需要 app-android 的 drawable 与
 *          分享链接构造，收益低于复杂度）；lyricTextMode 三档复用 LyricContentMode。
 *          **XMSF 断网隔离三档（关闭 / 标准 / 增强）与标准档时长已补回**（2026-10-10）：
 *          这一层依赖 Shizuku + 隐藏的 IConnectivityManager，现由 androidMain 的
 *          XmsfFirewall / XmsfIsolationController 实现，见 XIAOMI_SUPER_ISLAND_PORTING.md。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

/**
 * HyperOS 超级小岛的展示配置。
 *
 * 放在 commonMain 而不是 androidMain：它是**纯数据**，设置页（commonMain 的 Compose）
 * 要能直接编辑它，而真正发通知的 bridge 在 androidMain 收这份配置。
 *
 * 与 [LyricPushConfig] 分开存：总开关决定是否投放，这一份只决定「投成什么样」，
 * 两者改动频率差一个数量级（总开关偶尔动，样式可能反复调）。
 */
data class XiaomiSuperIslandConfig(
    /** 岛内主歌词取原文 / 翻译 / 注音。 */
    val content: LyricContentMode = LyricContentMode.ORIGINAL,
    /** 布局：标准（歌名 + 当前句）或完整（整句分左右两列）。 */
    val lyricMode: LyricMode = LyricMode.FULL,
    /** 完整布局下左侧是否保留专辑封面。 */
    val fullLyricShowLeftCover: Boolean = false,
    /** 逐字歌词是否跟随当前词滚动；关闭则固定显示整句。 */
    val scrollingEnabled: Boolean = true,
    val rightTextChars: Int = 7,
    val leftWithCoverTextChars: Int = 6,
    val leftWithoutCoverTextChars: Int = 8,
    /** 是否给岛内文字与进度条上色（否则用系统默认）。 */
    val textColorEnabled: Boolean = false,
    val colorSource: IslandColorSource = IslandColorSource.ALBUM,
    val customColor: Int = DEFAULT_CUSTOM_COLOR,
    /** 进度条是否也用强调色。 */
    val progressColorEnabled: Boolean = false,
    /** 暂停后延迟多久收起（毫秒）。 */
    val dismissDelayMs: Int = 0,
    /**
     * XMSF 断网隔离档位。
     *
     * 默认 [XmsfIsolationMode.STANDARD]：只有先临时切断 `com.xiaomi.xmsf` 的网络，
     * 焦点通知才不会被 XMSF 的联网校验拦掉（见 XIAOMI_SUPER_ISLAND_PORTING.md §0 / §5）。
     * **未授予 Shizuku 权限时该档位自动退化为「直接发送」**，与 [XmsfIsolationMode.OFF]
     * 等价 —— 所以默认开启是安全的：它只在用户主动授权后才真正生效。
     */
    val xmsfMode: XmsfIsolationMode = XmsfIsolationMode.STANDARD,
    /** [XmsfIsolationMode.STANDARD] 下，发完通知后保持阻断的时长（毫秒，100–500）。 */
    val xmsfBlockDurationMs: Int = DEFAULT_XMSF_BLOCK_MS,
) {
    /** 布局模式。 */
    enum class LyricMode { STANDARD, FULL }

    /** 强调色来源。 */
    enum class IslandColorSource { ALBUM, CUSTOM }

    /**
     * XMSF 断网隔离档位（三档，语义与 NeriPlayer / 移植指南 §5.4 一致）。
     *
     * | 档位 | 行为 | 定位 |
     * | --- | --- | --- |
     * | [OFF] | 不动 XMSF，直接发通知 | 联网时通常不显示，仅兜底 |
     * | [STANDARD] | 阻断 → 发通知 → 等 [xmsfBlockDurationMs] → 恢复 | 默认，系统压力最小 |
     * | [ENHANCED] | 播放期间持续阻断，暂停 / 关闭 / 切档时才恢复 | 联网稳定优先，副作用更大 |
     */
    enum class XmsfIsolationMode { OFF, STANDARD, ENHANCED }

    /**
     * 把越界值夹回合法区间。
     *
     * **所有读出来的配置都要过一遍**：这份 JSON 存在 `SettingsStorage` 里，用户手改、
     * 跨版本升级、以及未来删掉某个枚举项之后，旧值都可能落在区间外；夹值比抛异常好，
     * 因为一个坏值不该让整个歌词投放页打不开。
     */
    fun sanitized(): XiaomiSuperIslandConfig = copy(
        lyricMode = if (lyricMode == LyricMode.FULL) LyricMode.FULL else LyricMode.STANDARD,
        rightTextChars = rightTextChars.coerceIn(RIGHT_CHARS_RANGE),
        leftWithCoverTextChars = leftWithCoverTextChars.coerceIn(LEFT_WITH_COVER_RANGE),
        leftWithoutCoverTextChars = leftWithoutCoverTextChars.coerceIn(LEFT_WITHOUT_COVER_RANGE),
        customColor = customColor or (0xFF shl 24),
        dismissDelayMs = dismissDelayMs.takeIf { it in DISMISS_DELAYS_MS } ?: 0,
        // 吸附到最近的预设档而不是简单夹值：UI 用「第几段」映射预设，
        // 手改配置留下一个非预设值会让滑杆显示与实际值对不上。
        xmsfBlockDurationMs = XMSF_BLOCK_PRESETS_MS
            .minByOrNull { kotlin.math.abs(it - xmsfBlockDurationMs) }
            ?: DEFAULT_XMSF_BLOCK_MS,
    )

    fun encode(): String = LyricPushJson.buildObject(
        "content" to content.name,
        "mode" to lyricMode.name,
        "leftCover" to fullLyricShowLeftCover.toString(),
        "scroll" to scrollingEnabled.toString(),
        "rightChars" to rightTextChars.toString(),
        "leftCoverChars" to leftWithCoverTextChars.toString(),
        "leftChars" to leftWithoutCoverTextChars.toString(),
        "colorize" to textColorEnabled.toString(),
        "colorSource" to colorSource.name,
        "customColor" to customColor.toString(),
        "progressColor" to progressColorEnabled.toString(),
        "dismissDelay" to dismissDelayMs.toString(),
        "xmsfMode" to xmsfMode.name,
        "xmsfBlockMs" to xmsfBlockDurationMs.toString(),
    )

    companion object {
        /** 与上游 bridge 的默认强调色一致（HyperOS 岛内的蓝）。 */
        const val DEFAULT_CUSTOM_COLOR = -0x00CB7D01

        /**
         * 标准档默认阻断时长（毫秒）。移植指南 §5.4 给的区间是 100–500。
         *
         * ⚠️ 必须**本身就是** [XMSF_BLOCK_PRESETS_MS] 里的一档：`sanitized()` 会把任意值
         * 吸附到最近的预设，默认值若不在预设里，「默认配置往返」就会被改写成另一档。
         */
        const val DEFAULT_XMSF_BLOCK_MS = 200

        val RIGHT_CHARS_RANGE = 6..14
        val LEFT_WITH_COVER_RANGE = 4..10
        val LEFT_WITHOUT_COVER_RANGE = 6..14
        val DISMISS_DELAYS_MS = setOf(0, 1_000, 3_000, 5_000)

        /** 标准档阻断时长可选档（毫秒），与设置页的分段控件一一对应。 */
        val XMSF_BLOCK_PRESETS_MS = listOf(100, 200, 300, 500)

        fun decode(value: String?): XiaomiSuperIslandConfig {
            if (value.isNullOrBlank()) return XiaomiSuperIslandConfig()
            return runCatching {
                XiaomiSuperIslandConfig(
                    content = LyricPushJson.stringField(value, "content")
                        ?.let { runCatching { LyricContentMode.valueOf(it) }.getOrNull() }
                        ?: LyricContentMode.ORIGINAL,
                    lyricMode = if (LyricPushJson.stringField(value, "mode") == LyricMode.STANDARD.name) {
                        LyricMode.STANDARD
                    } else {
                        LyricMode.FULL
                    },
                    fullLyricShowLeftCover = LyricPushJson.boolField(value, "leftCover") ?: false,
                    scrollingEnabled = LyricPushJson.boolField(value, "scroll") ?: true,
                    rightTextChars = LyricPushJson.intField(value, "rightChars") ?: 7,
                    leftWithCoverTextChars = LyricPushJson.intField(value, "leftCoverChars") ?: 6,
                    leftWithoutCoverTextChars = LyricPushJson.intField(value, "leftChars") ?: 8,
                    textColorEnabled = LyricPushJson.boolField(value, "colorize") ?: false,
                    colorSource = if (LyricPushJson.stringField(value, "colorSource") == IslandColorSource.CUSTOM.name) {
                        IslandColorSource.CUSTOM
                    } else {
                        IslandColorSource.ALBUM
                    },
                    customColor = LyricPushJson.intField(value, "customColor") ?: DEFAULT_CUSTOM_COLOR,
                    progressColorEnabled = LyricPushJson.boolField(value, "progressColor") ?: false,
                    dismissDelayMs = LyricPushJson.intField(value, "dismissDelay") ?: 0,
                    xmsfMode = LyricPushJson.stringField(value, "xmsfMode")
                        ?.let { runCatching { XmsfIsolationMode.valueOf(it) }.getOrNull() }
                        ?: XmsfIsolationMode.STANDARD,
                    xmsfBlockDurationMs = LyricPushJson.intField(value, "xmsfBlockMs")
                        ?: DEFAULT_XMSF_BLOCK_MS,
                ).sanitized()
            }.getOrDefault(XiaomiSuperIslandConfig())
        }
    }
}
