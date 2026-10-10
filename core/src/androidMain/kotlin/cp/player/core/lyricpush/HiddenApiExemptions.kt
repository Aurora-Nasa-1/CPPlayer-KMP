/*
 * 新增文件（非直接移植）。
 * 移植指南见 XIAOMI_SUPER_ISLAND_PORTING.md §3「Hidden API」。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.os.Build
import android.os.Process
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 隐藏 API 豁免。
 *
 * ### 为什么需要
 * XMSF 隔离要用到两个隐藏 API：
 * 1. `android.os.ServiceManager.getService(name)` —— 拿系统服务 binder；
 * 2. `android.net.IConnectivityManager` 的 `TRANSACTION_*` 常量 —— 读事务号
 *    （见 [XmsfFirewall]，它不复制 AIDL，而是反射读框架里的常量）。
 * 两者都命中非 SDK 接口限制，不豁免就是 `NoSuchMethodException` / 反射拒绝。
 *
 * ### ⚠️ 它是**进程级、非永久**的
 * 不要「装一次就当长期有效」。ROM 更新、进程重建、Shizuku binder 重建都会让它失效。
 * 因此本仓库在两个点各装一次：
 * - `CPPlayerApplication.onCreate()`（主进程入口）；
 * - 每次走防火墙之前（[XmsfFirewall] 内部调用，见 `ensureHiddenApiExemptions()`）。
 *
 * 这不是「系统级持久化」，但与移植指南描述的实际行为一致。
 */
object HiddenApiExemptions {

    private const val TAG = "XmsfHiddenApi"

    /**
     * 已成功安装豁免的进程 id；`-1` 表示本进程还没装过。
     *
     * 记 pid 而不是布尔：豁免是**进程级**的，而同一进程内重复安装是无害但白白的
     * 反射开销。pid 变了（几乎不会，除非 fork）就重装一次。
     */
    @Volatile
    private var installedPid: Int = -1

    /**
     * 安装豁免（幂等）。
     *
     * `addHiddenApiExemptions("")` 的空串是**前缀匹配**语义 —— 空前缀匹配一切，
     * 等价于「本进程内不再拦截任何非 SDK 接口」。这比逐类列白名单稳，
     * 因为各 ROM 的隐藏类名会漂。
     *
     * @return 是否已处于「已豁免」状态（失败时返回 false，调用方按「未豁免」处理）。
     */
    fun install(): Boolean {
        // API 28（P）才引入非 SDK 接口限制；更低版本无需处理。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        val pid = Process.myPid()
        if (installedPid == pid) return true
        val ok = runCatching { HiddenApiBypass.addHiddenApiExemptions("") }.getOrDefault(false)
        if (ok) {
            installedPid = pid
            Log.d(TAG, "Hidden API exemptions installed for process pid=$pid")
        } else {
            Log.w(TAG, "Failed to install hidden API exemptions for pid=$pid")
        }
        return ok
    }
}
