package cp.player.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

/**
 * [extractSeedColor] 回归测试。
 *
 * 两条最容易回归的行为：
 * 1. 有色彩的封面**必须**能取出种子色（否则「跟随封面」这个功能等于不存在）；
 * 2. 灰阶封面**必须**返回 null，让主题回退到固定种子色 —— 若返回灰色，
 *    整个界面会跟着发灰，比不取色更难看。
 */
class CoverColorTest {

    @Test
    fun `a saturated cover yields a seed close to its own colour`() {
        val seed = extractSeedColor(solidImage(0xFF3B82F6.toInt()))
        assertNotNull(seed, "纯色封面必须能取出种子色，否则「跟随封面」功能形同不存在")
        assertChannel(0x3B, seed.red, "red")
        assertChannel(0x82, seed.green, "green")
        assertChannel(0xF6, seed.blue, "blue")
    }

    @Test
    fun `a greyscale cover yields no seed so the theme falls back`() {
        // 黑 / 白 / 中灰的 HSV 饱和度都是 0，全部应当被 MinSaturation 挡掉。
        listOf(0xFF000000, 0xFF808080, 0xFFFFFFFF).forEach { argb ->
            assertNull(
                extractSeedColor(solidImage(argb.toInt())),
                "灰阶封面（0x${argb.toUInt().toString(16)}）不该产生种子色：" +
                    "取出来会让整个界面发灰，比回退到固定配色更难看",
            )
        }
    }

    /**
     * 近灰（带一点色偏）**会被采纳** —— 这是 Material You 自己的判据，不是缺陷。
     *
     * 旧实现额外加了一条「HSV 饱和度 < 0.18 就拒绝」，比系统更严。那正是
     * 「取色和别的 MD3 应用不一样」的来源之一，已经删掉。现在只剩 `Score` 的彩度过滤
     * （`filter = true`）这一条判据；**真正无彩度的灰仍然会被剔除**（见上一条）。
     *
     * 这条断言的意图是**锁住「别再自作主张加更严的阈值」**。
     */
    @Test
    fun `a near-grey cover is accepted because that is what Material You does`() {
        val seed = extractSeedColor(solidImage(0xFF7A7F78.toInt()))
        assertNotNull(
            seed,
            "彩度达标的近灰会被 Material You 采纳；自行加更严的阈值就会和系统不一致",
        )
        assertChannel(0x7A, seed.red, "red")
        assertChannel(0x7F, seed.green, "green")
        assertChannel(0x78, seed.blue, "blue")
    }

    /**
     * 这条钉住「取色必须和其他 MD3 应用一致」这个要求 —— 它是**实际发生过的回归**。
     *
     * Win11 默认壁纸 = 大面积蓝底 + 中间一小朵鲜艳的花。旧实现「优先取最鲜艳的色块」
     * 会取到花的玫红 `#C03058`，而 Material You（`QuantizerCelebi` + `Score`，**按面积打分**）
     * 取的是底色的蓝。用户一眼就看出「你这取色和别人不一样」。
     *
     * 直接喂像素数组而不经过 `ImageBitmap`：核心逻辑本来就在数组这一层。
     */
    @Test
    fun `a large calm area beats a small vivid accent`() {
        val size = 112
        val inset = size * 36 / 100
        val pixels = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            // 中间 32×32（约 8% 面积）是玫红点缀，其余是蓝底。
            if (x in inset until size - inset && y in inset until size - inset) {
                0xFFC03058.toInt()
            } else {
                0xFF1B4F9C.toInt()
            }
        }
        val seed = extractSeedColor(pixels, size, size)
        assertNotNull(seed, "有彩色的图必须取到种子色")
        assertChannel(0x1B, seed.red, "red")
        assertChannel(0x4F, seed.green, "green")
        assertChannel(0x9C, seed.blue, "blue")
    }

    /**
     * 容差 8 而不是 1：`Palette` 的量化器把每个通道截断到 **5 bit**
     * （`0x3B → 0x38`），最大误差 7。这是库的固有行为，不是缺陷。
     *
     * 这条断言要证明的是「种子色确实来自封面」，不是「逐位还原」——
     * 种子色后面还要过一遍 HCT 转换，几个色阶的偏差没有意义。
     */
    private fun assertChannel(expected: Int, actual: Float, name: String) {
        val value = (actual * 255f).roundToInt()
        assertTrue(
            abs(value - expected) <= 8,
            "$name 通道期望 0x${expected.toString(16)}，实际 0x${value.toString(16)}",
        )
    }
}

/** 造一张纯色位图。走 Skia 是因为 `ImageBitmap` 只有读像素的接口，没有写的。 */
internal fun solidImage(argb: Int, size: Int = 64): ImageBitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo.makeN32(size, size, ColorAlphaType.OPAQUE))
    bitmap.erase(argb)
    return bitmap.asComposeImageBitmap()
}
