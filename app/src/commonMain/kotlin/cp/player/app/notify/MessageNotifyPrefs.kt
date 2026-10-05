package cp.player.app.notify

import cp.player.core.util.SettingsStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 私信通知偏好：**订阅表 + 提醒水位 + 引导标志**。
 *
 * ## 为什么单独一个类
 *
 * 这三样东西被**两个互不相识的调用方**同时读写：UI（右键 / 长按开关、首次引导）
 * 与后台轮询（[MessageWatchService]）。两边各写各的 key 必然漂移，所以落盘只有一个入口。
 *
 * ## 键必须带 providerId
 *
 * 私信是「按音源 + 按账号」的（cookie 存的是 `cookie_$providerId`，见 `ProviderManager`）。
 * 不带 providerId 的话，切到另一个音源会把别人的订阅关系读成自己的。
 *
 * ## 为什么订阅要额外存一份「索引」
 *
 * [SettingsStorage] 只有 `getString/putString/contains`，**没有 key 枚举** ——
 * 靠扫描 key 反查「订了谁」是做不到的。所以订阅关系以 **JSON 数组**整表存在
 * `msg_notify_subs_<providerId>` 下；每联系人一条的写法（`..._<uid>`）虽然可读，
 * 却回答不了「这个音源订了哪些人」这个轮询每轮都要问的问题。
 *
 * ⚠️ 索引与水位是**两个独立命名空间**，不要互相推导：水位在取消订阅后**不清零**，
 * 这样用户重新订阅同一个人时不会把历史消息当成新消息炸一遍（见 [MessageNotifyPolicy]）。
 */
class MessageNotifyPrefs(private val storage: SettingsStorage) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ======================== 首次引导 ========================

    /**
     * 消息页的「默认不打扰 + 右键/长按开启」引导是否已经看过。
     *
     * 刻意与全局 `onboarding_done` 分开：那是「装音源 / 登录」级别的门槛，
     * 本引导是**消息页的上下文说明**，用户可能早就过完新手引导了才第一次点进消息页。
     */
    fun isGuideDone(): Boolean = storage.getString(KEY_GUIDE_DONE)?.toBooleanStrictOrNull() ?: false

    /** 看完引导立刻落盘 —— 路由页 pop 回来会重跑 `LaunchedEffect`，靠内存标志挡不住。 */
    fun setGuideDone(done: Boolean = true) {
        storage.putString(KEY_GUIDE_DONE, done.toString())
    }

    // ======================== 总开关 ========================

    /**
     * 总开关。**默认 false** —— 满足「默认全不推送 / 不监听」。
     *
     * 关着的时候 [MessageWatchService] 连一轮轮询都不跑（不是「跑了但不发通知」），
     * 所以默认状态下没有任何额外的私信请求。
     */
    fun isMasterEnabled(): Boolean = storage.getString(KEY_MASTER)?.toBooleanStrictOrNull() ?: false

    fun setMasterEnabled(enabled: Boolean) {
        storage.putString(KEY_MASTER, enabled.toString())
    }

    // ======================== 订阅表 ========================

    /** 该音源下被订阅的 uid 集合（索引表解析失败按「空」处理，不让坏数据卡住轮询）。 */
    fun subscribedUids(providerId: String): Set<Long> {
        val raw = storage.getString(subsKey(providerId)) ?: return emptySet()
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return emptySet()
        return array.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.toSet()
    }

    fun isSubscribed(providerId: String, uid: Long): Boolean = uid in subscribedUids(providerId)

    /**
     * 设置某人是否订阅。
     *
     * @return 设置完成后**该音源剩余的订阅数** —— 调用方据此决定要不要起停轮询
     *   （0 就该停，否则白白占连接）。
     */
    fun setSubscribed(providerId: String, uid: Long, subscribed: Boolean): Int {
        val current = subscribedUids(providerId).toMutableSet()
        if (subscribed) current.add(uid) else current.remove(uid)
        storage.putString(subsKey(providerId), JsonArray(current.map { JsonPrimitive(it) }).toString())
        return current.size
    }

    /** 订阅数（轮询的前置判据之一）。 */
    fun subscribedCount(providerId: String): Int = subscribedUids(providerId).size

    // ======================== 提醒水位（游标） ========================

    /**
     * 上一次**已经提醒过**的 `lastMessageTime`。
     *
     * `null` = 从未提醒过（例如刚订阅）。注意**不是**「最后读到的消息时间」——
     * 语义是「到此为止已经打扰过用户了」，所以只在真的发了通知之后才推进。
     */
    fun cursor(providerId: String, uid: Long): Long? =
        storage.getString(cursorKey(providerId, uid))?.toLongOrNull()

    fun setCursor(providerId: String, uid: Long, value: Long) {
        storage.putString(cursorKey(providerId, uid), value.toString())
    }

    private fun subsKey(providerId: String) = "$KEY_SUBS_PREFIX$providerId"
    private fun cursorKey(providerId: String, uid: Long) = "$KEY_CURSOR_PREFIX${providerId}_$uid"

    companion object {
        const val KEY_GUIDE_DONE = "msg_notify_guide_done"
        const val KEY_MASTER = "msg_notify_master"
        const val KEY_SUBS_PREFIX = "msg_notify_subs_"
        const val KEY_CURSOR_PREFIX = "msg_notify_cursor_"
    }
}
