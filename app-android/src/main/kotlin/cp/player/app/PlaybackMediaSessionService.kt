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
        // ⚠️⚠️⚠️ addSession 只是**必要条件**，不是充分条件 —— 别以为加完这行就有通知了。
        //
        // 通知的生成判据是 MediaNotificationManager.shouldShowNotification()（javap 1.4.1）：
        //     val c = controllerMap[session]
        //     return c != null
        //         && !c.currentTimeline.isEmpty()      // ← 时间线必须非空
        //         && c.playbackState != STATE_IDLE     // ← 状态必须非 IDLE
        // 这条内部控制器读的是 session.getPlayer()（= 上面那个 ControllerForwardingPlayer
        // → SharedMedia3Player 的 ExoPlayer）。启动时它没有 media item、状态是 IDLE
        // ⇒ 两条同时为假 ⇒ shouldShowNotification() 恒 false。
        //
        // 这是 media3 的**设计前提**（假定会话一建立 Player 里就有待播内容），不是 bug：
        // 真正的通知要等**第一次 load() 之后**（时间线非空、状态变 READY/BUFFERING），
        // onEvents 才驱动 updateNotification → createNotification → startForeground。
        // ⇒ 从点第一首歌到 load 完成之间没有通知，属预期行为。
        //
        // 另有一处**同样致命**、且不在本文件：MainActivity 必须 bindService 把本服务
        // 绑上（见那里的长注释）—— 只 startService 的话系统侧永远不知道这条会话存在。
        // 本次「改了很多次仍然没有 MediaSession」的真根因就在那里。
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户从「最近任务」划掉 app 卡片时不自毁。
     *
     * media3 1.4.1 的默认实现把「暂停中」也视为可停（只看是否正在播放），
     * 会在暂停后划卡片时 stopSelf → 通知消失、进程失去前台资格随即被杀，
     * 恰好抵消 manifest 里 `stopWithTask="false"` 的保活意图。
     * 这里改为：只要引擎里还有可续播的曲目（READY/BUFFERING，含暂停）就保活；
     * 真正空闲（IDLE / 已播完）才停掉自己。
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
     * media3 1.4.1 的 MediaNotificationManager（javap 核实）：
     * - `shouldRunInForeground(session, periodic)` 判据是
     *   `playWhenReady && (playbackState == READY || BUFFERING)`；
     * - 每次通知更新（含暂停、以及**一首播完到下一首 load 完成之间的 STATE_ENDED 间隙**）
     *   都会走 `updateNotificationInternal(runInForeground=false)` →
     *   `maybeStopForegroundService(false)` → **`stopForeground(DETACH)`**——
     *   通知还挂在通知栏，但服务的**前台资格已被摘掉**。
     *
     * 前台资格一旦没了，熄屏状态下进程只剩「started service」优先级，
     * 厂商 ROM（MIUI/HyperOS、HarmonyOS、ColorOS…）的电池策略几秒内就把进程杀掉 ——
     * 用户看到的就是「熄屏后台秒杀」。播放中看似安全（READY+playing 时是前台），
     * 但**每次切歌都会短暂降级**：只要有一次降级发生在熄屏后，进程就没了。
     *
     * 想覆写 `Service.stopForeground` 拦截？**不行** —— 它在 `android.app.Service` 上是
     * final（编译实锤）。可用的公开钩子是本方法：`onUpdateNotificationInternal`
     * 把算好的 `runInForeground` 传进来，再转给默认实现去走 startForeground /
     * stopForeground。这里只要引擎里还有可续播内容（时间线非空且非 IDLE），就强制按
     * 「保持前台」处理（走 media3 自己的 startForeground 路径，状态一致、无副作用）；
     * 真正空闲（IDLE / 清空队列）时透传原值，通知照常可清、服务照常降级。
     * 强制保前台时若系统拒绝（ForegroundServiceStartNotAllowedException），
     * media3 的 onUpdateNotificationInternal 已有 try/catch 兜底，不会崩。
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
