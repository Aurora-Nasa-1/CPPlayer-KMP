package cp.player.app.ui.theme

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 桌面壁纸取色回归测试。
 *
 * 重点不是「能不能取到色」，而是**取不到色时不能崩、也不能返回脏值**：
 * 壁纸来源五花八门（动态壁纸、幻灯片、无扩展名的转码副本、损坏文件、空文件），
 * 每一条失败路径都必须干净地回退到 [DefaultSeedColor]。
 *
 * ⚠️ 这里**不能**用 `reg.exe` 造夹具 —— 沙箱把它列进了程序黑名单。
 * 所以解析逻辑必须与「跑 reg」解耦：`parseWallpaperPath` 只吃字符串。
 */
class WallpaperSeedTest {

    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempFiles.forEach { it.delete() }
    }

    // ---------------- 注册表解析 ----------------

    @Test
    fun `parses the wallpaper path out of reg query output`() {
        val output = "HKEY_CURRENT_USER\\Control Panel\\Desktop\n" +
            "    Wallpaper    REG_SZ    C:\\Users\\me\\Pictures\\wall.jpg\n"
        assertEquals("C:\\Users\\me\\Pictures\\wall.jpg", parseWallpaperPath(output))
    }

    @Test
    fun `a blank wallpaper value means no wallpaper`() {
        // 动态壁纸 / 幻灯片时注册表值就是空的。reg 仍会打印这一行（尾部一堆空白），
        // 当成「有壁纸」会得到一个指向 "" 的 File，后续解码必炸。
        val output = "HKEY_CURRENT_USER\\Control Panel\\Desktop\n" +
            "    Wallpaper    REG_SZ    \n"
        assertNull(parseWallpaperPath(output))
    }

    @Test
    fun `a blank value does not swallow the next line`() {
        // `\s` 会匹配换行 —— 若用 `\s*` 做分隔，值为空时会把下一行吃进捕获组。
        // 这条专门钉住那个坑。
        val output = "HKEY_CURRENT_USER\\Control Panel\\Desktop\n" +
            "    Wallpaper    REG_SZ    \n" +
            "    SomeOtherValue    REG_SZ    C:\\should\\not\\be\\captured.jpg\n"
        assertNull(parseWallpaperPath(output), "空值不该把下一行当成路径")
    }

    @Test
    fun `missing wallpaper value means no wallpaper`() {
        assertNull(parseWallpaperPath("错误: 系统找不到指定的注册表项或值。"))
    }

    @Test
    fun `a path that itself contains the word Wallpaper is parsed whole`() {
        // 装了 Wallpaper Engine 的机器上真实存在的文件名 —— 锚在 REG_SZ 上才不会截断。
        val expected =
            "C:\\Users\\me\\AppData\\Roaming\\Microsoft\\Windows\\Themes\\" +
                "WallpaperEngineBackupWallpaper.jpg"
        val output = "HKEY_CURRENT_USER\\Control Panel\\Desktop\n" +
            "    Wallpaper    REG_SZ    $expected\n"
        assertEquals(expected, parseWallpaperPath(output))
    }

    // ---------------- 解码 + 取色 ----------------

    @Test
    fun `extracts a seed from a wallpaper image`() {
        val seed = extractWallpaperSeed(writeImage(0xFF3B82F6.toInt(), 240, 160))
        assertNotNull(seed, "有色彩的壁纸必须能取出种子色，否则这个回退等于不存在")
        assertChannel(0x3B, seed.red, "red")
        assertChannel(0x82, seed.green, "green")
        assertChannel(0xF6, seed.blue, "blue")
    }

    @Test
    fun `decodes by content rather than by file extension`() {
        // Windows 的 TranscodedWallpaper **没有扩展名** —— 按后缀判断格式在这里必然失败。
        val file = writeImage(0xFF3B82F6.toInt(), 240, 160, extensionless = true)
        assertTrue(!file.name.contains('.'), "夹具必须是无扩展名文件，实际：${file.name}")
        assertNotNull(extractWallpaperSeed(file), "无扩展名的壁纸只能靠内容嗅探解出来")
    }

    @Test
    fun `a large wallpaper is subsampled without skewing the colour`() {
        // 降采样解码是「不卡顿」的关键：1600×1200 全解要 192 万个 int，
        // 4K 壁纸更是 3300 万。这条断言保证降采样不会把颜色采歪。
        val seed = extractWallpaperSeed(writeImage(0xFF3B82F6.toInt(), 1600, 1200))
        assertNotNull(seed)
        assertChannel(0x3B, seed.red, "red")
        assertChannel(0x82, seed.green, "green")
        assertChannel(0xF6, seed.blue, "blue")
    }

    @Test
    fun `a greyscale wallpaper yields no seed so the theme keeps its fallback`() {
        assertNull(
            extractWallpaperSeed(writeImage(0xFF808080.toInt(), 240, 160)),
            "灰阶壁纸取出来的灰会让整个界面发灰，比回退到固定配色更难看",
        )
    }

    @Test
    fun `a file that is not an image yields no seed instead of throwing`() {
        val file = tempFile("cpplayer-wallpaper-", ".jpg")
        file.writeText("this is definitely not a JPEG")
        assertNull(extractWallpaperSeed(file))
    }

    @Test
    fun `an empty file yields no seed instead of throwing`() {
        val file = tempFile("cpplayer-wallpaper-", ".png")
        assertTrue(file.length() == 0L)
        assertNull(extractWallpaperSeed(file))
    }

    @Test
    fun `a missing file yields no seed instead of throwing`() {
        assertNull(extractWallpaperSeed(File("/definitely/not/here/wall.jpg")))
    }

    // ---------------- Roaming 目录定位 ----------------

    @Test
    fun `roaming dir prefers the APPDATA environment variable`() {
        assertEquals(
            File("C:\\env\\Roaming").path,
            roamingAppData("C:\\env\\Roaming", "C:\\home")?.path,
        )
    }

    @Test
    fun `roaming dir falls back to user home when APPDATA is absent`() {
        // 已实测：Gradle 起的测试 JVM 里 APPDATA 就是 null。只认环境变量的话，
        // 壁纸回退会在这些启动方式下**静默失效**，主题悄悄退到静态色板。
        assertEquals(
            File("C:\\home", "AppData\\Roaming").path,
            roamingAppData(null, "C:\\home")?.path,
        )
    }

    @Test
    fun `a blank APPDATA is treated as absent`() {
        assertEquals(
            File("C:\\home", "AppData\\Roaming").path,
            roamingAppData("   ", "C:\\home")?.path,
        )
    }

    @Test
    fun `no roaming dir when neither source is available`() {
        assertNull(roamingAppData(null, null))
    }

    // ---------------- 夹具 ----------------

    private fun tempFile(prefix: String, suffix: String?): File =
        File.createTempFile(prefix, suffix).also { tempFiles += it }

    /**
     * 造一张纯色 PNG。
     *
     * `extensionless = true` 时用**没有点号**的名字 —— 这才是 `TranscodedWallpaper` 的真实形态。
     * （`File.createTempFile(prefix, null)` 会补上 `.tmp`，测不出「不依赖扩展名」。）
     */
    private fun writeImage(
        argb: Int,
        width: Int,
        height: Int,
        extensionless: Boolean = false,
    ): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) image.setRGB(x, y, argb)
        }
        val file = if (extensionless) {
            File(System.getProperty("java.io.tmpdir"), "cpplayer-wallpaper-${System.nanoTime()}")
                .also { tempFiles += it }
        } else {
            tempFile("cpplayer-wallpaper-", ".png")
        }
        ImageIO.write(image, "png", file)
        return file
    }

    /**
     * 容差 8 而不是 1：`Palette` 的量化器把每个通道截断到 **5 bit**（`0x3B → 0x38`），
     * 最大误差 7。这是库的固有行为，不是缺陷。与 `CoverColorTest` 同一口径。
     */
    private fun assertChannel(expected: Int, actual: Float, name: String) {
        val value = (actual * 255f).roundToInt()
        assertTrue(
            abs(value - expected) <= 8,
            "$name 通道期望 0x${expected.toString(16)}，实际 0x${value.toString(16)}",
        )
    }
}
