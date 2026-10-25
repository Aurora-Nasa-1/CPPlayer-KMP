package cp.player.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily

/**
 * 应用使用的三款字体及其优先级 —— **全仓唯一的字体声明处**。
 *
 * 顺序即优先级（[priority]）：**Google Sans Flex > Noto Sans JP > MiSans**。
 * 但这里的「优先级」不是 CSS 那种「某个字形缺了才往下找」的字体栈：
 *
 * ⚠️ **Compose 没有按字形回退的字体栈**（`javap` 核实：`FontListFontFamilyTypefaceAdapter.resolve`
 * 按字重 / 斜体在家族内挑**一个** `Font` 返回，`TypefaceRequest` 里根本没有文本；
 * 桌面端 `FontSynthesis_desktopKt.synthesizeTypeface` 更是直接 `areturn` 原样返回）。
 * 也就是说：一个 `TextStyle` 只能落到一个 typeface，缺失的字形交给**平台**回退链
 * （Windows 是雅黑 / mac 是苹方 / Android 是 Noto Sans CJK）——那不是我们能声明的。
 *
 * 所以这里的「优先级」是**按字符脚本路由**：见 [appScript] 与 [buildAppStyledText]。
 * 一段文本按脚本切成若干个 run，每个 run 套上对应的字体家族，效果等价于 CSS 里的
 * `font-family: "Google Sans Flex", "Noto Sans JP", "MiSans"`，
 * 区别只是「路由依据」由字形覆盖变成脚本分类 —— 而且更可控：
 * 中日共用码位的汉字（`直` / `骨` / `次`）不会因为字体里谁先有那个码位就变成对方的写法。
 *
 * @param label 字体名（诊断输出 / 设置页展示用）
 * @param covers 它负责哪些字符（写在这里是为了「换了字体先看这张表」）
 * @param bundledOnDesktop 桌面端是否随安装包内置；Android 端由用户下载（见 `FontPacks`）
 */
enum class AppFontFace(
    val label: String,
    val covers: String,
    val bundledOnDesktop: Boolean,
) {
    /** 拉丁 / 数字 / 符号。应用默认字体，同时承担所有「不特殊处理」的字符。 */
    GOOGLE_SANS_FLEX(
        label = "Google Sans Flex",
        covers = "拉丁字母 · 数字 · 希腊 / 西里尔 · 通用符号",
        bundledOnDesktop = true,
    ),

    /** 日文。见 `Font.kt` 里「为什么必须自带而不是靠系统回退」。 */
    NOTO_SANS_JP(
        label = "Noto Sans JP",
        covers = "日文：假名 + 同码位汉字的日式写法",
        bundledOnDesktop = true,
    ),

    /** 中文与其余文字。见 `Font.kt` 里 MiSans VF 的说明。 */
    MISANS(
        label = "MiSans",
        covers = "中文 · 全角标点 · 大部分非拉丁文字",
        bundledOnDesktop = true,
    ),
    ;

    companion object {
        /**
         * 优先级声明：**Google Sans Flex > Noto Sans JP > MiSans**。
         *
         * 别在别处再抄一份顺序 —— 路由逻辑（[buildAppStyledText]）与设置页展示都读它。
         */
        val priority: List<AppFontFace> = listOf(GOOGLE_SANS_FLEX, NOTO_SANS_JP, MISANS)
    }
}

/**
 * 字符所属的**脚本分组**。分组决定它落到哪一款字体上（[AppFontFace]）。
 *
 * 分组刻意粗：真正的逐字形覆盖判断要读字体 cmap（几万个码位），
 * 而且中日共用码位的汉字本来就分不出「这是中文还是日文」——
 * 那种事只能靠**整段文本**的语言判定（见 [containsKana]）。
 */
enum class TextScript {
    /** 拉丁 / 数字 / 通用符号 —— 走默认字体（Google Sans Flex）。 */
    LATIN,

    /** 假名 —— 走日文字体。 */
    JAPANESE,

    /** 汉字 / 全角标点 / 全角字母数字 —— 走「本段文本所属语言」的 CJK 字体。 */
    CJK,

    /** 其余文字（韩文 / 泰文 / 阿拉伯文 …）—— 按声明「大部分语言」走 MiSans。 */
    OTHER,
}

/** 假名（含小假名、长音符、半角片假名）。 */
internal fun Char.isKana(): Boolean =
    this in '\u3040'..'\u309F' || this in '\u30A0'..'\u30FF' ||
        this in '\u31F0'..'\u31FF' || this in '\uFF65'..'\uFF9F'

