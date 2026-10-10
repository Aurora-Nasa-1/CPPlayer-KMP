/*
 * 新增文件（非直接移植）。
 * 移植指南见 XIAOMI_SUPER_ISLAND_PORTING.md §4.2「daemon UserService 是 keepalive 的关键」。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 常驻的 Shizuku UserService 本体。
 *
 * ### 为什么要有它
 * Halcyon / NeriPlayer 稳定的原因**不是**「权限永久保存」，而是有一个长期存在的
 * Shizuku UserService（移植指南 §4.2）。Shizuku 的 binder 会在 server 重启、
 * 系统回收等情况下断掉，权限状态也随之需要重新走一次注册 —— 一个 daemon UserService
 * 让这条连接在后台维持住，避免「冷启动失效」。
 *
 * ### 契约（改这个类前先读）
 * - 由 **Shizuku 服务器在另一个进程里按全限定名反射实例化**，因此：
 *   1. 类名不能混淆（见 app-android/proguard-rules.pro 的 keep 规则）；
 *   2. 构造函数必须是**单个 `Context` 参数**（移植指南给的写法）；
 *   3. 必须继承 [Binder]，否则 `bindUserService` 拿不到可用的 IBinder。
 * - 它**不做任何事**：这是刻意的。它的价值在于「存在」而不是「干活」——
 *   防火墙走的是 wrapped binder（见 [XmsfFirewall]），不需要经过 UserService。
 */
class ShizukuKeepAliveService(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : Binder()

/**
 * daemon UserService 的绑定管理。
 *
 * 绑定失败或 `onServiceDisconnected()` 之后延迟重试；**权限未授予时不请求**，
 * 只安排重试（移植指南 §4.2）。用户在设置页手动授权成功后由调用方再调一次
 * [ensureBound]（`ShizukuPermissions.requestPermission` 的回调里已接上）。
 */
object ShizukuKeepAlive {

    private const val TAG = "XmsfKeepAlive"
    private const val RETRY_DELAY_MS = 5_000L
    private const val SERVICE_TAG = "cp_player_keepalive"

    /** 绑定回调必须在主线程；Shizuku 的 API 也要求主线程调用。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var bound = false

    @Volatile
    private var retryJob: Job? = null

    @Volatile
    private var listenerRegistered = false

    private val argsLock = Any()
    private var cachedArgs: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bound = true
            Log.d(TAG, "Shizuku user service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            Log.d(TAG, "Shizuku user service disconnected")
            scheduleRetry()
        }
    }

    /** Shizuku 重启后 binder 会重新到达 —— 那时必须重绑，否则连接永远停在断开状态。 */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received; rebinding keepalive")
        bound = false
        appContext?.let { ensureBound(it) }
    }

    /** 绑定（幂等）。权限未授予 / 未装 Shizuku 时只安排重试，不弹窗。 */
    fun ensureBound(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!ShizukuPermissions.isInstalled(ctx)) return
        if (!listenerRegistered) {
            ShizukuPermissions.addBinderReceivedListener(binderReceivedListener)
            listenerRegistered = true
        }
        if (!ShizukuPermissions.isGranted()) {
            scheduleRetry()
            return
        }
        if (bound) return
        runCatching { Shizuku.bindUserService(argsFor(ctx), connection) }.onFailure {
            Log.w(TAG, "bindUserService failed", it)
            scheduleRetry()
        }
    }

    /** 解绑（进程退出 / 用户关闭功能时）。 */
    fun unbind() {
        retryJob?.cancel()
        retryJob = null
        val args = cachedArgs ?: return
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        bound = false
    }

    private fun scheduleRetry() {
        if (appContext == null) return
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(RETRY_DELAY_MS)
            appContext?.let { ensureBound(it) }
        }
    }

    private fun argsFor(context: Context): Shizuku.UserServiceArgs = synchronized(argsLock) {
        cachedArgs ?: Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuKeepAliveService::class.java.name),
        )
            .tag(SERVICE_TAG)
            .daemon(true)
            .processNameSuffix("shizuku")
            .debuggable(false)
            .version(1)
            .also { cachedArgs = it }
    }
}
