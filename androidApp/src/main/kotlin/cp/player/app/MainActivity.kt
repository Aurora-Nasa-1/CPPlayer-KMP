package cp.player.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cp.player.app.version.AppVersion
import cp.player.kmp.MusicBackend
import cp.player.app.platform.notifyMediaReadPermissionGranted
import cp.player.app.platform.provideAppContext
import cp.player.app.platform.setMediaPermissionRequester
import cp.player.kmp.util.initKmpAndroidContext
import cp.player.kmp.util.toPlatformContext
import cp.player.kmp.util.defaultSettingsStorage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        instance = this

        initKmpAndroidContext(this)
        provideAppContext(this)
        // 把媒体权限申请入口注册给 app 层（本地扫描 permissionDenied 时经此触发系统授权弹窗）
        setMediaPermissionRequester { requestMediaReadPermission() }
        MusicBackend.init(
            context = toPlatformContext(),
            settings = defaultSettingsStorage(),
        )
        AppModel.markInitialized()
        ContextCompat.startForegroundService(
            this,
            Intent(this, PlaybackMediaSessionService::class.java),
        )

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
