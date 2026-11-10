package cp.player.app.ui.screen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 均衡器推子（5 段垂直推子调音台）拖拽换算的回归测试。
 *
 * 真正的缺陷不在公式里，而在**调用点**：`pointerInput` 的手势协程只在 key 变化时重启，
 * `detectVerticalDragGestures` 又是个 `awaitEachGesture` 死循环 ⇒ 闭包里捕获的
 * `band.gainDb` 停留在协程启动那一刻，每帧「旧值 + 本帧位移」重算的结果会被下一帧覆盖，
 * 表现为拖不动 / 拖完弹回；同批捕获的 `eq` 快照还会在提交时把别的频段改动一起回滚。
 *
 * 所以这里分两层锁：① 换算数学（量程、方向、量化、夹紧）；② 源码接线（手势里必须读
 * ref、父层必须现读 `draft`），因为前者单测量不到，后者运行期也量不到。
 */
class EqualizerFaderDragTest {

    private val trackPx = 130f

    private fun readSource(relative: String): String {
        var dir: File? = File("").absoluteFile
        val found = buildList {
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { add(it) }
                val underApp = File(File(dir, "app"), relative)
                if (underApp.isFile) add(underApp)
                dir = dir.parentFile
            }
        }.firstOrNull()
        check(found != null) { "找不到 $relative" }
        return found.readText()
    }

    // ---------------------------------------------------------------- 换算数学

    @Test
    fun `向上拖满轨道到正上限`() {
        assertEquals(12f, faderGainFromDrag(0f, -trackPx, trackPx))
    }

    @Test
    fun `向下拖满轨道到负上限`() {
        assertEquals(-12f, faderGainFromDrag(0f, trackPx, trackPx))
    }

    @Test
    fun `从正增益往下拖四分之一轨道`() {
        // 12dB - (32.5/130 * 24dB) = 6dB
        assertEquals(6f, faderGainFromDrag(12f, trackPx / 4f, trackPx))
    }

    @Test
    fun `越界位移夹紧在量程内`() {
        assertEquals(12f, faderGainFromDrag(11f, -trackPx, trackPx))
        assertEquals(-12f, faderGainFromDrag(-11f, trackPx, trackPx))
    }

    @Test
    fun `增益按 0_1dB 量化`() {
        // 1px ≈ 0.185dB → 量化到 -0.2dB；0.5px ≈ 0.09dB → 量化到 -0.1dB
        assertEquals(-0.2f, faderGainFromDrag(0f, 1f, trackPx))
        assertEquals(-0.1f, faderGainFromDrag(0f, 0.5f, trackPx))
    }

    @Test
    fun `轨道高度为零时退化为原值而不是 NaN`() {
        assertEquals(3f, faderGainFromDrag(3f, 40f, 0f))
        assertEquals(0f, faderGainAtY(40f, 0f))
    }

    // ---------------------------------------------------------------- 点击定位

    @Test
    fun `点击轨道按绝对位置定位增益`() {
        assertEquals(12f, faderGainAtY(0f, trackPx))
        assertEquals(0f, faderGainAtY(trackPx / 2f, trackPx))
        assertEquals(-12f, faderGainAtY(trackPx, trackPx))
    }

    @Test
    fun `点击超出轨道范围时夹紧`() {
        assertEquals(12f, faderGainAtY(-30f, trackPx))
        assertEquals(-12f, faderGainAtY(trackPx + 30f, trackPx))
    }

    // ---------------------------------------------------------------- 源码接线

    @Test
    fun `推子手势不得读捕获的 band 增益`() {
        val source = readSource(
            "src/commonMain/kotlin/cp/player/app/ui/screen/AudioEffectSettingsScreen.kt",
        )
        val dragBlock = source
            .substringAfter("detectVerticalDragGestures(")
            .substringBefore("detectTapGestures(")
        assertTrue(dragBlock.isNotEmpty(), "应能定位到推子的垂直拖拽手势块")
        assertFalse(
            "band.gainDb" in dragBlock,
            "拖拽回调里禁止直接读 band.gainDb：手势协程长驻，捕获值是启动时的快照，会让推子拖不动",
        )
        assertTrue(
            "liveGainDb.value" in dragBlock,
            "拖拽起点必须从 rememberUpdatedState 的 ref 读当前增益",
        )
        assertTrue(
            "liveOnGainChange.value" in dragBlock,
            "拖拽回调必须通过 ref 调用最新的 onGainChange，否则会写进已作废的组合闭包",
        )
    }

    @Test
    fun `推子回调不得用捕获的 eq 快照拼新配置`() {
        val source = readSource(
            "src/commonMain/kotlin/cp/player/app/ui/screen/AudioEffectSettingsScreen.kt",
        )
        val callbackBlock = source
            .substringAfter("EqualizerFaderConsole(")
            .substringBefore("onBandGainChangeFinished")
        assertFalse(
            "equalizer = eq.copy(" in callbackBlock,
            "onBandGainChange 必须现读 draft.equalizer，用捕获的 eq 快照会把别的频段改动整批回滚",
        )
        assertTrue(
            "val current = draft.equalizer" in callbackBlock,
            "onBandGainChange 应现读 draft.equalizer",
        )
    }
}
