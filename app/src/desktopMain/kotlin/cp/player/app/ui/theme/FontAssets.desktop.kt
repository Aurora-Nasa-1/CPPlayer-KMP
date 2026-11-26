package cp.player.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.platform.Font

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 桌面端内置字体在 classpath 上的位置。
 *
 * 文件实体在 `app/src/desktopMain/resources/fonts/`（**不在** `composeResources` 下）：
 * 那两个目录是 `commonMain` 的，放进去 Android 也会一起打包 ——
 * 而 Android 的这两款字体是**下载**来的（见 [AppFontPack]）。
 */
private const val JapaneseFontPath = "fonts/noto_sans_jp.ttf"
private const val MiSansFontPath = "fonts/misans_vf.ttf"
private const val GoogleSansFontPath = "fonts/google_sans_flex.ttf"

@Composable
internal actual fun rememberExternalGoogleSansFlexFamily(roundness: Int?): FontFamily? {
    if (System.getProperty("org.graalvm.nativeimage.imagecode") != "runtime" ||
        !System.getProperty("os.name").startsWith("Windows")) return null
    val file = remember { resolveFontDiskFile(GoogleSansFontPath) }
    return remember(file, roundness) {
        if (file == null) FontFamily.Default else FontFamily(
            AppFontWeights.map { (weight, fontWeight) ->
                Font(file, fontWeight, FontStyle.Normal, googleSansFlexVariation(weight, roundness))
            },
        )
    }
}

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
 * 把一个字体（优先本地磁盘文件，回退到 classpath / 嵌入式资源）按 [AppFontWeights] 展开成五档家族。
 *
 * 桌面端的合成粗体是**空实现**（`FontSynthesis_desktopKt.synthesizeTypeface` 直接
 * `areturn` 原样返回），所以五档必须都真的存在；可变字体靠 `wght` 轴给出这五档。
 *
 * 文件缺失 / 解析失败时返回 null（文本回退到平台字体），不抛 —— 一个字体不该让整个应用起不来。
 */
private fun bundledFamily(path: String): FontFamily? {
    // 1. 优先尝试从本地磁盘文件加载（外置在 exe 同级的 fonts/ 目录或 resources/fonts/）
    val diskFile = resolveFontDiskFile(path)
    if (diskFile != null && diskFile.isFile && diskFile.length() > 0) {
        val diskFamily = runCatching {
            FontFamily(
                AppFontWeights.map { (weight, fontWeight) ->
                    Font(
                        diskFile,
                        fontWeight,
                        FontStyle.Normal,
                        FontVariation.Settings(FontVariation.weight(weight)),
                    )
                },
            )
        }.getOrNull()
        if (diskFamily != null) return diskFamily
    }

    // 2. 回退到 classpath 或 Native Image 编译期嵌入资源
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

private fun resolveFontDiskFile(relativePath: String): File? {
    val candidates = ArrayList<File>()
    val baseName = File(relativePath).name

    // a. exe 所在目录探测 (Win32 API 或 ProcessHandle)
    try {
        val appRoot = findAppDirectory()
        if (appRoot != null && appRoot.exists()) {
            candidates += File(appRoot, relativePath)
            candidates += File(appRoot, "fonts/$baseName")
            candidates += File(appRoot, "resources/$relativePath")
            candidates += File(appRoot, baseName)
        }
    } catch (_: Throwable) {}

    // b. 当前工作目录探测
    val userDir = File(System.getProperty("user.dir", "."))
    candidates += File(userDir, relativePath)
    candidates += File(userDir, "fonts/$baseName")
    candidates += File(userDir, "app/src/desktopMain/resources/$relativePath")

    return candidates.firstOrNull { it.isFile && it.length() > 0 }
}

private fun findAppDirectory(): File? {
    try {
        if (System.getProperty("os.name", "").lowercase().contains("win")) {
            java.lang.foreign.Arena.ofConfined().use { arena ->
                val kernel32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", arena)
                val getModuleFileName = java.lang.foreign.Linker.nativeLinker().downcallHandle(
                    kernel32.find("GetModuleFileNameW").orElseThrow(),
                    java.lang.foreign.FunctionDescriptor.of(
                        java.lang.foreign.ValueLayout.JAVA_INT,
                        java.lang.foreign.ValueLayout.ADDRESS,
                        java.lang.foreign.ValueLayout.ADDRESS,
                        java.lang.foreign.ValueLayout.JAVA_INT,
                    ),
                )
                val pathBuf = arena.allocate(1024L)
                val len = getModuleFileName.invokeWithArguments(java.lang.foreign.MemorySegment.NULL, pathBuf, 512) as Int
                if (len > 0) {
                    val path = pathBuf.getString(0, StandardCharsets.UTF_16LE)
                    return File(path).parentFile
                }
            }
        }
    } catch (_: Throwable) {}

    return try {
        ProcessHandle.current().info().command().map { File(it).parentFile }.orElse(null)
    } catch (_: Throwable) {
        null
    }
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
