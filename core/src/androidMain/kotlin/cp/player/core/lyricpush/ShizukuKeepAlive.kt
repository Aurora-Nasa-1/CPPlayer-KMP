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
import kotlinx.coroutines.SupervisorJob
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
 * 重复请求在连接回调到达前也会合并；绑定失败、权限暂不可用、连接断开时按轮次重试。
 * 所有 Shizuku API 调用与 listener 清理都在主线程执行；关闭后迟到的 binder/连接回调
 * 不会重新启用功能。用户手动授权成功后由调用方再次调 [ensureBound]。
 */
object ShizukuKeepAlive {

    private const val TAG = "XmsfKeepAlive"
    private const val RETRY_DELAY_MS = 5_000L
    private const val SERVICE_TAG = "cp_player_keepalive"

    /** Shizuku binder/listener APIs 和 ServiceConnection 均通过主线程 scope 串行化。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var appContext: Context? = null

    private val argsLock = Any()
    private var cachedArgs: Shizuku.UserServiceArgs? = null
    private var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null

    private lateinit var bindingController: KeepAliveBindingController

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Shizuku user service connected")
            bindingController.onServiceConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Shizuku user service disconnected")
            bindingController.onServiceDisconnected()
        }
    }

    private val backend = object : KeepAliveBindingBackend {
        override fun isInstalled(): Boolean = appContext?.let(ShizukuPermissions::isInstalled) ?: false

        override fun hasPermission(): Boolean = ShizukuPermissions.isGranted()

        override fun addBinderListener(listener: () -> Unit): Boolean {
            val platformListener = Shizuku.OnBinderReceivedListener { listener() }
            binderReceivedListener = platformListener
            val added = ShizukuPermissions.addBinderReceivedListener(platformListener)
            if (!added) binderReceivedListener = null
            return added
        }

        override fun removeBinderListener(): Boolean {
            val listener = binderReceivedListener ?: return true
            val removed = ShizukuPermissions.removeBinderReceivedListener(listener)
            if (removed) binderReceivedListener = null
            return removed
        }

        override fun bindUserService() {
            val context = appContext ?: error("Shizuku keepalive has no application context")
            Shizuku.bindUserService(argsFor(context), connection)
        }

        override fun unbindUserService() {
            val args = cachedArgs ?: return
            Shizuku.unbindUserService(args, connection, true)
        }
    }

    init {
        bindingController = KeepAliveBindingController(
            scope = scope,
            backend = backend,
            retryDelayMs = RETRY_DELAY_MS,
            onFailure = { Log.w(TAG, "Shizuku keepalive operation failed", it) },
        )
    }

    /** 启动或确保绑定；重复调用安全，调用线程会自动切换到主线程。 */
    fun ensureBound(context: Context) {
        appContext = context.applicationContext
        bindingController.activate()
    }

    /** 关闭功能或退出时禁用 keepalive；调用线程会自动切换到主线程清理资源。 */
    fun unbind() {
        bindingController.deactivate()
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