/** 汉字（含兼容汉字与 `々` `〇`）。 */
internal fun Char.isHan(): Boolean =
    this in '\u4E00'..'\u9FFF' || this in '\u3400'..'\u4DBF' ||
        this in '\uF900'..'\uFAFF' || this == '\u3005' || this == '\u3007'

/** CJK 标点与全角字符（`、。「」（）` 与全角字母数字）。 */
internal fun Char.isCjkPunctuation(): Boolean =
    this in '\u3000'..'\u303F' || this in '\uFE10'..'\uFE19' ||
        this in '\uFE30'..'\uFE4F' || this in '\uFF00'..'\uFF64'

/** `a-z A-Z 0-9` 与拉丁常见标点、常见符号区 —— 显式列出，避免 `isLetter()` 把西里尔也算进来。 */
private fun Char.isLatinish(): Boolean =
    this.code < 0x0250 || this in '\u0370'..'\u03FF' || this in '\u0400'..'\u04FF' ||
        this in '\u2000'..'\u206F' || this in '\u20A0'..'\u20CF' || this in '\u2100'..'\u27BF'

/** 这段字符属于哪个脚本分组。 */
fun Char.appScript(): TextScript = when {
    isKana() -> TextScript.JAPANESE
    isHan() || isCjkPunctuation() -> TextScript.CJK
    isLatinish() -> TextScript.LATIN
    // 代理对（emoji / 扩展区汉字）落到这里：交给 MiSans，缺字形时平台回退到系统字体。
    else -> TextScript.OTHER
}

/** 这段文本里有没有汉字（用于「整段是不是中文」的判定，见 `JapaneseText.kt`）。 */
fun String.containsHan(): Boolean = any { it.isHan() }

/**
 * 主题装配好的「非默认字体」，即除 Google Sans Flex 之外的两款。
 *
 * null 表示这一款当前**不可用**（Android 还没下载 / 加载失败）。此时该脚本的字符
 * 不会被强行换字体，而是保持原来的平台回退行为 —— 「宁可回到系统字体，也不要豆腐块」。
 */
class AppFontFamilies(
    val japanese: FontFamily? = null,
    val misans: FontFamily? = null,
) {
    /** 两款都没有时，任何文本都不需要切 run（快路径）。 */
    val isEmpty: Boolean get() = japanese == null && misans == null

    companion object {
        val Empty = AppFontFamilies()
    }
}

/**
 * 当前主题提供的可选字体。
 *
 * 走 CompositionLocal 而不是让调用点自己读文件：字体家族的构建在 Android 上是
 * **按字重建 Typeface**（含磁盘 IO），在桌面端是**读 classpath 解字体**，
 * 逐处新建会把整棵树的 `TextStyle` 身份搅乱（同 `JapaneseText.kt` 的原注释）。
 */
val LocalAppFontFamilies = staticCompositionLocalOf { AppFontFamilies.Empty }

/**
 * 按字体声明把 [text] 切成若干 run，逐段套上对应字体。
 *
 * - 拉丁 / 数字 / 符号 → 不套（沿用样式的默认字体，即 Google Sans Flex）
 * - 假名 → 日文字体
 * - 汉字 / 全角标点 → 「本段语言的 CJK 字体」：含假名时是日文字体，否则是 MiSans
 * - 其余文字 → MiSans
 *
 * ⚠️ **整段语言判定只做一次**（看整段有没有假名），不逐句判断：
 * 「这是一段日文」是段落级属性；逐句判断会让同一行里的汉字一会儿日式一会儿中式。
 *
 * ⚠️ 没有可切换的字体时**原样返回**（`AnnotatedString(text)`，零 span 开销）——
 * 这条路径覆盖「安卓还没下载字体」「离屏测试没装主题」两种常见情况。
 */
fun buildAppStyledText(text: String, families: AppFontFamilies): AnnotatedString {
    if (families.isEmpty || text.isEmpty()) return AnnotatedString(text)

    val cjkFace = cjkFaceFor(text, families) ?: return AnnotatedString(text)
    return buildAnnotatedString {
        appendAppFontRuns(
            text = text,
            cjkFace = cjkFace,
            cjkFamily = if (cjkFace == AppFontFace.NOTO_SANS_JP) families.japanese else families.misans,
            otherFamily = families.misans,
        )
    }
}

/**
 * 把已有的 [annotated]（可能带颜色、下划线等样式）保留原有 span 并叠加字体路由。
 */
