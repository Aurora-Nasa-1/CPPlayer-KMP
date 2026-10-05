package cp.player.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.media3.session.MediaSessionService
import cp.player.app.version.AppVersion
import cp.player.app.platform.handleMessageNotificationIntent
import cp.player.app.platform.notifyMediaReadPermissionGranted
import cp.player.app.platform.provideAppContext
import cp.player.app.platform.setMediaPermissionRequester
import cp.player.app.platform.setNotificationPermissionRequester

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        instance = this

        provideAppContext(this)
        // 把媒体权限申请入口注册给 app 层（本地扫描 permissionDenied 时经此触发系统授权弹窗）
        setMediaPermissionRequester { requestMediaReadPermission() }
        // 私信通知的权限入口同理：用户在消息页开启某个联系人的推送、但系统通知被关掉时，
        // app 层经此再弹一次授权框（启动时那次用户可能拒过）。
        setNotificationPermissionRequester { requestNotificationPermissionIfNeeded() }
        (application as CPPlayerApplication).backend
        AppModel.markInitialized()
        // 让媒体会话服务随应用启动而创建，通知栏/锁屏/耳机控制才有宿主。
        // ⚠️ 这里必须用 startService 而不是 startForegroundService：
        // media3（1.4.1）的 MediaSessionService 只在「有播放任务」时才调 startForeground()
        // —— MediaNotificationManager.shouldShowNotification 对 idle / 空队列直接返回 false，
        // 空闲时既不 startForeground 也不 stopSelf。用 startForegroundService 在空闲时
        // 拉起它，5 秒契约超时必崩：ForegroundServiceDidNotStartInTimeException。
        // 真正的前台提升由 media3 在播放开始时自己完成（其内部 startForeground 会先
        // startForegroundService 再 startForeground，契约自洽），无需应用代劳。
        startService(Intent(this, PlaybackMediaSessionService::class.java))
        // ⚠️⚠️ 只 startService **不够** —— 这是「改了多次仍然没有 MediaSession」的最后一块。
        //
        // startService(无 action) 进 MediaSessionService.onStartCommand 后两个分支
        // （`isMediaAction` / `isCustomAction`）全不命中，直接 `return START_STICKY`、
        // **零副作用**（1.11.1 复核：分支里多了 super 调用与 isCustomAction 判断，
        // 但「无 action 的 intent」仍然全不命中）。会话虽然在 onCreate 里 addSession 了，
        // 但系统侧（MediaSessionManager 的活跃会话表、锁屏卡片、耳机按键路由）
        // 与服务的**连接**始终没有建立。
        //
        // bindService 是**唯一不引入控制通路**的补齐方式：走 onBind 拿到
        // MediaSessionServiceStub binder，系统据此看到会话；而我们**不创建 MediaController**
        // 去连那条 binder，所以播放仍由应用自己的 PlaybackController 单点驱动，
        // 不会出现「两个播放器抢同一个 ExoPlayer」。
        bindService(
            Intent(MediaSessionService.SERVICE_INTERFACE)
                .setComponent(ComponentName(this, PlaybackMediaSessionService::class.java)),
            mediaSessionConnection,
            Context.BIND_AUTO_CREATE,
        )
        // Android 13+ 媒体通知（含播放控制按钮）受 POST_NOTIFICATIONS 运行时权限约束：
        // 未授权时通知被系统静默拦截 —— 播放器明明在放，通知栏/锁屏却什么都没有。
        // 首次启动即申请；用户拒绝也只是收不到通知，不影响会话本身的锁屏/蓝牙控制。
        requestNotificationPermissionIfNeeded()

        runCatching {
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            AppVersion.init(
                versionName = pkgInfo.versionName ?: "1.0",
                versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                    pkgInfo.longVersionCode.toInt() else pkgInfo.versionCode,
                gitSha = BuildConfig.GIT_SHA,
                releaseChannel = BuildConfig.RELEASE_CHANNEL,
            )
        }

        setContent { App() }

        // 从私信通知点进来：把会话信息交给 app 层。
        // 此刻 `App()` 的处理器**还没注册**（组合尚未跑完），平台层会把这次点击缓存下来，
        // 等处理器注册时补投 —— 所以这里同步调用是安全的。
        handleMessageNotificationIntent(intent)
    }

    /**
     * 应用已在后台时点击通知：Intent 会走这里（配合 `FLAG_ACTIVITY_CLEAR_TOP or SINGLE_TOP`，
     * 复用同一个 Activity 实例而不是再叠一个）。
     *
     * ⚠️ 必须 `setIntent(intent)`：不换掉的话，后续 `getIntent()` 拿到的还是旧的，
     * 旋转屏幕重建时会重新处理一遍已经处理过的 extras。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMessageNotificationIntent(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        // 解绑媒体会话服务。服务本身由 startService 的 START_STICKY 语义与
        // 「正在播放」的前台资格保活，会话不随 Activity 销毁而消失。
        runCatching { unbindService(mediaSessionConnection) }
        // The foreground media service owns the background session and is not stopped
        // here, so rotating or backgrounding the Activity does not interrupt playback.
        super.onDestroy()
    }

    /**
     * 只为「让服务被系统绑定」而存在，**不**持有任何控制器。
     *
     * 刻意不在这里建 `MediaController` 去连服务的 binder：本应用的播放由
     * `AppModel.playback`（单例 PlaybackController）驱动，再引入一条控制器
     * 就会出现两个播放器抢同一个 ExoPlayer。见 onCreate 里的长注释。
     */
    private val mediaSessionConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // 无需持有 binder：会话已在 PlaybackMediaSessionService.onCreate 里
            // addSession，绑定本身即完成「系统可见」。
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    /** Android 13+ 申请通知权限（媒体通知需要）；低版本或已授权时为空操作。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA_READ && hasMediaReadPermission()) {
            // 授权完成 → 通知 app 层（可据此自动重试扫描）
            notifyMediaReadPermissionGranted()
        }
    }

    companion object {
        private const val REQ_MEDIA_READ = 1001
        private const val REQ_NOTIFICATIONS = 1002

        @Volatile
        private var instance: MainActivity? = null

        /** 当前平台所需的媒体读取权限（API 33+ 为 AUDIO+VIDEO，低版本为 READ_EXTERNAL_STORAGE）。 */
        fun requiredMediaReadPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 33) arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
            ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        /** 是否已持有所需的全部媒体读取权限。 */
        fun hasMediaReadPermission(): Boolean {
            val activity = instance ?: return false
            return requiredMediaReadPermissions().all {
                activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }

        /**
         * 供 UI 调用的最小权限请求入口：
         * 本地媒体扫描（LocalMediaSource.scan）报 permissionDenied 时调用此方法引导授权。
         */
        fun requestMediaReadPermission() {
            val activity = instance ?: return
            val missing = requiredMediaReadPermissions()
                .filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                .toTypedArray()
            if (missing.isNotEmpty()) {
                activity.requestPermissions(missing, REQ_MEDIA_READ)
            }
        }
    }
}
