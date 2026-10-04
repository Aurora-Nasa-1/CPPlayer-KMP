package cp.player.core.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 邀请链接的拼装与解析。
 *
 * 为什么这几条值得单测：**邀请没有官方 API**（网易云 380 个端点里没有「发出邀请」），
 * 整个邀请闭环靠的就是这条链接。链接格式一旦漂移，表现是「对方点了没反应」——
 * 一个既没有错误码、也没有日志的失败。所以把它钉死在测试里。
 */
class ListenTogetherInviteTest {

    /** 实测真实 roomId 形态：32 位 hex + 下划线 + 创建时间戳。 */
    private val realRoomId = "af4e21669699a2a538fdfb8b205f9490_1791075851"

    @Test
    fun `拼出的链接与官方格式一致`() {
        val url = ListenTogetherInvite.buildShareUrl(realRoomId, inviterId = 9084388061L)

        assertEquals(
            "https://st.music.163.com/listen-together/share/?" +
                "roomId=$realRoomId&inviterId=9084388061",
            url,
        )
    }

    @Test
    fun `带 songId 时 songId 排在最前`() {
        val url = ListenTogetherInvite.buildShareUrl(realRoomId, 9084388061L, songId = "347230")

        assertEquals(
            "https://st.music.163.com/listen-together/share/?" +
                "songId=347230&roomId=$realRoomId&inviterId=9084388061",
            url,
        )
    }

    @Test
    fun `自己拼的链接能自己解析回来`() {
        val url = ListenTogetherInvite.buildShareUrl(realRoomId, 9084388061L, songId = "347230")

        val parsed = assertNotNull(ListenTogetherInvite.parse(url))
        assertEquals(realRoomId, parsed.roomId)
        assertEquals(9084388061L, parsed.inviterId)
        assertEquals("347230", parsed.songId)
    }

    /**
     * 对方多半是把链接**贴进私信正文**发过来的（官方那条 `inbox_invite` 就是这么投递的）。
     *
     * 所以解析必须接受「夹在文本里的链接」而不是只认纯 URL —— 而且链接后面跟的
     * 往往是引号（消息体是 JSON）而不是 `&`，取值终止符要覆盖这种情况。
     */
    @Test
    fun `能从私信正文里抠出邀请参数`() {
        val message = "邀请你一起听 https://st.music.163.com/listen-together/share/?" +
            "songId=347230&roomId=$realRoomId&inviterId=9084388061 快来！"

        val parsed = assertNotNull(ListenTogetherInvite.parse(message), )
        assertEquals(realRoomId, parsed.roomId)
        assertEquals(9084388061L, parsed.inviterId)
    }

    @Test
    fun `链接被包在 JSON 引号里也能解析`() {
        val jsonBody = """{"msg":"一起听 https://st.music.163.com/listen-together/share/?roomId=$realRoomId&inviterId=9084388061","type":"text"}"""

        val parsed = assertNotNull(ListenTogetherInvite.parse(jsonBody))
        assertEquals(realRoomId, parsed.roomId)
        // 终止符若不含引号，这里会解析成 "9084388061\" 从而 toLongOrNull 失败
        assertEquals(9084388061L, parsed.inviterId)
    }

    /**
     * 缺 `inviterId` 必须返回 null 而不是半成品。
     *
     * `accept` 需要**成对**的 roomId + inviterId；给个只有 roomId 的对象，
     * 调用方会一路走到 accept 才失败，而那时错误只剩
     * `API error (code=488): Unknown error` —— 完全不可归因。
     */
    @Test
    fun `缺 inviterId 时拒绝解析`() {
        assertNull(ListenTogetherInvite.parse("roomId=$realRoomId"))
        assertNull(ListenTogetherInvite.parse("https://st.music.163.com/listen-together/share/?roomId=$realRoomId"))
    }

    @Test
    fun `inviterId 不是数字时拒绝解析`() {
        assertNull(ListenTogetherInvite.parse("roomId=$realRoomId&inviterId=abc"))
    }

    @Test
    fun `毫不相干的文本解析为 null`() {
        assertNull(ListenTogetherInvite.parse("今天天气不错"))
        assertNull(ListenTogetherInvite.parse(""))
    }

    @Test
    fun `参数顺序颠倒也能解析`() {
        val parsed = assertNotNull(
            ListenTogetherInvite.parse("?inviterId=9084388061&roomId=$realRoomId"),
        )
        assertEquals(realRoomId, parsed.roomId)
        assertEquals(9084388061L, parsed.inviterId)
    }
}
