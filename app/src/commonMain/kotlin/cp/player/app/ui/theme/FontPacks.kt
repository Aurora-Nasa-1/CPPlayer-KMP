package cp.player.app.ui.theme

import androidx.compose.runtime.Composable

/**
 * 需要**按需下载**的字体包（只有 Android 走这条路，桌面端随安装包内置）。
 *
 * ## 为什么 Android 要下载而不是内置
 *
 * 两款字体合计约 30 MB（MiSans VF 20.1 MB + Noto Sans JP 9.6 MB）。它们只服务
 * 「界面里有中文 / 日文」这一个需求，而 APK 体积是每一个用户都要付的成本 ——
 * 把它做成一次性的、用户可见、可删除的下载，比无条件塞进 APK 更合理。
 *
 * ## 文件从哪来
 *
 * 仓库同一份字体文件也发布在本仓库的 GitHub Release 资源里
 * （tag 见 [AppFontPack.url]），**App 只认 release 资源**：
 * 走 `raw.githubusercontent.com` 会跟随分支变化，且没有缓存头，
 * 一旦以后调整文件就得让所有客户端重新下载一遍。
 *
 * ⚠️ 改文件名 / tag / 资源名时，**必须同步改这里的 url**，否则线上用户下载 404。
 *
 * @param face 对应哪一款 [AppFontFace]
 * @param fileName 落到 `filesDir/fonts/` 下的文件名（也是 release 资源名）
 * @param url 下载地址（GitHub Release 资源，不可变）
 * @param sizeBytes 预期字节数：用于进度百分比与「下到一半断了」的判定
 */
enum class AppFontPack(
    val face: AppFontFace,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
) {
    /** Noto Sans JP（约 9.6 MB）。日文歌词 / 日文标题的字形正确性全靠它。 */
    JAPANESE(
        face = AppFontFace.NOTO_SANS_JP,
        fileName = "noto_sans_jp.ttf",
        url = "https://github.com/Aurora-Nasa-1/CPPlayer-KMP/releases/download/fonts-v1/noto_sans_jp.ttf",
        sizeBytes = 9_589_900L,
    ),

    /** MiSans VF（约 20.1 MB）。中文与全角标点的主字体。 */
    MISANS(
        face = AppFontFace.MISANS,
        fileName = "misans_vf.ttf",
        url = "https://github.com/Aurora-Nasa-1/CPPlayer-KMP/releases/download/fonts-v1/misans_vf.ttf",
        sizeBytes = 20_093_424L,
    ),
}

/** 一个字体包的当前状态。 */
enum class FontPackPhase {
    /** 没下载（Android 初始状态）。 */
    MISSING,

    /** 下载中，进度见 [FontPackStatus.progress]。 */
    DOWNLOADING,

    /** 已安装且可用。 */
    READY,

    /** 下载 / 校验失败，原因见 [FontPackStatus.error]。 */
    FAILED,
}

/**
 * 字体包状态快照。
 *
 * @param progress 0f–1f，仅 [FontPackPhase.DOWNLOADING] 有意义
 * @param error 失败原因（诊断用原文，可能是英文异常信息，不参与多语言）
 */
class FontPackStatus(
    val pack: AppFontPack,
    val phase: FontPackPhase,
    val progress: Float = 0f,
    val error: String? = null,
)

/**
 * 当前各字体包的状态。
 *
 * 桌面端恒为「已内置」，因此下载 UI 在桌面端只用来**展示**（见 `PlatformFontPacksSection`）。
 */
@Composable
expect fun rememberFontPackStatuses(): List<FontPackStatus>

/** 当前平台是否支持字体包（Android 需要 API 26+ 才能按字重实例化可变字体）。 */
fun isFontPackSupported(): Boolean = platformSupportsFontPacks()

/** 由各平台实现「是否支持」判定。 */
expect fun platformSupportsFontPacks(): Boolean

/** 开始下载并安装 [pack]；重复调用是幂等的（正在下载时直接忽略）。 */
expect fun downloadFontPack(pack: AppFontPack)

/** 删除已安装的 [pack]，文本立即回到平台回退字体。 */
expect fun removeFontPack(pack: AppFontPack)
