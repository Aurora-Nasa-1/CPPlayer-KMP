package cp.player.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** 桌面没有壁纸 Monet：系统只给「一个强调色」和「一张壁纸图」，都要经 materialkolor 展开。 */
@Composable
actual fun supportsPlatformDynamicScheme(): Boolean = false

@Composable
actual fun platformDynamicScheme(dark: Boolean): ColorScheme? = null

/**
 * Windows 的 DWM 强调色。
 *
 * `by lazy` ⇒ 每个进程只读一次注册表（约 20–30 ms）。首次调用发生在
 * `CpTheme` 的首帧，此时启动页正在显示，代价可接受；换来的好处是**不会**
 * 出现「先按默认色渲染、几十毫秒后再过渡到系统色」的启动闪烁。
 *
 * 只在用户显式选择「跟随系统」时才会走到这里（默认来源是 `FIXED`），
 * 所以绝大多数启动不会付这个代价。
 */
actual fun platformAccentSeed(): Color? = windowsAccentColor

/**
 * 桌面壁纸种子色。
 *
 * `by lazy` ⇒ 每个进程只解一次壁纸。**这里比 [platformAccentSeed] 贵得多**
 * （读文件 + 解码，几十到几百毫秒），所以它**不在 composition 里同步调用** ——
 * 由 `AppModel` 在 `Dispatchers.Default` 上预热，见 `startCoverColorTracking`。
 * 壁纸在运行中变化不感知（用户换了壁纸重启即可），换来的是「跟随封面但没在播放」
 * 这个状态不会每次切歌都重解一次壁纸。
 */
actual fun platformWallpaperSeed(): Color? = windowsWallpaperSeed

private val windowsAccentColor: Color? by lazy { readWindowsAccentColor() }

private val windowsWallpaperSeed: Color? by lazy {
    readWindowsWallpaperFile()?.let(::extractWallpaperSeed)
}

/**
 * 找到当前的系统壁纸文件。两个来源，按可靠性排序：
 *
 * 1. 注册表 `HKCU\Control Panel\Desktop\Wallpaper` —— 用户直接设静态壁纸时的原始路径。
 * 2. `%APPDATA%\Microsoft\Windows\Themes\TranscodedWallpaper` —— Windows 转码后的缓存副本。
 *
 * 第 2 条不是冗余：**动态壁纸（Wallpaper Engine 之类）和幻灯片会让注册表值为空**，
 * 此时转码副本是唯一能拿到的静态帧。
 *
 * ⚠️ 转码副本**没有扩展名** —— 不能按后缀判断格式，只能靠内容嗅探（见 [extractWallpaperSeed]）。
 */
private fun readWindowsWallpaperFile(): File? {
    if (!isWindows()) return null
    val fromRegistry = queryRegistry(DESKTOP_KEY, WALLPAPER_VALUE)
        ?.let(::parseWallpaperPath)
        ?.let(::File)
        ?.takeIf { it.isFile && it.length() > 0L }
    if (fromRegistry != null) return fromRegistry

    val roaming = roamingAppData(System.getenv("APPDATA"), System.getProperty("user.home"))
        ?: return null
    return File(roaming, TRANSCODED_WALLPAPER_RELATIVE)
        .takeIf { it.isFile && it.length() > 0L }
}

/**
 * 定位 Windows 的 Roaming 目录。
 *
 * ⚠️ **不能只用 `APPDATA` 环境变量**：已实测在某些启动方式下它是 `null`
 * （Gradle 起的测试 JVM 里就是），此时壁纸回退会**静默失效** —— 主题退到静态色板
 * （靛蓝 `#4F55A5`），用户只看到「配色不对」却完全无从判断是取色失败还是壁纸本来就那样。
 * 用 `user.home` 兜底，两者都拿不到才放弃。
 */
internal fun roamingAppData(env: String?, home: String?): File? {
    env?.takeIf { it.isNotBlank() }?.let { return File(it) }
    home?.takeIf { it.isNotBlank() }?.let { return File(it, "AppData\\Roaming") }
    return null
}

/**
 * 解析 `reg query` 的壁纸路径。输出形如：
 * ```
 * HKEY_CURRENT_USER\Control Panel\Desktop
 *     Wallpaper    REG_SZ    C:\Users\me\Pictures\wall.jpg
 * ```
 *
 * ⚠️ 值为空时 `reg` 仍会打印 `Wallpaper    REG_SZ    `（尾部一堆空白），
 * 必须当作「没有壁纸」返回 null，否则会得到一个指向 `""` 的 `File`。
 * 路径本身可能含 `Wallpaper` 字样（`WallpaperEngineBackupWallpaper.jpg`），
 * 所以锚在 `REG_SZ` 上而不是单纯找 `Wallpaper`。
 */
