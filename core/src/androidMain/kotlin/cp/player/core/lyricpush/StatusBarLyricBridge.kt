/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/TickerBridge.kt
 * Changes: 删掉 PlaybackTickerState 与 hideNotification 相关分支 —— 那套机制是
 *          Halcyon 用来**就地改写自己播放通知**的（依赖它自家 PlaybackService 的通知
 *          构建路径），本仓库媒体通知由 media3 的 MediaNotificationProvider 掌管，
 *          改写的正确入口是 LyricPushMetadataSink（见 MediaNotificationLyricBridge）。
 *          这里只保留两件自包含的事：Flyme ticker 广播 + 通用浮动通知；
 *          小图标由 Canvas 合成（core 无资源目录）。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.util.Log
import cp.player.core.playback.SyncedLyricLine

/**
 * 状态栏歌词：魅族 Flyme ticker 广播 + 通用「浮动通知」。
 *
 * 两条路径**互斥**：
 * - 魅族设备上走 ticker 广播（沉浸式状态栏滚动，无通知噪音）；
 * - 其它设备上，如果用户开了「浮动通知歌词」，就发一条高优先级的横幅通知。
 *
 * 为什么不都发：ticker 广播在非魅族设备上无人接收，发了等于空转；
 * 而横幅通知在魅族设备上会打断 Flyme 自己的状态栏歌词，两边打架。
 */
