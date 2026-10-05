package cp.player.app.notify

import cp.player.core.model.Contact
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 私信通知的「该不该响」判定。
 *
 * 这是本功能里**唯一一处容易出错、又最值得钉住**的逻辑：轮询、平台通知、UI 都可能写错，
 * 但只有这里写错会表现为「该提醒的不提醒 / 不该提醒的乱提醒」，而且只在真机上才看得出来。
 * 所以它是纯函数，这里逐条把边界钉死。
 */
class MessageNotifyPolicyTest {

    private fun contact(
        userId: Long = 42L,
        unread: Int = 1,
        lastAt: Long? = 1_000L,
    ) = Contact(
        userId = userId,
        nickname = "某人",
        avatarUrl = "",
        lastMessage = "在吗",
        lastMessageTime = lastAt,
        unreadCount = unread,
    )

    private val now = 10_000L

    @Test
    fun `有未读的对方消息且没有提醒过时应当提醒`() {
        assertTrue(MessageNotifyPolicy.shouldNotify(contact(), cursorMs = null, activePeerUid = null, nowMs = now))
    }

    @Test
    fun `没有未读时不提醒`() {
        // ⚠️ 这条是「不要对自己的消息误报」的守门员：`lastMessage` 可能是我自己发的最后一条，
        // 时间照样前进，只有 unreadCount 才能区分。
        assertFalse(
            MessageNotifyPolicy.shouldNotify(contact(unread = 0), cursorMs = null, activePeerUid = null, nowMs = now)
        )
    }

    @Test
    fun `游标已经追上最后一条时不重复提醒`() {
        assertFalse(
            MessageNotifyPolicy.shouldNotify(contact(lastAt = 1_000L), cursorMs = 1_000L, activePeerUid = null, nowMs = now)
        )
    }

    @Test
    fun `比游标更新时应当提醒`() {
        assertTrue(
            MessageNotifyPolicy.shouldNotify(contact(lastAt = 2_000L), cursorMs = 1_000L, activePeerUid = null, nowMs = now)
        )
    }

    @Test
    fun `正在看这个会话时不提醒`() {
        assertFalse(
            MessageNotifyPolicy.shouldNotify(contact(userId = 42L), cursorMs = null, activePeerUid = 42L, nowMs = now)
        )
        // 看的是**别人**的会话 ⇒ 照常提醒。
        assertTrue(
            MessageNotifyPolicy.shouldNotify(contact(userId = 42L), cursorMs = null, activePeerUid = 7L, nowMs = now)
        )
    }

    @Test
    fun `太久没提醒过的积压消息不再补炸`() {
        val stale = now - MessageNotifyPolicy.STALE_WINDOW_MS - 1
        assertFalse(
            MessageNotifyPolicy.shouldNotify(contact(lastAt = stale), cursorMs = null, activePeerUid = null, nowMs = now)
        )
        // 窗口内（刚好等于边界）仍然提醒。
        val edge = now - MessageNotifyPolicy.STALE_WINDOW_MS
        assertTrue(
            MessageNotifyPolicy.shouldNotify(contact(lastAt = edge), cursorMs = null, activePeerUid = null, nowMs = now)
        )
    }

    @Test
    fun `拿不到最后消息时间时不提醒`() {
        assertFalse(
            MessageNotifyPolicy.shouldNotify(contact(lastAt = null), cursorMs = null, activePeerUid = null, nowMs = now)
        )
    }
}
