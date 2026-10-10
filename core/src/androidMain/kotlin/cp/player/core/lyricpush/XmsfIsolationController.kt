/*
 * 新增文件（非直接移植）。
 * 移植指南见 XIAOMI_SUPER_ISLAND_PORTING.md §5.4「三种隔离模式」。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * XMSF 隔离的**调度**：把「什么时候阻断、什么时候恢复」和「怎么阻断」分开。
 *
 * 三档行为（移植指南 §5.4）：
 *
 * | 档位 | 行为 |
 * | --- | --- |
 * | OFF | 不动 XMSF，直接发通知 |
 * | STANDARD | 阻断 → 发通知 → 等 `blockDurationMs` → 恢复 |
 * | ENHANCED | 播放期间保持阻断，暂停 / 关闭 / 切档时才恢复 |
 *
 * ### 与移植指南的一处实现差异（等价且更稳）
 * 指南的增强档用「generation 计数器」防止旧协程恢复网络。这里改用**取消并重排一个
 * 待执行的恢复任务**（[restoreJob]）：标准档下每来一帧就取消上一次的延迟恢复、
 * 重新计时，效果与 generation 判据相同，但不需要在恢复时再回头比对代际 ——
 * 被取消的协程根本不会执行到 `restore`。
 *
 * ### 切歌不恢复
 * 增强档在换歌时**不先恢复再阻断**（指南明确要求，否则换歌间隙会失败）：
 * [ensureBlocked] 是幂等的，阻断一次之后整段播放期都不会再动它。
 *
 * ⚠️ 所有方法都在主线程调用（由 `AndroidLyricPusher` 的 Main.immediate 作用域驱动），
 * 因此这里不做额外加锁。
 */
internal class XmsfIsolationController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    @Volatile
    private var mode = XiaomiSuperIslandConfig.XmsfIsolationMode.OFF

    @Volatile
    private var blockDurationMs = XiaomiSuperIslandConfig.DEFAULT_XMSF_BLOCK_MS.toLong()

    /** 当前是否处于「本控制器下发了阻断」的状态。用于恢复的幂等判断。 */
    @Volatile
    private var blocked = false

    private var restoreJob: Job? = null

    /** 应用新档位；档位变化时先把网络还原，避免从增强档切走后一直断着。 */
    fun applyMode(mode: XiaomiSuperIslandConfig.XmsfIsolationMode, blockDurationMs: Int) {
        val changed = this.mode != mode
        this.mode = mode
        this.blockDurationMs = blockDurationMs.coerceAtLeast(0).toLong()
        if (changed) {
            // 一行诊断（移植指南 §10 的 `permission check` 那一栏）：只在档位变化时打，
            // 逐帧打会把日志刷爆，而档位变化恰好是排查「为什么没生效」的第一个观察点。
            Log.d(
                TAG,
                "isolation mode=$mode binderAlive=${ShizukuPermissions.isBinderAlive()} " +
                    "granted=${ShizukuPermissions.isGranted()} blockMs=$blockDurationMs",
            )
            restoreNow()
        }
    }

    /** 当前档位（诊断 / UI 用）。 */
    fun currentMode(): XiaomiSuperIslandConfig.XmsfIsolationMode = mode

    /** 当前是否已阻断（诊断用）。 */
    fun isBlocked(): Boolean = blocked

    /**
     * 在「隔离窗口」内执行一次通知发布。
     *
     * 未授予 Shizuku 权限时**静默退化为直接发送** —— 与 OFF 档等价，绝不因为
     * 隔离不可用而放弃发通知（否则未装 Shizuku 的用户会连普通通知都收不到）。
     */
    fun aroundPublish(publish: () -> Unit) {
        val active = mode
        if (active == XiaomiSuperIslandConfig.XmsfIsolationMode.OFF || !ShizukuPermissions.isGranted()) {
            publish()
            return
        }
        val blockedNow = ensureBlocked()
        publish()
        if (blockedNow && active == XiaomiSuperIslandConfig.XmsfIsolationMode.STANDARD) {
            scheduleRestore()
        }
    }

    /** 立即恢复网络（暂停 / 关闭功能 / 切档 / 清空歌词 / 进程退出时调用）。 */
    fun restoreNow() {
        restoreJob?.cancel()
        restoreJob = null
        if (!blocked) return
        // 先置位再发调用：若恢复调用抛异常，也不会把自己卡在「以为还阻断着」的状态。
        blocked = false
        runCatching { XmsfFirewall.restore(context) }
            .onFailure { Log.w(TAG, "restore XMSF networking failed", it) }
    }

    private fun ensureBlocked(): Boolean {
        if (blocked) return true
        val ok = runCatching { XmsfFirewall.block(context) }.getOrDefault(false)
        blocked = ok
        return ok
    }

    private fun scheduleRestore() {
        restoreJob?.cancel()
        restoreJob = scope.launch {
            delay(blockDurationMs)
            restoreNow()
        }
    }

    private companion object {
        const val TAG = "XmsfIsolation"
    }
}