internal class StatusBarLyricBridge(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var enabled = false
    private var headsUpEnabled = false
    private var secondary = LyricSecondaryMode.OFF

    private var lastPayload: Pair<String, String?>? = null

    private var headsUpSeq = 0
    private var lastHeadsUpId = 0
    private var lastHeadsUpAtMs = 0L

    private val flagAlwaysShowTicker: Int by lazy { notificationFlag("FLAG_ALWAYS_SHOW_TICKER", FLAG_ALWAYS_SHOW_TICKER_FALLBACK) }
    private val flagOnlyUpdateTicker: Int by lazy { notificationFlag("FLAG_ONLY_UPDATE_TICKER", FLAG_ONLY_UPDATE_TICKER_FALLBACK) }
    private val smallIcon by lazy { Icon.createWithBitmap(LyricPushArtwork.silhouetteIcon(48)) }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        lastPayload = null
        if (!enabled) clearLyric()
    }

    fun setHeadsUpEnabled(enabled: Boolean) {
        if (headsUpEnabled == enabled) return
        headsUpEnabled = enabled
        lastPayload = null
        if (!enabled) cancelHeadsUp()
    }

    fun setSecondary(mode: LyricSecondaryMode) {
        if (secondary == mode) return
        secondary = mode
        lastPayload = null
    }

    fun sendLine(line: SyncedLyricLine?, force: Boolean = false) {
        if (!enabled || line == null) return
        val main = line.lineTextOrNull() ?: return
        val secondaryText = line.secondaryText(secondary)?.takeIf { it.isNotBlank() }
        val payload = main to secondaryText
        if (!force && payload == lastPayload) return
        lastPayload = payload

        runCatching {
            sendFlymeBroadcast(
                Intent(ACTION_SEND_LYRIC).apply {
                    // 同一份文本挂在多个键名下：不同 Flyme 版本读的键不一样，
                    // 上游逐版本试出来的，少一个键就有一个版本收不到。
                    putExtra("ticker_text", main)
                    putExtra("lyric", main)
                    putExtra("text", main)
                    putExtra("content", main)
                    putExtra("ticker_package", context.packageName)
                    putExtra("package", context.packageName)
                    putExtra("ticker_app_name", appName)
                    putExtra("app_name", appName)
                    secondaryText?.let { putExtra("translation", it) }
                },
            )

            if (shouldUseHeadsUp()) {
                postHeadsUp(main, secondaryText)
            } else if (hideCarrierNotification) {
                postCarrierNotification(main, secondaryText)
            }
        }.onFailure { Log.w(TAG, "Unable to publish status bar lyric", it) }
    }

    fun clearLyric() {
        lastPayload = null
        runCatching {
            sendFlymeBroadcast(
                Intent(ACTION_CLEAR_LYRIC).apply {
                    putExtra("ticker_package", context.packageName)
                    putExtra("package", context.packageName)
                },
            )
        }
        runCatching { notificationManager.cancel(CARRIER_NOTIFICATION_ID) }
        cancelHeadsUp()
    }

    fun destroy() {
        enabled = false
        headsUpEnabled = false
        clearLyric()
    }

    // ============ Flyme ticker ============

    /**
     * 发**两次**广播：一次隐式、一次显式指向 SystemUI。
     *
     * 从 Android 8 起隐式广播受限，只发隐式广播在部分 Flyme 版本上收不到；
     * 而只发显式广播则受不到某些版本的 SystemUI 包名差异影响。两次都发最稳。
     */
    private fun sendFlymeBroadcast(intent: Intent) {
        context.sendBroadcast(intent)
        context.sendBroadcast(Intent(intent).setPackage(SYSTEM_UI_PACKAGE))
    }

    /**
     * 让「歌词常驻通知」承载文本。
     *
     * 仅在**非**浮动通知模式下使用：它是一条常驻的低优先级通知，Flyme 从它上面
     * 取状态栏文本。发它而不直接 setTicker，是因为前者状态栏会持续显示，后者只闪一次。
     */
    @Suppress("DEPRECATION")
    private fun postCarrierNotification(text: String, secondaryText: String?) {
        ensureNotificationChannel(
            context = context,
            channelId = CHANNEL_ID,
            name = CHANNEL_NAME,
            description = CHANNEL_DESCRIPTION,
            importance = NotificationManager.IMPORTANCE_LOW,
        )
        val flymeSupported = isFlymeTickerSupported()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(smallIcon)
            .setContentTitle(text)
            .setContentText(secondaryText.orEmpty())
            .setTicker(if (flymeSupported) text else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setDefaults(0)
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
        if (flymeSupported) {
            // 这两个 flag 是隐藏常量：Flyme 用它区分「系统通知」与「状态栏歌词载体」。
            notification.flags = notification.flags or flagAlwaysShowTicker
            notification.flags = notification.flags or flagOnlyUpdateTicker
        }
        runCatching { notificationManager.notify(CARRIER_NOTIFICATION_ID, notification) }
    }

    // ============ 通用浮动通知 ============

    @Suppress("DEPRECATION")
    private fun postHeadsUp(text: String, secondaryText: String?) {
        val now = SystemClock.uptimeMillis()
        // 逐字歌词可能每 200ms 就换一句（短句），不限流会变成通知风暴。
        if (now - lastHeadsUpAtMs < HEADS_UP_MIN_INTERVAL_MS) return
        lastHeadsUpAtMs = now

        ensureNotificationChannel(
            context = context,
            channelId = CHANNEL_ID_HEADS_UP,
            name = HEADS_UP_CHANNEL_NAME,
            description = HEADS_UP_CHANNEL_DESCRIPTION,
            importance = NotificationManager.IMPORTANCE_HIGH,
        )
        cancelHeadsUp()
        headsUpSeq = (headsUpSeq + 1) % 1000
        // 每次都换一个通知 id：同 id 更新不会重新弹出横幅，而横幅正是这个模式的意义。
        val id = HEADS_UP_BASE_ID + headsUpSeq

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID_HEADS_UP)
        } else {
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(smallIcon)
            .setContentTitle(text)
            .setContentText(secondaryText.orEmpty())
            .setTicker(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(null)
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                // ⚠️ minSdk = 24：`setTimeoutAfter` 是 API 26+，低版本直接调用会
                // NoSuchMethodError。低版本没有「到期自动撤」的机制，横幅会一直挂着
                // 直到下一个通知替换它 —— 这是可接受的降级（不是崩溃）。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setTimeoutAfter(HEADS_UP_TIMEOUT_MS)
                }
            }
            .build()

        lastHeadsUpId = id
        runCatching { notificationManager.notify(id, notification) }
    }

    private fun cancelHeadsUp() {
        if (lastHeadsUpId != 0) {
            runCatching { notificationManager.cancel(lastHeadsUpId) }
            lastHeadsUpId = 0
        }
    }

    /**
     * 是否需要「常驻通知承载状态栏文本」。
     *
     * 只有**非**浮动通知模式才需要：浮动通知本身就是横幅，再挂一条常驻通知是重复的。
     */
    private val hideCarrierNotification: Boolean
        get() = !shouldUseHeadsUp()

    private fun shouldUseHeadsUp(): Boolean = headsUpEnabled && !isFlymeTickerSupported()

    private fun isFlymeTickerSupported(): Boolean =
        isFlymeDevice() && flagAlwaysShowTicker > 0 && flagOnlyUpdateTicker > 0

    private fun isFlymeDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val display = Build.DISPLAY.orEmpty()
        return manufacturer.contains("meizu", ignoreCase = true) ||
            brand.contains("meizu", ignoreCase = true) ||
            display.contains("flyme", ignoreCase = true)
    }

    /**
     * 读 `Notification` 的隐藏 flag 常量。
     *
     * 这两个常量不在 SDK 里，只能反射取；取不到就用上游实测的数值兜底 ——
     * **不要**在没有验证的情况下改这两个魔数，它们是 Flyme 私有协议的一部分。
     */
    private fun notificationFlag(name: String, fallback: Int): Int = try {
        val field = Notification::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.getInt(null)
    } catch (_: Throwable) {
        fallback
    }

    private val appName: String by lazy {
        runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }
            .getOrDefault(context.packageName)
    }

    private companion object {
        const val TAG = "StatusBarLyric"

        const val CHANNEL_ID = "cp_player_status_bar_lyric_v1"
        const val CHANNEL_NAME = "状态栏歌词"
        const val CHANNEL_DESCRIPTION = "承载状态栏歌词文本的常驻通知"

        const val CHANNEL_ID_HEADS_UP = "cp_player_heads_up_lyric_v1"
        const val HEADS_UP_CHANNEL_NAME = "浮动歌词通知"
        const val HEADS_UP_CHANNEL_DESCRIPTION = "以横幅形式显示当前歌词"

        const val CARRIER_NOTIFICATION_ID = 0x43504C01
        const val HEADS_UP_BASE_ID = 0x43505000

        const val HEADS_UP_MIN_INTERVAL_MS = 800L
        const val HEADS_UP_TIMEOUT_MS = 1_800L

        /** Flyme 私有 flag 的实测值（反射失败时的兜底）。 */
        const val FLAG_ALWAYS_SHOW_TICKER_FALLBACK = 0x1000000
        const val FLAG_ONLY_UPDATE_TICKER_FALLBACK = 0x2000000

        const val ACTION_SEND_LYRIC = "com.meizu.flyme.ticker.ACTION_SEND"
        const val ACTION_CLEAR_LYRIC = "com.meizu.flyme.ticker.ACTION_CLEAR"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
