package cp.player.app.ui.a2ui

import cp.player.app.i18n.AiStringsZh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A2UI 动作参数提取的回归测试。
 *
 * 这里**不启动** `A2uiActionHandler`（它会直接驱动播放器与导航），只覆盖它读事件
 * `context` 的那一层：官方事件里的 `context` 是 `Map<String, Any?>`，嵌套结构随
 * 模型/组件不同而变化 —— 这是最容易静默失配、且界面看起来「按钮点了没反应」的地方。
 *
 * 这些函数是 `internal` 的，测试放在同一模块的 desktopTest 源集里，能看到它们。
 */
class A2uiActionHandlerTest {

    @Test
    fun `media ids are read from flat and nested context shapes`() {
        val flat = mapOf<String, Any?>("mediaId" to "netease://song/1")
        assertEquals(listOf("netease://song/1"), flat.mediaIds("mediaId"))

        val list = mapOf<String, Any?>("mediaIds" to listOf("a", "b", " a "))
        assertEquals(listOf("a", "b"), list.mediaIds("mediaIds"))

        val objects = mapOf<String, Any?>(
            "tracks" to listOf(
                mapOf("id" to "track-1"),
                mapOf("mediaId" to "track-2"),
                mapOf("trackId" to "track-3"),
            ),
        )
        assertEquals(
            listOf("track-1", "track-2", "track-3"),
            objects.mediaIds("tracks"),
        )
    }

    @Test
    fun `blank and unrelated values never produce a media id`() {
        val context = mapOf<String, Any?>(
            "mediaId" to "   ",
            "title" to "不是 id",
            "tracks" to listOf(mapOf("name" to "无 id 的对象")),
        )

        assertTrue(context.mediaIds("mediaId", "tracks").isEmpty())
    }

    @Test
    fun `text values prefer the first non blank key`() {
        val context = mapOf<String, Any?>(
            "query" to " ",
            "q" to "夜雨",
            "preset" to "vocal",
        )

        assertEquals("夜雨", context.textValue("query", "q"))
        assertEquals("vocal", context.textValue("preset"))
        assertNull(context.textValue("missing"))
    }

    @Test
    fun `eq preset covers canonical english and chinese aliases`() {
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), eqPresetGains("flat"))
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), eqPresetGains(" 平直 "))
        assertEquals(listOf(-2f, -1f, 3f, 2f, 0f), eqPresetGains("VOCAL"))
        assertEquals(listOf(-2f, -1f, 3f, 2f, 0f), eqPresetGains("人声"))
        assertEquals(listOf(3f, 2f, 0f, -1f, -2f), eqPresetGains("night"))
        assertEquals(listOf(3f, 2f, 0f, -1f, -2f), eqPresetGains("夜间"))
    }

    @Test
    fun `unknown eq preset returns null instead of resetting the sound field`() {
        assertNull(eqPresetGains("cinema"))
        assertNull(eqPresetGains(""))
    }

    @Test
    fun `real dj surface carries play and eq contexts the handler can read`() {
        val track = A2uiTrackRef(
            mediaId = "netease://song/42",
            title = "测试曲目",
            artist = "测试歌手",
            album = null,
            coverUrl = null,
            durationMs = 120_000,
        )
        val messages = A2uiAgent.djMessages(track, emptyList(), AiStringsZh)
        val update = messages[1]

        // 事件 context 由我们自己的 SurfaceComponents 生成，确认参数键名与处理器一致。
        assertTrue(update.contains("\"name\":\"play_track\""))
        assertTrue(update.contains("\"mediaId\":\"netease://song/42\""))
        assertTrue(update.contains("\"name\":\"set_eq_preset\""))
        assertTrue(update.contains("\"preset\":\"vocal\""))
    }
}
