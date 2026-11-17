package cp.player.app.notify

import cp.player.core.model.Contact
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** 真正发通知。这个同步回调应快速返回；投递期间会与 stop() 互斥。 */
    private val post: (MessageNotification) -> Unit,
    private val now: () -> Long = { currentTimeMillis() },
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    /** 同步保护生命周期代际、job 与投递边界；网络请求期间不会持有该锁。 */
    private val lifecycleLock = Any()
    private var job: Job? = null
    private var runGeneration = 0L
    private val runOnceMutex = Mutex()

    /**
     * 用户此刻正开着的会话。
     *
     * 由 `ChatContent` / `MessagesPane` 在进出组合时设置（**不是**由本服务自己推断）——
     * 「用户正在看谁」是 UI 才知道的事。开着的时候不弹这个人的通知（他已经看见了）。
     */
    private var _activePeerUid: Long? = null
    var activePeerUid: Long?
        get() = synchronized(lifecycleLock) { _activePeerUid }
        set(value) { synchronized(lifecycleLock) { _activePeerUid = value } }

    val isRunning: Boolean
        get() = synchronized(lifecycleLock) { job?.isActive == true }

    /** 启动轮询。**幂等** —— 重复调用只保留一个 job。 */
    fun start(scope: CoroutineScope) {
        synchronized(lifecycleLock) {
            startLocked(scope)
        }
    }

    /** Reconcile the polling job and its gates under the same lock as preference updates. */
    fun sync(scope: CoroutineScope) {
        synchronized(lifecycleLock) {
            val shouldRun = isReady() && prefs.isMasterEnabled() &&
                prefs.subscribedCount(providerId()) > 0
            if (shouldRun) startLocked(scope) else stopLocked()
        }
    }

    /** Serialize a master-toggle write with the final notification-delivery check. */
    fun setMasterEnabled(enabled: Boolean) {
        synchronized(lifecycleLock) { prefs.setMasterEnabled(enabled) }
    }

    /** Enable the master gate and update one subscription as one delivery-atomic change. */
    fun setSubscription(provider: String, peerUid: Long, enabled: Boolean): Int =
        synchronized(lifecycleLock) {
            if (enabled && !prefs.isMasterEnabled()) prefs.setMasterEnabled(true)
            prefs.setSubscribed(provider, peerUid, enabled)
        }

    private fun startLocked(scope: CoroutineScope) {
        if (job?.isActive == true) return
        val generation = ++runGeneration
        job = scope.launch {
            while (isGenerationCurrent(generation)) {
                try {
                    runOnce(generation)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // A single failed tick must not permanently stop future polling.
                }
                delay(intervalMs)
            }
        }
    }

    /** 停止轮询（登出 / 总开关关 / 订阅清零）。 */
    fun stop() {
        synchronized(lifecycleLock) { stopLocked() }
    }

    private fun stopLocked() {
        runGeneration += 1L
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
        val generation = currentGeneration()
        scope.launch { runOnce(generation) }
    }

    /**
     * 跑一轮。返回本轮发出的通知条数（测试与日志用）。
     *
     * ⚠️ 这里刻意**不做**「同一联系人短时间内不重复」的节流：去重完全交给
     * [MessageNotifyPrefs.cursor] + [MessageNotifyPolicy] —— 游标推进即代表「已提醒过」，
     * 再加一层时间窗只会让两条规则互相打架。
     */
    internal suspend fun runOnce(): Int = runOnce(currentGeneration())

    private suspend fun runOnce(expectedGeneration: Long): Int = runOnceMutex.withLock {
        if (!isGenerationCurrent(expectedGeneration) || !isReady()) return@withLock 0
        if (!prefs.isMasterEnabled()) return@withLock 0

        val provider = providerId()
        val subscribed = prefs.subscribedUids(provider)
        if (subscribed.isEmpty()) return@withLock 0

        val contacts = try {
            fetchContacts()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
        if (!isGenerationCurrent(expectedGeneration) || !isReady() ||
            !prefs.isMasterEnabled() || providerId() != provider
        ) {
            return@withLock 0
        }
        val currentSubscriptions = prefs.subscribedUids(provider)
        if (contacts.isEmpty() || currentSubscriptions.isEmpty()) return@withLock 0

        val nowMs = now()
        var sent = 0
        for (contact in contacts) {
            if (!isGenerationCurrent(expectedGeneration)) return@withLock sent
            if (contact.userId !in subscribed || contact.userId !in currentSubscriptions) continue
            val cursor = prefs.cursor(provider, contact.userId)
            if (!MessageNotifyPolicy.shouldNotify(contact, cursor, activePeerUid, nowMs)) continue

            val delivered = try {
                deliverIfStillCurrent(expectedGeneration, provider, contact)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                continue
            }
            if (!delivered) return@withLock sent

            // 只有真发了才推进游标 —— 见 MessageNotifyPrefs.cursor 的 KDoc。
            contact.lastMessageTime?.let { prefs.setCursor(provider, contact.userId, it) }
            sent++
        }
        sent
    }

    /**
     * 在 [lifecycleLock] 内做最终投递检查。
     *
     * 单独成方法而不是把 `synchronized` 留在 suspend 的 [runOnce] 里：GraalVM Native Image
     * 25.3 解析「循环 + suspend 方法内 synchronized + 异常分派」时会命中
     * `unexpected non-null predecessor ... MonitorExit` 的分析器断言。抽成非挂起方法后，
     * monitor 只出现在结构化直线控制流里，instrumented 与生产镜像都能过分析。
     */
    private fun deliverIfStillCurrent(
        expectedGeneration: Long,
        provider: String,
        contact: Contact,
    ): Boolean = synchronized(lifecycleLock) {
        if (expectedGeneration != runGeneration ||
            !isReady() ||
            !prefs.isMasterEnabled() ||
            providerId() != provider ||
            contact.userId !in prefs.subscribedUids(provider) ||
            _activePeerUid == contact.userId
        ) {
            false
        } else {
            // Preference mutations, active-chat changes and stop() use this same lock.
            post(
                MessageNotification(
                    providerId = provider,
                    peerUid = contact.userId,
                    title = contact.nickname.ifBlank { UNKNOWN_NAME },
                    body = contact.lastMessage.orEmpty(),
                )
            )
            true
        }
    }

    private fun currentGeneration(): Long = synchronized(lifecycleLock) { runGeneration }

    private fun isGenerationCurrent(generation: Long): Boolean =
        synchronized(lifecycleLock) { generation == runGeneration }

    companion object {
        /** 轮询间隔。太短费电费流量，太长不像「推送」；45s 是两者的折中。 */
        const val DEFAULT_INTERVAL_MS = 45_000L

        const val UNKNOWN_NAME = "未知用户"
    }
}
