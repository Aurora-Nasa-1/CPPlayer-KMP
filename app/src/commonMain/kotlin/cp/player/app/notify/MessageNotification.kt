package cp.player.app.notify

/**
 * 一条待发的私信通知。
 *
 * @param providerId 来源音源（点击通知回跳时要连音源一起带上 —— 私信是「按音源 + 按账号」的，
 *   只带 uid 在切过音源之后会跳错人）
 * @param peerUid 发消息的人
 * @param title 通知标题（联系人昵称）
 * @param body 通知正文（消息内容）
 */
data class MessageNotification(
    val providerId: String,
    val peerUid: Long,
    val title: String,
    val body: String,
) {
    /**
     * 系统通知的去重键。
     *
     * 同一会话永远是同一个 key ⇒ **后来的覆盖先前的**，不会在通知栏里堆一排同名通知；
     * 不同会话 key 不同 ⇒ 各占一条，不会互相顶掉。
     */
    val key: String get() = keyOf(providerId, peerUid)

    companion object {
        /** 由 (音源, 会话对象) 直接算出 key —— 进会话要撤销通知时用得到（那里没有 [MessageNotification]）。 */
        fun keyOf(providerId: String, peerUid: Long): String = "msg_${providerId}_$peerUid"
    }
}
