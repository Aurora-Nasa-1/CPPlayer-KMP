/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/XiaomiSuperIslandLyricService.kt
 * Changes: 包名与 action 前缀换成 cp.player；去掉 `pendingNotification` 的跨进程假设注释
 *          （这里仍是同进程内存交接，理由不变）；补充「服务被 ROM 杀掉后重新走
 *          startForegroundService」的重入路径。
 * ⚠️ 需要在宿主模块（app-android）的 AndroidManifest 里声明：
 *    <service android:name="cp.player.core.lyricpush.XiaomiSuperIslandLyricService"
 *             android:foregroundServiceType="specialUse" android:exported="false" />
 *    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 承载 HyperOS 焦点通知的**独立前台服务**。
 *
 * ### 为什么必须是独立的前台服务
 * HyperOS 会拒绝把超级岛载荷当作「普通通知」渲染 —— 从播放进程直接 `notify()` 只会
 * 得到一条普通通知，岛不出现。放在一个 `specialUse` 类型的前台服务里，
 * 才符合 SystemUI 的渲染预期，而且**不影响** media3 自己那条媒体会话通知。
 *
 * ### 为什么不用 Intent 传 Notification
 * `Notification` 里可能挂着整张专辑封面 Bitmap，走 Intent 会撞上 Binder 的 1MB
 * 事务上限（真实曲目上必然触发）。同进程服务改成内存交接（[pendingNotification]），
 * Intent 只做触发。
 */
class XiaomiSuperIslandLyricService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private var foregroundStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PUBLISH -> {
                val notification = pendingNotification
                if (notification == null) {
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                runCatching {
                    if (foregroundStarted) {
                        notificationManager.notify(NOTIFICATION_ID, notification)
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            ServiceCompat.startForeground(
                                this,
                                NOTIFICATION_ID,
                                notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                            )
                        } else {
                            startForeground(NOTIFICATION_ID, notification)
                        }
                        foregroundStarted = true
                    }
                }.onFailure {
                    Log.w(TAG, "Unable to publish Super Island lyric notification", it)
                    stopSelfSafely()
                }
            }

            ACTION_STOP -> stopSelfSafely()

            else -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foregroundStarted = false
        running = false
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { notificationManager.cancel(NOTIFICATION_ID) }
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        runCatching { stopSelf() }
    }

    companion object {
        const val TAG = "SuperIslandLyricSvc"

        const val ACTION_PUBLISH = "cp.player.core.action.PUBLISH_SUPER_ISLAND_LYRIC"
        const val ACTION_STOP = "cp.player.core.action.STOP_SUPER_ISLAND_LYRIC"
        const val NOTIFICATION_ID = 0x454C4C53

        @Volatile
        private var pendingNotification: Notification? = null

        @Volatile
        private var running = false

        /**
         * 发布 / 更新岛内内容。
         *
         * 服务已在运行时走 `startService`（**不要**每次都用 `startForegroundService`：
         * 那会反复进入系统的 FGS 启动路径，在低的歌词更新频率下也会累积开销）。
         * 服务被 ROM 停掉时回退到前台路径重入。
         */
        fun publish(context: Context, notification: Notification) {
            pendingNotification = notification
            val intent = Intent(context, XiaomiSuperIslandLyricService::class.java)
                .setAction(ACTION_PUBLISH)
            val result = runCatching {
                if (running) {
                    context.startService(intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            }.recoverCatching {
                running = false
                ContextCompat.startForegroundService(context, intent)
            }
            if (result.isSuccess) {
                running = true
            } else {
                result.exceptionOrNull()?.let {
                    Log.w(TAG, "Unable to start Super Island lyric foreground service", it)
                }
                // 前台服务起不来时至少把通知发出去：部分 HyperOS 版本仍能渲染岛。
                runCatching {
                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIFICATION_ID, notification)
                }
            }
        }

        fun stop(context: Context) {
            running = false
            val intent = Intent(context, XiaomiSuperIslandLyricService::class.java)
                .setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Unable to stop Super Island lyric service", it) }
        }
    }
}
