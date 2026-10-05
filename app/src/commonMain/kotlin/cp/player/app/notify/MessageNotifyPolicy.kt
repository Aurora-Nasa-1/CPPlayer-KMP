package cp.player.app.notify

import cp.player.core.model.Contact

/**
 * 「这条联系人该不该弹通知」的**纯判定**。
 *
 * 刻意做成不碰 IO、不碰平台、不读全局状态的纯函数 —— 与 `CookieLogin` 同一取舍：
 * 通知的「什么时候该响」是本功能里唯一容易出错、也最值得单测的一环，
 * 混进协程与网络里就只能靠真机手测了。
 */
object MessageNotifyPolicy {

    /**
     * 超过这个时长没提醒过的消息**不再补提醒**。
     *
     * 存在的意义是冷启动：用户几天没开应用，一开就有几十条未读，
     * 不加这道闸就会一次炸出几十条通知。24h 是「还值得提醒」的粗线。
     */
    const val STALE_WINDOW_MS = 24L * 60 * 60 * 1000

    /**
     * @param contact 轮询拿到的最新联系人状态
     * @param cursorMs 上次**已提醒过**的 `lastMessageTime`（`null` = 从未提醒过）
     * @param activePeerUid 用户此刻正开着的会话（双栏右栏 / 整页聊天）；没有则 null
     * @param nowMs 当前时间（注入而非 `System.currentTimeMillis()`，否则没法测）
     */
    fun shouldNotify(
        contact: Contact,
        cursorMs: Long?,
        activePeerUid: Long?,
        nowMs: Long,
    ): Boolean {
        // 1. 没有「未读的对方消息」→ 不打扰。
        //
        // ⚠️ 这里**不能**退化成「比 lastMessageTime 有没有前进」：`Contact.lastMessage`
        // 可能是**我自己**刚发出去的最后一条，时间同样会前进。上游的 `newMsgCount`
        // （`unreadCount`）是服务端口径的「未读的对方消息」，只有它才能保证
        // 「只对别人发来的消息提醒」；顺带白拿了「我在别的客户端读过」——
        // 读过即清零，这边自然不再提醒。
        if (contact.unreadCount <= 0) return false

        // 2. 用户正开着这个会话 → 不打扰（他已经看见了，弹通知是噪音）。
        if (activePeerUid != null && contact.userId == activePeerUid) return false

        val lastAt = contact.lastMessageTime ?: return false

        // 3. 没有新进展 → 不重复提醒（同一轮/下一轮都靠这个去重，不需要额外的已通知集合）。
        if (cursorMs != null && lastAt <= cursorMs) return false

        // 4. 太旧 → 不补炸（见 STALE_WINDOW_MS）。
        if (nowMs - lastAt > STALE_WINDOW_MS) return false

        return true
    }
}