fun buildAppStyledText(annotated: AnnotatedString, families: AppFontFamilies): AnnotatedString {
    if (families.isEmpty || annotated.isEmpty()) return annotated
    val raw = annotated.text
    val cjkFace = cjkFaceFor(raw, families) ?: return annotated
    val cjkFamily = if (cjkFace == AppFontFace.NOTO_SANS_JP) families.japanese else families.misans
    val otherFamily = families.misans

    val builder = AnnotatedString.Builder(annotated)
    var runStart = 0
    var runFace: AppFontFace? = null
    for (index in raw.indices) {
        val face = when (raw[index].appScript()) {
            TextScript.LATIN -> null
            TextScript.JAPANESE -> AppFontFace.NOTO_SANS_JP
            TextScript.CJK -> cjkFace
            TextScript.OTHER -> AppFontFace.MISANS
        }
        if (index == 0) {
            runFace = face
            continue
        }
        if (face != runFace) {
            val fam = when (runFace) {
                null -> null
                AppFontFace.MISANS -> otherFamily
                AppFontFace.NOTO_SANS_JP -> cjkFamily
                AppFontFace.GOOGLE_SANS_FLEX -> null
            }
            if (fam != null) {
                builder.addStyle(SpanStyle(fontFamily = fam), runStart, index)
            }
            runStart = index
            runFace = face
        }
    }
    val fam = when (runFace) {
        null -> null
        AppFontFace.MISANS -> otherFamily
        AppFontFace.NOTO_SANS_JP -> cjkFamily
        AppFontFace.GOOGLE_SANS_FLEX -> null
    }
    if (fam != null) {
        builder.addStyle(SpanStyle(fontFamily = fam), runStart, raw.length)
    }
    return builder.toAnnotatedString()
}

/**
 * 这段文本的 **CJK 字体身份**（汉字 / 全角标点落到哪一款）。
 *
 * - 含假名 ⇒ [AppFontFace.NOTO_SANS_JP]（日文的汉字要用日式写法）
 * - 其余 ⇒ [AppFontFace.MISANS]（中文 / 未知语言，汉字用大陆写法）
 * - **返回 null** ⇒ 本段不该切换字体：含假名但日文字体不可用（Android 没下载）时，
 *   保持平台回退（系统按 locale 挑 CJK 字体），而不是拿 MiSans 的汉字写法去凑日文。
 */
internal fun cjkFaceFor(text: String, families: AppFontFamilies): AppFontFace? {
    if (families.isEmpty) return null
    if (!text.any { it.isKana() }) return AppFontFace.MISANS
    return if (families.japanese != null) AppFontFace.NOTO_SANS_JP else null
}

/**
 * 把 [text] 追加到当前 builder，并给每一段套上对应字体。
 *
 * 供已经有 `AnnotatedString`（带注解的歌手串、带点击区间的行）的场景复用：
 * 它们不能整体替换成 [buildAppStyledText] 的结果，只能逐段 append。
 *
 * @param cjkFace 汉字 / 全角标点用的字体身份（由调用方按整段语言先行判定：
 *   含假名 ⇒ [AppFontFace.NOTO_SANS_JP]，否则 ⇒ [AppFontFace.MISANS]）
 * @param cjkFamily 与 [cjkFace] 对应的家族
 * @param otherFamily 其余非拉丁文字用的家族
 */
internal fun AnnotatedString.Builder.appendAppFontRuns(
    text: String,
    cjkFace: AppFontFace,
    cjkFamily: FontFamily?,
    otherFamily: FontFamily?,
) {
    if (text.isEmpty()) return
    var runStart = 0
    var runFace: AppFontFace? = null
    for (index in text.indices) {
        val face = when (text[index].appScript()) {
            TextScript.LATIN -> null
            TextScript.JAPANESE -> AppFontFace.NOTO_SANS_JP
            TextScript.CJK -> cjkFace
            TextScript.OTHER -> AppFontFace.MISANS
        }
        if (index == 0) {
            runFace = face
            continue
        }
        if (face != runFace) {
            appendRun(text, runStart, index, runFace, cjkFamily, otherFamily)
            runStart = index
            runFace = face
        }
    }
    appendRun(text, runStart, text.length, runFace, cjkFamily, otherFamily)
}

/** 追加 [start, end) 这一段，并按 [face] 套字体；[face] 为 null 时不套（沿用默认字体）。 */
private fun AnnotatedString.Builder.appendRun(
    text: String,
    start: Int,
    end: Int,
    face: AppFontFace?,
    cjkFamily: FontFamily?,
    otherFamily: FontFamily?,
) {
    if (start >= end) return
    val from = length
    append(text, start, end)
    val family = when (face) {
        null -> null
        AppFontFace.MISANS -> otherFamily
        // 日文字体缺席时，汉字段就不套 —— 保持平台回退（见 buildAppStyledText 的注释）。
        AppFontFace.NOTO_SANS_JP -> cjkFamily
        AppFontFace.GOOGLE_SANS_FLEX -> null
    } ?: return
    addStyle(SpanStyle(fontFamily = family), from, length)
}
