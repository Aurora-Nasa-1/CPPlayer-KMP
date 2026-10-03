package cp.player.app

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
        val session = MediaSession.Builder(this, sessionPlayer).build()
        mediaSession = session
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
        // ⚠️⚠️ 这一步才是「有 MediaSession」的关键，缺了它前面全是死代码。
        //
        // MediaSession.Builder(this, player).build() **只**把会话登记进 MediaSession
        // 自己的静态表（SESSION_ID_TO_SESSION_MAP），**完全不碰 MediaSessionService**。
        // 1.4.1 里服务侧真正的挂载点只有一个 —— 本方法：
        //
        //     MediaSessionService.addSession(session)
        //       → notificationManager.addSession(session)   // 建一条服务内部 MediaController
        //       → session.setListener(MediaSessionListener())// 服务开始监听会话
        //
        // 而系统自带的登记路径只有三条：`onBind(MediaBrowserServiceCompat)`、
        // `onStartCommand(媒体按键 action)`、**某个 MediaController 经 Binder 连上来**
        // （MediaSessionServiceStub.connect）。本应用三条一条都不走：
        // MainActivity 用 `startService(普通 Intent)`（无 action ⇒ onStartCommand 直接
        // `return START_STICKY`，无任何副作用），且从不连接任何 MediaController。
        // 结果 `MediaNotificationManager` 连实例都没被创建，内部 controllerMap 恒空 ⇒
        // `shouldShowNotification()` 里的 `controller != null` 永远为假 ⇒
        // 通知栏 / 锁屏 / 蓝牙 永远没有任何播放控制 —— 正是「改了很久还是没有
        // MediaSession」的根因，且与该不该 startForeground、要不要 POST_NOTIFICATIONS
        // 全都无关（那两条只是它的下游症状）。
        //
        // javap 核实（1.4.1）：`public final void addSession(MediaSession)` 是公开 API，
        // 官方注释说「多数应用不需要手动调用」——那是以「应用自己用 MediaController 播」
        // 为前提；本应用直接驱动同一个 ExoPlayer 且不建控制器，所以必须显式登记。
        addSession(session)
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

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        // SharedMedia3Player is released by the playback controller lifecycle.
        player = null
        super.onDestroy()
    }
}
