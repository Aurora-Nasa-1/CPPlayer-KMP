/*
 * 新增文件（非直接移植，**未复制任何 GPL-3.0 上游代码**）。
 * 实现依据：XIAOMI_SUPER_ISLAND_PORTING.md §5「XMSF 隔离是联网显示的关键」。
 * 移植指南提到 Capsulyric / InstallerX Revived 的 GPL-3.0 实现；本文件刻意**不移植**
 * 它们的代码，而是按指南描述的协议自行实现（wrapped binder + 反射读事务号），
 * 因此不引入 GPL 义务。见 THIRD_PARTY_LICENSES.md。
 */
package cp.player.core.lyricpush

import android.content.Context
import android.os.Parcel
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 通过 Shizuku **临时切断 `com.xiaomi.xmsf` 的网络**。
 *
 * ### 为什么必须这么做
 * HyperOS 联网状态下不显示超级岛，通常不是 Focus payload 错了，而是 `com.xiaomi.xmsf`
 * 收到 Focus 通知后又去联网校验（移植指南 §0）。只发通知：联网被拦；断网才显示。
 * 因此在「发送通知」前后用 Shizuku 把 XMSF 的 UID 加进 OEM deny chain，发完再恢复。
 *
 * ### 为什么不用 AIDL
 * `IConnectivityManager` 的 AIDL 有上百个方法，事务号按**声明顺序**分配 ——
 * 复制一份 AIDL 就必须与 ROM 的接口逐字对齐，任何版本差异都会静默调错方法。
 * 这里改用两条更稳的路：
 * 1. 用 [SystemServiceHelper.getTransactionCode] **反射读框架里的 `TRANSACTION_*` 常量**
 *    （它内部就是 `Class.forName(接口全名).getDeclaredField("TRANSACTION_" + 方法名)`），
 *    事务号永远与当前 ROM 一致；
 * 2. 用 [ShizukuBinderWrapper] 包住系统服务 binder 后手动 `transact`，
 *    这样事务以 **Shizuku（shell/root）身份**执行 —— 直接调 app 进程里的 proxy
 *    不会走 shell 权限（移植指南 §5.2）。
 *
 * 同时保留 `connectivity` 与 `network_management` 两条 typed 路径（移植指南 §5.2 的建议），
 * 命中哪条就记住哪条。
 */
internal object XmsfFirewall {

    private const val TAG = "XmsfFirewall"

    /** 要被隔离的包：**不是**自己，而是小米的 XMSF。 */
    const val XMSF_PACKAGE = "com.xiaomi.xmsf"

    /** OEM deny chain：`OEM_DENY_3`。 */
    const val OEM_DENY_CHAIN = 9
    const val RULE_DENY = 2
    const val RULE_ALLOW = 1
    const val RULE_DEFAULT = 0

    /** XMSF 的 uid；取不到（未装 / 查询失败）返回 null。 */
    fun xmsfUid(context: Context): Int? = runCatching {
        context.packageManager.getPackageUid(XMSF_PACKAGE, 0)
    }.getOrNull()

    /**
     * 阻断 XMSF 联网。
     *
     * 顺序：先启用 deny chain，再把 XMSF 的 uid 规则设为 [RULE_DENY]。
     *
     * @return 规则是否**确实下发成功**（chain 启用失败只记日志 —— 该 chain 可能已被
     *   系统或其它应用启用，此时 `setFirewallChainEnabled(…, true)` 本身就是幂等的）。
     */
    fun block(context: Context): Boolean {
        val uid = xmsfUid(context) ?: run {
            Log.w(TAG, "XMSF package not found; cannot block")
            return false
        }
        val chainOk = XmsfFirewallTransport.setFirewallChainEnabled(OEM_DENY_CHAIN, true)
        if (!chainOk) Log.w(TAG, "setFirewallChainEnabled($OEM_DENY_CHAIN, true) did not confirm")
        val ruleOk = XmsfFirewallTransport.setUidFirewallRule(OEM_DENY_CHAIN, uid, RULE_DENY)
        if (ruleOk) {
            Log.d(TAG, "XMSF networking blocked for uid=$uid")
        } else {
            Log.w(TAG, "Failed to block XMSF networking for uid=$uid")
        }
        return ruleOk
    }

    /**
     * 恢复 XMSF 联网。
     *
     * ⚠️ **不要关闭整条 chain**（移植指南 §5.3）：它可能被系统或其它应用共用，
     * 关闭会影响别人的规则。只把本应用的 uid 规则还原成 [RULE_DEFAULT]。
     */
    fun restore(context: Context): Boolean {
        val uid = xmsfUid(context) ?: return false
        val ok = XmsfFirewallTransport.setUidFirewallRule(OEM_DENY_CHAIN, uid, RULE_DEFAULT)
        if (ok) {
            Log.d(TAG, "XMSF networking restored for uid=$uid")
        } else {
            Log.w(TAG, "Failed to restore XMSF networking for uid=$uid")
        }
        return ok
    }
}

