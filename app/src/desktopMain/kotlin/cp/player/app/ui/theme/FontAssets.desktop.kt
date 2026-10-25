package cp.player.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.platform.Font

/**
 * 桌面端内置字体在 classpath 上的位置。
 *
 * 文件实体在 `app/src/desktopMain/resources/fonts/`（**不在** `composeResources` 下）：
 * 那两个目录是 `commonMain` 的，放进去 Android 也会一起打包 ——
 * 而 Android 的这两款字体是**下载**来的（见 [AppFontPack]）。
 */
private const val JapaneseFontPath = "fonts/noto_sans_jp.ttf"
private const val MiSansFontPath = "fonts/misans_vf.ttf"

/** classpath 查询的锚点（顶层的 `expect fun` 拿不到 `javaClass`）。 */
private object DesktopFontAnchor

/**
 * 桌面端：中日文字体随安装包内置，**同步**从 classpath 读。
 *
 * 用 `androidx.compose.ui.text.platform.Font(identity, …)` 而不是 compose-resources 的
 * `Font(Res.font.…)`：后者的路径必须是 `commonMain/composeResources`（Android 会跟着打包），
 * 而且桌面端是**异步**加载（先给占位字体、字节到了再换），
 * 会让字阶在启动后换一次身份、整棵树重组一次。classpath 版是同步的，没有这个二次抖动。
 *
 * `remember` 无 key：字体是安装包的一部分，运行期不会变。
 */
@Composable
actual fun rememberAppFontFamilies(): AppFontFamilies = remember {
    AppFontFamilies(
        japanese = bundledFamily(JapaneseFontPath),
        misans = bundledFamily(MiSansFontPath),
    )
}

/**
 * 把一个 classpath 字体文件按 [AppFontWeights] 展开成五档家族。
 *
 * 桌面端的合成粗体是**空实现**（`FontSynthesis_desktopKt.synthesizeTypeface` 直接
 * `areturn` 原样返回），所以五档必须都真的存在；可变字体靠 `wght` 轴给出这五档。
 *
 * 文件缺失 / 解析失败时返回 null（文本回退到平台字体），不抛 —— 一个字体不该让整个应用起不来。
 */
private fun bundledFamily(path: String): FontFamily? {
    val loader = DesktopFontAnchor::class.java.classLoader ?: return null
    if (loader.getResource(path) == null) return null
    return runCatching {
        FontFamily(
            AppFontWeights.map { (weight, fontWeight) ->
                // 位置参数：这是平台 actual 函数，具名参数在两端改名就会编译不过。
                Font(
                    path,
                    fontWeight,
                    FontStyle.Normal,
                    FontVariation.Settings(FontVariation.weight(weight)),
                )
            },
        )
    }.getOrNull()
}

/** 桌面端两款字体都在安装包里，状态恒为「已安装」。 */
@Composable
actual fun rememberFontPackStatuses(): List<FontPackStatus> = remember {
    AppFontPack.entries.map { FontPackStatus(it, FontPackPhase.READY, progress = 1f) }
}

/** 桌面端不涉及下载，恒为 true（UI 只做展示）。 */
actual fun platformSupportsFontPacks(): Boolean = true

/** 桌面端字体内置，没有下载动作。 */
actual fun downloadFontPack(pack: AppFontPack) = Unit

/** 桌面端字体是安装包的一部分，删不掉（删了应用就没字体了）。 */
actual fun removeFontPack(pack: AppFontPack) = Unit
