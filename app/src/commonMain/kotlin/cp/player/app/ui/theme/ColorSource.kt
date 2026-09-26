package cp.player.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 种子色来源。
 *
 * 取代原先的 `dynamic_color` 布尔开关 —— 拆成「来源」而不是「是否动态」，是因为
 * 桌面端没有壁纸 Monet，但它同样需要一个动态来源（Windows 系统强调色 / 歌曲封面）。
 * 两者的差别只是**种子从哪来**；「种子 → 完整 M3 方案」这一段两端共用 materialkolor。
 */
enum class ColorSource {
    /**
     * 跟随系统。
     * - Android 12+：壁纸 Monet，走平台原生 `dynamicLight/DarkColorScheme`
     * - 桌面：Windows 的 DWM 强调色，作为种子交给 materialkolor
     * - 取不到时回退到 [DefaultSeedColor]
     */
    PLATFORM,

    /**
     * 跟随当前曲目的封面主色。
     *
     * 没有曲目封面时（未播放 / 该曲目无封面）回退到**系统壁纸**：
     * Android 12+ 走壁纸 Monet，桌面取壁纸图主色；两者都取不到才回退到 [DefaultSeedColor]。
     */
    COVER,

    /** 内置固定种子色 [DefaultSeedColor]。 */
    FIXED,
}

/**
 * [ColorSource.FIXED] 的种子色，也是另外两种来源取不到值时的最终回退。
 *
 * 刻意复用静态回退色板的蓝紫主色：这样「主题取不到色」与「根本没有主题功能」
 * 在观感上是一致的，不会出现「同一个 App 两套默认配色」。
 */
val DefaultSeedColor: Color = PrimaryLight

/** 设置页用的短标签。放这里而不是 UI 层，避免两个设置页各写一份文案。 */
fun ColorSource.displayName(): String = when (this) {
    ColorSource.PLATFORM -> "系统"
    ColorSource.COVER -> "封面"
    ColorSource.FIXED -> "默认"
}

/**
 * 来源说明文案。
 *
 * 刻意写明**回退行为**：这三个来源都可能取不到色（平台不支持 / 无封面），
 * 用户看到配色变了却不知道为什么，是最容易误报成 bug 的一类反馈。
 */
fun ColorSource.description(platformAvailable: Boolean): String = when (this) {
    ColorSource.PLATFORM ->
        if (platformAvailable) "取自系统强调色（安卓壁纸 / Windows 强调色）"
        else "当前平台不支持，将回退到默认配色"
    ColorSource.COVER -> "取自当前曲目封面主色；未播放或无封面时改用系统壁纸配色"
    ColorSource.FIXED -> "始终使用内置配色"
}
