package cp.player.app.i18n

import cp.player.app.AppModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「状态与文案不许耦死」的守卫。
 *
 * ### 它防什么
 *
 * 无缝转移的旧实现发的是**成品中文串**，UI 靠
 * `msg.startsWith("转移失败")` 决定用不用错误色。这种写法有三个问题，
 * 且**每一个都不会被编译器或任何常规测试发现**：
 *
 * 1. 改文案（翻译 / 换个措辞）→ 判断静默失配，失败提示显示成普通信息色；
 * 2. 换语言 → 那个中文串根本不会出现，判断恒为 false；
 * 3. 单测里断言的是「文案长什么样」，没人会想到去断言「配色判据还成立」。
 *
 * 现在 `AppModel` 发的是 [AppModel.HandoffState]（带类型的纯状态），
 * UI 看 [AppModel.HandoffState.failed]，文案由 `textOf(strings)` 在渲染时组出。
 *
 * ### 为什么这条测试有意义
 *
 * 它断言的是**「判失败不依赖任何具体文案」**这条性质本身。
 * 有人日后若图省事把状态又退回成品字符串（或加一条「先判文案再判状态」的旁路），
 * 这里就会红 —— 而那种退化在出图里也只是「颜色淡了一点」，很难被肉眼发现。
 */
class HandoffStateTest {

    private val zh = CpStrings.zh
    private val en = CpStrings.en

    @Test
    fun `failed is decided by type, never by wording`() {
        // 六个分支逐个钉住分类 —— 这是 UI 配色的唯一依据。
        assertFalse(AppModel.HandoffState.Starting("Pixel").failed, "进行中不是失败")
        assertFalse(AppModel.HandoffState.Done("Pixel").failed, "成功不是失败")
        assertTrue(AppModel.HandoffState.Failed(null).failed, "失败（含无原因）是失败")
        assertTrue(AppModel.HandoffState.Failed("timeout").failed, "失败（有原因）是失败")
        assertTrue(AppModel.HandoffState.TakeOverFailed("Pixel", "nope").failed, "接管失败是失败")
        assertTrue(AppModel.HandoffState.NoTrack.failed, "没有可转移的曲目按失败呈现（提示要明确）")
    }

    @Test
    fun `every state renders non-blank text in both languages`() {
        val samples = listOf(
            AppModel.HandoffState.NoTrack,
            AppModel.HandoffState.Starting("Pixel 7"),
            AppModel.HandoffState.Done("Pixel 7"),
            AppModel.HandoffState.Failed("timeout"),
            AppModel.HandoffState.Failed(null),
            AppModel.HandoffState.TakenOver("Pixel 7", "夜曲"),
            AppModel.HandoffState.TakeOverFailed("Pixel 7", "nope"),
        )
        listOf("zh" to zh, "en" to en).forEach { (tag, strings) ->
            samples.forEach { state ->
                val text = state.textOf(strings)
                assertTrue(text.isNotBlank(), "[$tag] $state 的文案是空串（界面上会是一片空白）")
            }
        }
    }

    @Test
    fun `the two languages actually differ`() {
        // 防止「英文实现里直接粘了中文」这种最常见的偷懒 ——
        // 它对结构守卫是不可见的（两边都非空、结构一致），只有出图能看出。
        val samples = listOf(
            AppModel.HandoffState.NoTrack,
            AppModel.HandoffState.Starting("Pixel 7"),
            AppModel.HandoffState.Done("Pixel 7"),
            AppModel.HandoffState.Failed("timeout"),
            AppModel.HandoffState.TakenOver("Pixel 7", "夜曲"),
            AppModel.HandoffState.TakeOverFailed("Pixel 7", "nope"),
        )
        samples.forEach { state ->
            val zhText = state.textOf(zh)
            val enText = state.textOf(en)
            assertTrue(
                zhText != enText,
                "[$state] 中英文案完全相同，英文实现很可能没翻：$zhText",
            )
        }
    }

    @Test
    fun `device name and reason reach the rendered text`() {
        // 参数真的进了句子（批次 1 的哨兵机制在 CpStringsTest 里查的是「函数层」，
        // 这里查的是「状态 → 文案」这一层的组装有没有把参数丢掉）。
        assertTrue(
            AppModel.HandoffState.Done("Living Room").textOf(en).contains("Living Room"),
            "设备名没进英文句子",
        )
        assertTrue(
            AppModel.HandoffState.Failed("connection refused").textOf(en).contains("connection refused"),
            "失败原因没进英文句子",
        )
        // 空原因要回落到「设备无响应」而不是留个空洞。
        val fallback = AppModel.HandoffState.Failed(null).textOf(en)
        assertTrue(fallback.isNotBlank() && !fallback.contains("null"), "空原因未回落到兜底文案：$fallback")
        assertEquals(
            en.standby.noResponse,
            fallback.substringAfter("Handoff failed: ").substringBefore(" — "),
            "空原因应回落到 noResponse",
        )
    }

    @Test
    fun `a blank reason is treated as no reason`() {
        // 对端可能回空串（老版本没填 message），不能把空串当「原因」显示出去。
        val text = AppModel.HandoffState.Failed("   ").textOf(en)
        assertTrue(text.isNotBlank())
        assertFalse(
            text.contains("   "),
            "空白原因被原样显示出来了：[$text]",
        )
    }
}
