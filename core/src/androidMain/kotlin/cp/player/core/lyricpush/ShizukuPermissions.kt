/*
 * 新增文件（非直接移植）。
 * 移植指南见 XIAOMI_SUPER_ISLAND_PORTING.md §4「Shizuku 授权、keepalive 与重复弹窗」。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku 授权与 binder 状态。
 *
 * ### 铁律：播放路径里**绝不**请求权限
 * 歌词更新频率很高。任何「检查失败就 `requestPermission()`」的写法都会造成**反复弹窗**
 * （移植指南 §4.1 / §11 都把这条列为最常见的错误实现）。因此这里把 API 分成两类：
 *
 * - 播放 / 歌词回调只调 [isGranted] / [isGrantedWhenReady] —— **只检查，不请求**；
 * - [requestPermission] 是唯一会弹窗的入口，只允许由设置页按钮触发。
 */
object ShizukuPermissions {

    private const val TAG = "XmsfShizuku"

    /** Shizuku Manager 的包名。 */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** 权限请求码（任意，只要与其它请求不撞）。 */
    const val REQUEST_CODE = 0x5358

    /**
     * 「正在请求中」的闸门。
     *
     * 移植指南要求用 Mutex 串行化，这里用无锁 CAS：本方法**只允许从主线程调用**
     * （设置页点击），主线程上不存在真正的并发，CAS 足够挡住「连点两下按钮」
     * 与「多个协程几乎同时调」这两种情况，且不需要额外的协程作用域。
     */
    private val requesting = AtomicBoolean(false)

    /** 当前等待结果的回调（单播；结果到达即置空）。 */
    @Volatile
    private var pendingResult: ((Boolean) -> Unit)? = null

    /**
     * 权限结果监听器。
     *
     * **常驻注册**而不是「注册-摘除」：摘除要求持有同一个实例引用，而把它存进
     * 另一个属性再自引用初始化很别扭；常驻一个监听器没有任何代价（回调里按
     * [pendingResult] 是否为空过滤），也避免了「摘除失败 ⇒ 下次多收一次回调」。
     */
    private val resultListener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code != REQUEST_CODE) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        requesting.set(false)
        val callback = pendingResult
        pendingResult = null
        Log.d(TAG, "permission result: granted=$granted")
        callback?.invoke(granted)
    }

    @Volatile
    private var resultListenerRegistered = false

    /** Shizuku Manager 是否已安装（Android 11+ 需清单里的 `<queries>` 才可见）。 */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Shizuku 服务是否可达。 */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * 权限是否已授予。
     *
     * **只检查，不请求。** 未装 / 未启动 Shizuku 时 `pingBinder()` 为 false ⇒ 返回 false。
     */
    fun isGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 等待 binder 就绪后再判权限（冷启动场景）。
     *
     * 移植指南 §4.1：播放路径可以**等** binder 就绪，但 DENIED 之后必须直接返回，
     * 不能转去请求。最多等 [timeoutMs]（默认 5s，与「20 × 250ms」等价）。
     */
    suspend fun isGrantedWhenReady(timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!isBinderAlive() && System.currentTimeMillis() < deadline) {
            delay(250)
        }
        return isGranted()
    }

    /**
     * 显式请求权限（**唯一允许弹窗的入口**，只应由设置页按钮调用）。
     *
     * @param onResult 结果回调（主线程）。为 null 时仍会发起请求。
     */
    fun requestPermission(onResult: ((Boolean) -> Unit)? = null) {
        if (isGranted()) {
            onResult?.invoke(true)
            return
        }
        if (!isBinderAlive()) {
            Log.w(TAG, "requestPermission ignored: Shizuku binder is not alive")
            onResult?.invoke(false)
            return
        }
        if (!requesting.compareAndSet(false, true)) {
            Log.d(TAG, "requestPermission ignored: already requesting")
            return
        }
        ensureResultListener()
        pendingResult = onResult
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }.onFailure {
            Log.w(TAG, "requestPermission failed", it)
            requesting.set(false)
            pendingResult = null
            onResult?.invoke(false)
        }
    }

    private fun ensureResultListener() {
        if (resultListenerRegistered) return
        val ok = runCatching {
            Shizuku.addRequestPermissionResultListener(resultListener)
            true
        }.getOrDefault(false)
        if (ok) {
            resultListenerRegistered = true
        } else {
            Log.w(TAG, "Failed to register permission result listener")
        }
    }

    /**
     * binder 就绪时回调一次（sticky：注册时若已就绪会立即触发）。
     *
     * 由 [ShizukuKeepAlive] 使用：它要「binder 一就绪就重连」，而不是等下一次播放。
     */
    internal fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        runCatching { Shizuku.addBinderReceivedListenerSticky(listener) }
            .onFailure { Log.w(TAG, "addBinderReceivedListener failed", it) }
    }

    internal fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        runCatching { Shizuku.removeBinderReceivedListener(listener) }
    }
}
