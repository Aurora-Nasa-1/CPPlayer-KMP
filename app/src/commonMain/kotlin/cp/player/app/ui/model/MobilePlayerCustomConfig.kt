package cp.player.app.ui.model

/**
 * 手机端单页播放器偏好。
 *
 * 封面比例、圆角、光晕、波形、对齐与按键缩放等旧字段仅为持久化兼容保留；
 * 新版由版式统一决定视觉，使用手势、歌词字号、逐字高亮和翻译偏好。
 */
data class MobilePlayerCustomConfig(
    /** 是否启用全新单页播放布局（关闭则退回经典多页 HorizontalPager 布局）。 */
    val enabled: Boolean = true,

    /** 封面宽度占屏幕宽度的比例 (0.55f ~ 0.95f，默认 0.72f)。 */
    val coverScale: Float = 0.72f,

    /** 封面圆角大小（dp，范围 8 ~ 40，默认 24）。 */
    val coverCornerDp: Int = 24,

    /** 封面阴影海拔（dp，范围 0 ~ 32，默认 16）。 */
    val coverElevationDp: Int = 16,

    /** 封面背后弥散环境光晕（营造浮动沉浸景深）。 */
    val ambientGlow: Boolean = true,

    /** 封面左右滑动手势切歌（左滑下一首，右滑上一首）。 */
    val coverSwipeGesture: Boolean = true,

    /** 进度条样式：true = M3 Expressive 波形进度条，false = 经典平直进度条。 */
    val useWavySeekBar: Boolean = false,

    /** 歌词显示行数空间（默认 2 行，即 0.5 + 1 + 0.5 经典排版）。 */
    val lyricsLines: Int = 2,

    /** 歌词预览字体字号（sp，范围 14 ~ 22，默认 17）。 */
    val lyricsFontSizeSp: Int = 17,

    /** 歌词预览对齐方式：false = 居中对齐，true = 靠左对齐。 */
    val lyricsAlignStart: Boolean = false,

    /** 是否在歌词预览中启用逐字卡拉OK动效（根据音节时间平滑点亮）。 */
    val karaokeEnabled: Boolean = true,

    /** 是否在歌词预览中显示歌词翻译（若有）。 */
    val showTranslation: Boolean = true,

    /** 走带控制按键整体缩放比例 (0.85f ~ 1.15f，默认 1.0f)。 */
    val controlsScale: Float = 1.0f,
)
