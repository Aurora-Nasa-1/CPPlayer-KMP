/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/data/XiaomiSuperIslandSettings.kt
 * Changes: org.json.JSONObject 换成包内 LyricPushJson（commonMain 没有 org.json）；
 *          **删掉 XMSF 断网旁路三档与自定义时长**（它们依赖 Shizuku + 隐藏的
 *          IConnectivityManager，本仓库不引入），只保留「直接发送」一条路径；
 *          删掉媒体控制按钮 / 分享卡片 / 通知样式（需要 app-android 的 drawable 与
 *          分享链接构造，收益低于复杂度）；lyricTextMode 三档复用 LyricContentMode。
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
) {
    /** 布局模式。 */
    enum class LyricMode { STANDARD, FULL }

    /** 强调色来源。 */
    enum class IslandColorSource { ALBUM, CUSTOM }

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
    )

    companion object {
        /** 与上游 bridge 的默认强调色一致（HyperOS 岛内的蓝）。 */
        const val DEFAULT_CUSTOM_COLOR = -0x00CB7D01

        val RIGHT_CHARS_RANGE = 6..14
        val LEFT_WITH_COVER_RANGE = 4..10
        val LEFT_WITHOUT_COVER_RANGE = 6..14
        val DISMISS_DELAYS_MS = setOf(0, 1_000, 3_000, 5_000)

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
                ).sanitized()
            }.getOrDefault(XiaomiSuperIslandConfig())
        }
    }
}