/**
 * 底层事务发送。与 [XmsfFirewall] 分开是为了让「规则语义」与「怎么把调用送出去」两件事
 * 各自独立 —— 后者是唯一需要跟着 ROM 走的部分。
 */
private object XmsfFirewallTransport {

    private const val TAG = "XmsfFirewall"

    private data class Path(
        /** `ServiceManager` 里的服务名。 */
        val service: String,
        /** 接口全限定名（= AIDL descriptor，也用于反射取 `TRANSACTION_*`）。 */
        val iface: String,
        /** 「按 uid 设规则」的方法名。 */
        val setUidRule: String,
        /** 「启停 chain」的方法名。 */
        val setChainEnabled: String,
    )

    /**
     * 两条 typed 路径。不同 HyperOS 版本方法名有差异，所以两条都留着；
     * `connectivity` 为主（`IConnectivityManager`），`network_management` 为备。
     */
    private val paths = listOf(
        Path(
            service = "connectivity",
            iface = "android.net.IConnectivityManager",
            setUidRule = "setUidFirewallRule",
            setChainEnabled = "setFirewallChainEnabled",
        ),
        Path(
            service = "network_management",
            iface = "android.os.INetworkManagementService",
            setUidRule = "setFirewallUidRule",
            setChainEnabled = "setFirewallChainEnabled",
        ),
    )

    private val lock = Any()

    /** 上次命中的路径下标；下一次先试它。 */
    private var preferredIndex = 0

    fun setUidFirewallRule(chain: Int, uid: Int, rule: Int): Boolean =
        tryPaths { path -> call(path.service, path.iface, path.setUidRule, listOf(chain, uid, rule)) }

    fun setFirewallChainEnabled(chain: Int, enabled: Boolean): Boolean =
        tryPaths { path ->
            call(path.service, path.iface, path.setChainEnabled, listOf(chain, enabled))
        }

    private fun tryPaths(attempt: (Path) -> Boolean): Boolean {
        val order = synchronized(lock) {
            List(paths.size) { paths[(preferredIndex + it) % paths.size] }
        }
        for (path in order) {
            if (attempt(path)) {
                synchronized(lock) { preferredIndex = paths.indexOf(path) }
                return true
            }
        }
        return false
    }

    /**
     * 把一次 AIDL 调用送出去。
     *
     * 因为绕过了生成的 Proxy，这里必须**手工复刻**事务格式：
     * `writeInterfaceToken(descriptor)` → 依次写参数 → `transact` → `readException()`。
     * 目前只用到 int / boolean（AIDL 里 boolean 就是 int 0/1）两种参数，够用；
     * 出现新参数类型时这里要一起扩展。
     */
    private fun call(service: String, iface: String, method: String, args: List<Any>): Boolean {
        if (!ShizukuPermissions.isGranted()) return false
        // 隐藏 API 豁免是进程级的，且可能在进程重建后失效 —— 每次走特权调用前重装一次。
        HiddenApiExemptions.install()

        val binder = runCatching { SystemServiceHelper.getSystemService(service) }.getOrNull()
        if (binder == null) {
            Log.w(TAG, "system service '$service' unavailable")
            return false
        }
        // `getTransactionCode` 被 Shizuku 标了 deprecated（推荐改用 AIDL 生成类），
        // 但它正是我们**刻意**要的「按 ROM 反射读事务号」能力，且无替代 API。
        @Suppress("DEPRECATION")
        val code = runCatching { SystemServiceHelper.getTransactionCode(iface, method) }.getOrNull()
        if (code == null) {
            Log.w(TAG, "transaction code for $iface.$method not found")
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(iface)
            for (arg in args) {
                when (arg) {
                    is Int -> data.writeInt(arg)
                    is Boolean -> data.writeInt(if (arg) 1 else 0)
                    else -> {
                        Log.w(TAG, "unsupported argument type ${arg::class.java}")
                        return false
                    }
                }
            }
            ShizukuBinderWrapper(binder).transact(code, data, reply, 0)
            // 服务端会把异常写进 reply；不读就会把失败当成功。
            reply.readException()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "$service.$method failed: ${t::class.java.simpleName}: ${t.message}")
            false
        } finally {
            runCatching { data.recycle() }
            runCatching { reply.recycle() }
        }
    }
}
