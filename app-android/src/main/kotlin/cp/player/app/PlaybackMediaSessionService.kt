package cp.player.app

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import cp.player.core.playback.SharedMedia3Player

/**
 * Android system media-session host. The shared controller remains the source of
 * truth for in-app playback; this service keeps the process eligible for playback
 * controls while the activity is backgrounded.
 */
class PlaybackMediaSessionService : MediaSessionService() {
    private var player: ControllerForwardingPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        (application as? CPPlayerApplication)?.backend
        super.onCreate()
        // 会话的 Player 不是裸 ExoPlayer：切歌/seek 必须转交应用控制器。
        // 裸 ExoPlayer 只持有单个 media item，队列在控制器里，
        // 直接用它会导致通知栏/锁屏/耳机切歌无效。
        val sessionPlayer = ControllerForwardingPlayer(SharedMedia3Player.get(this)) {
            AppModel.playback
        }
        player = sessionPlayer
        // 通知栏那个小图标：media3 默认用它自带的占位图（media3_notification_small_icon），
        // 在状态栏里和本应用没有任何关系。换成自己的单色播放三角。
        // ⚠️ 状态栏图标必须是**白色剪影 + 透明底**，系统会统一着色 —— 带颜色的图会被糊成色块。
        // 资源由 scripts/gen_app_icon.py 生成（drawable-*/ic_stat_playback.png）。
        // ⚠️ 必须在下面的 addSession 之前：addSession 会**立即**创建
        // MediaNotificationManager，它用「当时的 provider」定终，顺序反了小图标不生效。
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_stat_playback)
            }
        )
        val session = MediaSession.Builder(this, sessionPlayer)
            // 点通知 / 锁屏卡片回到播放界面。
            // ⚠️ 这不是「锦上添花」：DefaultMediaNotificationProvider 用
            // session.getSessionActivity() 建通知的 contentIntent，为 null 时通知**不可点**。
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        mediaSession = session
        // ⚠️⚠️ 这一步才是「有 MediaSession」的关键，缺了它前面全是死代码。
        //
        // MediaSession.Builder(this, player).build() **只**把会话登记进 MediaSession
        // 自己的静态表（SESSION_ID_TO_SESSION_MAP），**完全不碰 MediaSessionService**
        // （javap 核实 1.4.1：MediaSession / MediaSession.Builder 的字节码里
        // `MediaSessionService` 出现 **0 次**）。1.4.1 里服务侧真正的挂载点只有一个 ——
        // 本方法：notificationManager.addSession(session) 建一条**服务内部 MediaController**，
        // 再 session.setListener(MediaSessionListener()) 让服务开始监听会话。
        //
        // javap 核实（1.4.1）：`public final void addSession(MediaSession)` 是公开 API，
        // 官方注释说「多数应用不需要手动调用」——那是以「应用自己用 MediaController 播」
        // 为前提；本应用直接驱动同一个 ExoPlayer 且不建控制器，所以必须显式登记。
        addSession(session)
        // ⚠️⚠️ addSession 只是**必要条件**：启动时 Player 里没有 media item、状态是 IDLE，
        // 通知判据 `shouldShowNotification()`（时间线非空 且 非 IDLE）不成立 ⇒ 第一首歌
        // load 完成之前没有通知，属预期行为（不是 bug，别再往这里找根因）。
        //
        // 1.11.1 复核：判据前半句（时间线非空）不变；**IDLE 分支改为由
        // `setShowNotificationForIdlePlayer(...)` 决定**，默认 = `AFTER_STOP_OR_ERROR`
        // —— 即「播放过之后再停止/出错」通知**会保留**（1.4.1 是不保留）。这是升级带来的
        // 唯一用户可见行为变化；若要恢复旧观感，在上面的 setMediaNotificationProvider
        // 旁加一行：
        //     setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        //
        // 另有一处同样致命、且不在本文件：MainActivity 必须 bindService 把本服务绑上
        // —— 只 startService 的话系统侧永远不知道这条会话存在（见那里的注释）。
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户从「最近任务」划掉 app 卡片时不自毁。
     *
     * 默认实现在「暂停中」也会收摊，且 **1.11.1 起从 `stopSelf()` 升级为
     * `pauseAllPlayersAndStopSelf()`（会连带暂停播放）** —— 暂停后划卡片即停播、
     * 通知消失、进程随即失去前台资格，恰好抵消 manifest 里 `stopWithTask="false"`
     * 的保活意图。
     * 这里改为：只要引擎里还有可续播的曲目（READY/BUFFERING，含暂停）就保活；
     * 真正空闲（IDLE / 已播完）才停掉自己。**1.11.1 复核后仍必须覆写**（默认更激进了）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        val keepAlive = p != null && runCatching {
            when (p.playbackState) {
                Player.STATE_READY, Player.STATE_BUFFERING -> true
                else -> false
            }
        }.getOrDefault(false)
        if (keepAlive) return
        stopSelf()
        // 故意不调 super：其默认逻辑与上述意图冲突（见 KDoc）。
    }

    /**
     * ⚠️⚠️ 熄屏后台被杀（进程秒死）的修复点 —— 拦下 media3 的「非播放中即降前台」。
     *
     * 通知更新（含暂停、以及**一首播完到下一首 load 完成之间的 STATE_ENDED 间隙**）
     * 会走 `updateNotificationInternal(runInForeground=false)` →
     * `maybeStopForegroundService(false)` → **`stopForeground(DETACH)`**：通知还挂在
     * 通知栏，但服务的**前台资格已被摘掉**。熄屏下进程只剩「started service」优先级，
     * 厂商 ROM（MIUI/HyperOS、HarmonyOS、ColorOS…）几秒内就把它杀掉。
     * 播放中看似安全，但**每次切歌都会短暂降级** —— 只要有一次发生在熄屏后，进程就没了。
     *
     * 想覆写 `Service.stopForeground` 拦截？**不行** —— 它在 `android.app.Service` 上是
     * final（编译实锤）。可用的公开钩子就是本方法：这里只要引擎里还有可续播内容
     * （时间线非空且非 IDLE）就强制按「保持前台」处理；真正空闲（IDLE / 清空队列）时
     * 透传原值，通知照常可清、服务照常降级。系统拒绝时
     * （ForegroundServiceStartNotAllowedException）media3 内部有兜底，不会崩。
     *
     * 1.11.1 复核：判据 `shouldRunInForeground` 被重写（签名 `(MediaSession, boolean)`
     * → `(boolean)`，并叠加了默认 **10 分钟**的 user-engaged 超时窗口），但逐字节码追到
     * `isAnySessionUserEngaged(boolean)` 后确认**老判据逐字保留**（只是从「单 session」
     * 改成「遍历所有 session」）⇒ 本覆写仍有效；且超时窗口只有 10 分钟、覆盖不了
     * 「暂停很久仍要保活」这个意图，所以**仍然必要**。
     */
    override fun onUpdateNotification(session: MediaSession, runInForeground: Boolean) {
        super.onUpdateNotification(session, runInForeground || hasResumablePlayback())
    }

    /** 引擎里是否还有可续播内容（有 media item 且不是 IDLE）。读失败按「无」处理。 */
    private fun hasResumablePlayback(): Boolean {
        val p = player ?: return false
        return runCatching { p.mediaItemCount > 0 && p.playbackState != Player.STATE_IDLE }
            .getOrDefault(false)
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        // SharedMedia3Player is released by the playback controller lifecycle.
        player = null
        super.onDestroy()
    }
}
