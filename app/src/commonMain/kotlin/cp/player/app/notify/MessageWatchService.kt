package cp.player.app.notify

import cp.player.core.model.Contact
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 私信新消息的**本地轮询**。
 *
 * ## 为什么必须轮询
 *
 * 本仓库此前**没有任何私信轮询**：`AppModel.refreshUnreadMessages()` 只在
 * 「启动 / 登录成功 / 打开消息页」被调用，KDoc 明写「不做轮询」。
 * 要「新消息推送」就得自己建这条管线 —— 没有服务端推送可用（见下方限制）。
 *
 * ## 前置闸门（决定它「默认什么都不做」）
 *
 * [runOnce] 开头三连短路：**未登录 / 总开关关 / 该音源订阅数为 0** 一律直接返回，
 * 连一次请求都不发。这正是需求里「默认全不推送、不监听」的落点。
 *
 * ## 每轮只发一次请求
 *
 * `getContacts()` 一次就带回**全部**联系人的 `lastMessage` / `lastMessageTime` / `unreadCount`，
 * 所以按订阅过滤、判定、发通知全在本地完成，请求数与订阅人数无关。
 *
 * ## 限制（对用户必须诚实，见设置页文案）
 *
 * 进程死了一切停止。Android 后台无播放时进程可能被系统杀掉；桌面关窗即退出。
 * 真可靠的方案要么 `WorkManager`（最小 15 分钟）要么服务端推送，均不在本期。
 */
class MessageWatchService(
    private val prefs: MessageNotifyPrefs,
    /** 当前音源 id —— 订阅表与游标都按它隔离。 */
    private val providerId: () -> String,
    /** 前置条件（已登录且 uid 可用）。 */
    private val isReady: () -> Boolean,
    /** 一次拿回全部最近联系人（失败应返回空表，不要抛）。 */
    private val fetchContacts: suspend () -> List<Contact>,
    /** 真正发通知。 */
    private val post: (MessageNotification) -> Unit,
    private val now: () -> Long = { currentTimeMillis() },
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {

    private var job: Job? = null

    /**
     * 用户此刻正开着的会话。
     *
     * 由 `ChatContent` / `MessagesPane` 在进出组合时设置（**不是**由本服务自己推断）——
     * 「用户正在看谁」是 UI 才知道的事。开着的时候不弹这个人的通知（他已经看见了）。
     */
    var activePeerUid: Long? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** 启动轮询。**幂等** —— 重复调用只保留一个 job。 */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                runOnce()
                delay(intervalMs)
            }
        }
    }

    /** 停止轮询（登出 / 总开关关 / 订阅清零）。 */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * 立刻跑一轮，不等下一个 tick。
     *
     * 用在「刚订阅某人」「刚打开消息页」这类**用户正在等结果**的时刻 ——
     * 否则最多要等 [intervalMs] 才看到第一条提醒，观感像没生效。
     */
    fun tickNow(scope: CoroutineScope) {
        scope.launch { runOnce() }
    }

    /**
     * 跑一轮。返回本轮发出的通知条数（测试与日志用）。
     *
     * ⚠️ 这里刻意**不做**「同一联系人短时间内不重复」的节流：去重完全交给
     * [MessageNotifyPrefs.cursor] + [MessageNotifyPolicy] —— 游标推进即代表「已提醒过」，
     * 再加一层时间窗只会让两条规则互相打架。
     */
    internal suspend fun runOnce(): Int {
        if (!isReady()) return 0
        if (!prefs.isMasterEnabled()) return 0

        val provider = providerId()
        val subscribed = prefs.subscribedUids(provider)
        if (subscribed.isEmpty()) return 0

        val contacts = runCatching { fetchContacts() }.getOrDefault(emptyList())
        if (contacts.isEmpty()) return 0

        val nowMs = now()
        var sent = 0
        for (contact in contacts) {
            if (contact.userId !in subscribed) continue
            val cursor = prefs.cursor(provider, contact.userId)
            if (!MessageNotifyPolicy.shouldNotify(contact, cursor, activePeerUid, nowMs)) continue

            post(
                MessageNotification(
                    providerId = provider,
                    peerUid = contact.userId,
                    title = contact.nickname.ifBlank { UNKNOWN_NAME },
                    body = contact.lastMessage.orEmpty(),
                )
            )
            // 只有真发了才推进游标 —— 见 MessageNotifyPrefs.cursor 的 KDoc。
            contact.lastMessageTime?.let { prefs.setCursor(provider, contact.userId, it) }
            sent++
        }
        return sent
    }

    companion object {
        /** 轮询间隔。太短费电费流量，太长不像「推送」；45s 是两者的折中。 */
        const val DEFAULT_INTERVAL_MS = 45_000L

        const val UNKNOWN_NAME = "未知用户"
    }
}