internal fun parseWallpaperPath(output: String): String? =
    WALLPAPER_PATTERN.find(output)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 解码壁纸并取种子色。
 *
 * **降采样解码是这里的重点**：4K 壁纸全解会分配约 3300 万个 int（~130 MB），
 * 只为取一个主色完全不值。`setSourceSubsampling` 让解码器**一开始就只输出**
 * 约 [CoverSampleSizePx] 见方的像素 —— 内存和解码时间都降两个数量级。
 *
 * 任何一步失败都返回 null（文件损坏 / 不是图片 / 没有可用的 ImageIO reader /
 * 是动态壁纸且副本也是视频），主题继续回退到 [DefaultSeedColor]。
 */
internal fun extractWallpaperSeed(file: File): Color? = runCatching {
    ImageIO.createImageInputStream(file)?.use { stream ->
        val readers = ImageIO.getImageReaders(stream)
        if (!readers.hasNext()) return@use null
        val reader = readers.next()
        try {
            reader.input = stream
            val fullWidth = reader.getWidth(0)
            val fullHeight = reader.getHeight(0)
            if (fullWidth <= 0 || fullHeight <= 0) return@use null
            // 只解到 ~112px 就够量化用了，step 至少为 1（小图不能放大成 0）。
            val step = (minOf(fullWidth, fullHeight) / CoverSampleSizePx).coerceAtLeast(1)
            val param = reader.defaultReadParam
            param.setSourceSubsampling(step, step, 0, 0)
            val image = reader.read(0, param) ?: return@use null
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return@use null
            val pixels = IntArray(width * height)
            image.getRGB(0, 0, width, height, pixels, 0, width)
            extractSeedColor(pixels, width, height)
        } finally {
            reader.dispose()
        }
    }
}.getOrNull()

private fun readWindowsAccentColor(): Color? =
    queryRegistry(DWM_KEY, ACCENT_VALUE)?.let(::parseAccentColor)

/**
 * 跑一次 `reg query` 并返回 stdout。
 *
 * 不用 JNI / JNA：为了几个字节的注册表值引一个原生依赖不划算，`reg.exe` 随系统自带。
 * 超过 1 秒强杀 —— 正常查询在几十毫秒内返回，卡住说明环境异常，
 * 不能让启动流程跟着一起挂。
 */
private fun queryRegistry(key: String, value: String): String? {
    if (!isWindows()) return null
    return runCatching {
        val process = ProcessBuilder("reg", "query", key, "/v", value)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching null
        }
        output
    }.getOrNull()
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)

/**
 * `reg query` 的输出形如：
 * ```
 * HKEY_CURRENT_USER\Software\Microsoft\Windows\DWM
 *     AccentColor    REG_DWORD    0xff1d4f4f
 * ```
 *
 * ⚠️ DWM 的 `AccentColor` 是 **ABGR** 而不是 ARGB —— 直接当 ARGB 读会把红蓝对调，
 * 取到「蓝色主题」却渲染成橙色。
 */
internal fun parseAccentColor(output: String): Color? {
    val hex = ACCENT_PATTERN.find(output)?.groupValues?.get(1) ?: return null
    val value = hex.toLongOrNull(16)?.toInt() ?: return null
    val alpha = (value ushr 24) and 0xFF
    val blue = (value ushr 16) and 0xFF
    val green = (value ushr 8) and 0xFF
    val red = value and 0xFF
    // 用户没开「透明效果」时 alpha 常为 0，此时按不透明处理，否则会得到一个全透明的种子色。
    return Color(red, green, blue, if (alpha == 0) 0xFF else alpha)
}

private const val DWM_KEY = "HKCU\\Software\\Microsoft\\Windows\\DWM"
private const val ACCENT_VALUE = "AccentColor"
private const val DESKTOP_KEY = "HKCU\\Control Panel\\Desktop"
private const val WALLPAPER_VALUE = "Wallpaper"
private const val TRANSCODED_WALLPAPER_RELATIVE = "Microsoft\\Windows\\Themes\\TranscodedWallpaper"
private val ACCENT_PATTERN = Regex("AccentColor\\s+REG_DWORD\\s+0x([0-9a-fA-F]{1,8})")

/**
 * 用 `[ \t]` 而不是 `\s`：`\s` 会匹配换行，值为空时 `\s*` 会把换行也吃掉，
 * 于是 `(.*)` 捕获到**下一行**的内容 —— 空壁纸被解析成一个乱七八糟的路径。
 */
private val WALLPAPER_PATTERN = Regex("Wallpaper[ \\t]+REG_SZ[ \\t]*(.*)", RegexOption.IGNORE_CASE)
