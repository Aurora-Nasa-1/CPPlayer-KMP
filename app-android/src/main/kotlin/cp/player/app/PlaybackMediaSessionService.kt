package cp.player.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import cp.player.core.playback.flick.FlickEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Android system media-session host. The shared controller remains the source of
 * truth for in-app playback; this service keeps the process eligible for playback
 * controls while the activity is backgrounded.
 */
class PlaybackMediaSessionService : MediaSessionService() {
    private var serviceScope: CoroutineScope? = null
    private var controllerPlayer: ControllerMediaSessionPlayer? = null
    private var player: ControllerForwardingPlayer? = null
    private var mediaSession: MediaSession? = null

    /**
     * 歌词写回媒体会话的通道（ColorOS 锁屏岛 extras + 蓝牙/车机通知歌词）。
     *
     * 注册到 `core` 的全局表里：`core` 的歌词投放协调器拿不到本服务实例
     * （它比服务先初始化），所以只能由服务在创建时把实现挂上去、销毁时摘掉。
     */
    private var lyricSink: LyricMetadataSink? = null

    override fun onCreate() {
        (application as? CPPlayerApplication)?.backend
        super.onCreate()
        // ⚠️ 预热磁盘缓存（SimpleCache 首次构造要开 SQLite，是主线程 IO 大头）。
        // 同步在 onCreate（主线程）做的话，首次播放那一下会被拖住；
        // 放 IO 线程预热，等下面 ControllerForwardingPlayer 真正 get() 时多半已就绪。
        Thread {
            runCatching { cp.player.core.playback.SharedMedia3Player.getCache(this) }
        }.apply {
            isDaemon = true
            name = "media-cache-prewarm"
        }.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope
        // 由 PlaybackController 统一驱动 MediaSession Player：无论是 Media3 还是 FlickEngine，
        // 通知栏/锁屏卡片/封面/进度条/蓝牙 AVRCP 与状态栏歌词都能完整呈现并双向控制。
        val backendPlayer = ControllerMediaSessionPlayer(Looper.getMainLooper(), scope) {
            AppModel.playback
        }
        backendPlayer.startObserving()
        controllerPlayer = backendPlayer
        val sessionPlayer = ControllerForwardingPlayer(backendPlayer) {
            AppModel.playback
        }
        player = sessionPlayer
        // 歌词写回挂在同一个 sessionPlayer 上：媒体会话、蓝牙 AVRCP 与 ColorOS
        // 读的都是它的 currentMediaItem.mediaMetadata。
        val sink = LyricMetadataSink { sessionPlayer }
        lyricSink = sink
        cp.player.app.platform.lyricpush.LyricPushSinks.metadataSink = sink
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
        addSession(session)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        controllerPlayer?.startObserving()
        return super.onStartCommand(intent, flags, startId)
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
        val keepAlive = hasResumablePlayback(p)
        // 只要当前并非正在播放中（含暂停 / 空闲），就立刻释放 USB 独占，避免划掉卡片后其它应用无声。
        runCatching { AppModel.playback.releaseExclusiveAudioIfIdle() }
        runCatching { FlickEngineManager.releaseUsbIfIdle() }
        if (keepAlive) return
        stopSelf()
        // 故意不调 super：其默认逻辑与上述意图冲突（见 KDoc）。
    }

    override fun onUpdateNotification(session: MediaSession, runInForeground: Boolean) {
        super.onUpdateNotification(session, runInForeground || hasResumablePlayback(player))
    }

    /** 引擎或 App 控制器里是否还有可续播内容（兼容 Media3 与 FlickEngine）。 */
    private fun hasResumablePlayback(p: Player? = player): Boolean {
        val exoResumable = p != null && runCatching {
            p.mediaItemCount > 0 && p.playbackState != Player.STATE_IDLE
        }.getOrDefault(false)
        if (exoResumable) return true
        return runCatching {
            val st = AppModel.playback.state.value
            st.currentTrack != null && (st.isPlaying || st.isBuffering || st.queue.isNotEmpty())
        }.getOrDefault(false)
    }

    override fun onDestroy() {
        runCatching { AppModel.playback.releaseExclusiveAudioIfIdle() }
        runCatching { FlickEngineManager.releaseUsbIfIdle() }
        controllerPlayer?.stopObserving()
        controllerPlayer = null
        serviceScope?.cancel()
        serviceScope = null
        // 先摘歌词通道再释放会话：否则会话销毁后 sink 仍被 core 持有，
        // 下一帧歌词会对着已释放的播放器调 replaceMediaItem。
        if (cp.player.app.platform.lyricpush.LyricPushSinks.metadataSink === lyricSink) {
            cp.player.app.platform.lyricpush.LyricPushSinks.metadataSink = null
        }
        lyricSink?.release()
        lyricSink = null
        mediaSession?.release()
        mediaSession = null
        // SharedMedia3Player is released by the playback controller lifecycle.
        player = null
        super.onDestroy()
    }
}
