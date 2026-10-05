package cp.player.app.i18n

/**
 * 应用显示语言。
 *
 * ### 三种取值不是「三种语言」
 *
 * [SYSTEM] 不是第三种语言，而是「跟随系统」：**运行时**由
 * [CpStrings.Companion.of] 读 Compose 的 `Locale.current` 决定。
 * 不命中英文时一律中文 —— **中文是本应用的兜底语言**。
 *
 * 之所以选中文兜底而不是英文：当前用户群以中文为主，将来某种口音 / 某种语言缺少翻译时，
 * 回落到用户看得懂的语言比回落到另一种外语更接近可用。
 * 要改兜底语言，只需改 `CpStrings.of` 里那一个 `else` 分支。
 *
 * @param storageKey 持久化值。刻意用稳定字符串而不是枚举 [name]（见 [ofStorageKey]）
 * @param tag BCP-47 语言标签；`null` = 跟随系统。**目前只用于持久化与调试输出**，
 *   文案取值不经过它 —— 走 [CpStrings.Companion.of] 请 CompositionLocal 那条链，
 *   别在这里和第 2 份解析逻辑重复造轮子
 */
enum class AppLanguage(
    val storageKey: String,
    val tag: String?,
) {
    /** 跟随系统语言。 */
    SYSTEM("system", null),

    /** 简体中文（强制，不随系统）。 */
    ZH_HANS("zh-Hans", "zh"),

    /** English (forced, independent of system locale). */
    ENGLISH("en", "en"),
    ;

    companion object {
        /**
         * 从持久化值还原。**无法识别时回落到 [SYSTEM]** —— 宁可在语言上回到「跟随系统」，
         * 也不要在反序列化这里抛异常，把一个偏好项变成 crash 源。
         *
         * 匹配 [storageKey] 而不是枚举名：后者一旦有人重命名枚举成员，老用户的持久化值
         * 就静默失效并被重置（用户会发现「我明明选了英文」）。存储键与枚举名解耦后，
         * 改名是无害的。
         */
        fun ofStorageKey(key: String?): AppLanguage =
            entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

/**
 * 语言选项的显示名。
 *
 * ⚠️ 刻意放在这里而不是 [LanguageStrings] 里加 `systemTitle` / `zhHansTitle` / `englishTitle`
 * 三个成员：那是把「枚举有几个取值」和「每个取值叫什么」拆到两处维护，
 * 加一种语言就要改两个地方。集中在这儿，`AppLanguage.entries` 遍历即列表。
 */
fun AppLanguage.displayName(strings: CpStrings): String = when (this) {
    AppLanguage.SYSTEM -> strings.language.optionSystem
    AppLanguage.ZH_HANS -> strings.language.optionZhHans
    AppLanguage.ENGLISH -> strings.language.optionEnglish
}

/**
 * 语言选项的补充说明；`null` = 不显示。
 *
 * 只有 [AppLanguage.SYSTEM] 需要说明「系统语言不受支持时会回落到什么」——
 * 两个具体语言本身就自解释，再加一行副标题是噪声。
 */
fun AppLanguage.displayNote(strings: CpStrings): String? = when (this) {
    AppLanguage.SYSTEM -> strings.language.optionSystemNote
    else -> null
}
