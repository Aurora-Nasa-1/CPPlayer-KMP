package cp.player.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cp.player.app.version.AppVersion
import cp.player.app.platform.notifyMediaReadPermissionGranted
import cp.player.app.platform.provideAppContext
import cp.player.app.platform.setMediaPermissionRequester

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        instance = this

        provideAppContext(this)
        // 把媒体权限申请入口注册给 app 层（本地扫描 permissionDenied 时经此触发系统授权弹窗）
        setMediaPermissionRequester { requestMediaReadPermission() }
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
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        // The foreground media service owns the background session and is not stopped
        // here, so rotating or backgrounding the Activity does not interrupt playback.
        super.onDestroy()
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
