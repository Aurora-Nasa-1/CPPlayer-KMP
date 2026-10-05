package cp.player.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import cp.player.app.notify.MessageNotification

/**
 * 私信通知的 Android 实现。
 *
 * ## 为什么用独立渠道
 *
 * 播放通知由 media3 的 `PlaybackMediaSessionService` 用**它自己的**渠道发。
 * 如果私信也塞进那条渠道，用户在系统里关掉「播放控制」就会把私信通知一起关掉 ——
 * 两件事的取舍完全不同，必须能分别开关。
 *
 * ## 点击回跳为什么不用 `MainActivity::class.java`
 *
 * `MainActivity` 在 `app-android`，而 `app` **不能**反向依赖它。这里改用
 * `getLaunchIntentForPackage(packageName)` 拿启动 Intent（就是 MainActivity），
 * 再 `putExtra` 带上会话信息；`app-android` 侧只需读同一个 extras 键。
 * 这与 `setMediaPermissionRequester` 是同一套「回调桥」思路，只是方向相反。
 */
private const val CHANNEL_ID = "cp_messages"
private const val CHANNEL_NAME = "私信消息"
private const val CHANNEL_DESC = "联系人发来的新私信"

internal const val EXTRA_MSG_NOTIFY_PROVIDER = "cp.player.msgNotify.providerId"
internal const val EXTRA_MSG_NOTIFY_PEER = "cp.player.msgNotify.peerUid"
internal const val EXTRA_MSG_NOTIFY_TITLE = "cp.player.msgNotify.title"

/** 由 `MainActivity` 注册（`app` 不依赖 `app-android`）。 */
@Volatile
private var permissionRequester: (() -> Unit)? = null

/** 由 `App` 注册（通知点击后要跳到哪个会话）。 */
@Volatile
private var clickHandler: ((String, Long, String) -> Unit)? = null

/**
 * 进程被系统杀掉后从通知冷启动时，点击事件会**先于** [setOnMessageNotificationClick] 到达。
 * 丢掉它 = 「点了通知没反应」，所以先存下来，等处理器注册时补投。
 */
@Volatile
private var pendingClick: Triple<String, Long, String>? = null

actual fun messageNotificationsSupported(): Boolean = true

actual fun canPostMessageNotifications(): Boolean {
    val ctx = ctxOrNull ?: return false
    // API 24+ 都有 NotificationManager.areNotificationsEnabled()（24 起进公开 API）。
    return runCatching {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    }.getOrDefault(false)
}

actual fun requestMessageNotificationPermission() {
    permissionRequester?.invoke()
}

actual fun postMessageNotification(notification: MessageNotification) {
    val ctx = ctxOrNull ?: return
    if (!canPostMessageNotifications()) return
    runCatching {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationIdOf(notification.key), buildNotification(ctx, notification))
    }
}

actual fun cancelMessageNotification(key: String) {
    val ctx = ctxOrNull ?: return
    runCatching {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notificationIdOf(key))
    }
}

actual fun setOnMessageNotificationClick(handler: ((String, Long, String) -> Unit)?) {
    clickHandler = handler
    // 补投「冷启动时先到的那一次点击」（见 pendingClick 的说明）。
    if (handler != null) {
        val pending = pendingClick ?: return
        pendingClick = null
        handler(pending.first, pending.second, pending.third)
    }
}

// ======================== 与 app-android 的桥 ========================

/** `MainActivity` 启动时注册（内部调 `requestPermissions`）。 */
fun setNotificationPermissionRequester(requester: (() -> Unit)?) {
    permissionRequester = requester
}

/**
 * `MainActivity.onCreate` / `onNewIntent` 调用：从通知的 Intent 里取出会话并派发。
 *
 * 取出后**立刻移除 extras** —— Activity 重建（旋转、进程恢复）会复用同一个 Intent，
 * 不移除就会重复触发一次跳转。
 */
fun handleMessageNotificationIntent(intent: Intent?) {
    val intent = intent ?: return
    val providerId = intent.getStringExtra(EXTRA_MSG_NOTIFY_PROVIDER) ?: return
    val peerUid = intent.getLongExtra(EXTRA_MSG_NOTIFY_PEER, 0L)
    val title = intent.getStringExtra(EXTRA_MSG_NOTIFY_TITLE).orEmpty()
    intent.removeExtra(EXTRA_MSG_NOTIFY_PROVIDER)
    intent.removeExtra(EXTRA_MSG_NOTIFY_PEER)
    intent.removeExtra(EXTRA_MSG_NOTIFY_TITLE)
    if (peerUid == 0L) return

    val handler = clickHandler
    if (handler != null) handler(providerId, peerUid, title) else pendingClick = Triple(providerId, peerUid, title)
}

// ======================== 内部 ========================

/** 通知 id：key 的哈希（稳定且同一会话恒定 ⇒ 后来的覆盖先前的）。 */
private fun notificationIdOf(key: String): Int = key.hashCode()

private fun ensureChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = CHANNEL_DESC
    }
    nm.createNotificationChannel(channel)
}

private fun buildNotification(ctx: Context, n: MessageNotification): Notification {
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(ctx, CHANNEL_ID)
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(ctx)
    }
    builder
        .setContentTitle(n.title)
        .setContentText(n.body)
        // 正文可能很长（对方发了一整段），收起态单行、展开态完整显示。
        .setStyle(Notification.BigTextStyle().bigText(n.body))
        .setSmallIcon(smallIconOf(ctx))
        .setAutoCancel(true)
        .setWhen(System.currentTimeMillis())
        .setShowWhen(true)

    contentIntent(ctx, n)?.let { builder.setContentIntent(it) }
    return builder.build()
}

private fun contentIntent(ctx: Context, n: MessageNotification): PendingIntent? {
    val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
    // CLEAR_TOP + SINGLE_TOP：应用已在后台时复用同一个 MainActivity 实例并走 onNewIntent，
    // 而不是再叠一个（叠一个会让返回键要按两次才退出）。
    launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    launch.putExtra(EXTRA_MSG_NOTIFY_PROVIDER, n.providerId)
    launch.putExtra(EXTRA_MSG_NOTIFY_PEER, n.peerUid)
    launch.putExtra(EXTRA_MSG_NOTIFY_TITLE, n.title)
    return PendingIntent.getActivity(
        ctx,
        notificationIdOf(n.key),
        launch,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * 状态栏小图标：必须是**白色剪影 + 透明底**（系统统一着色），带颜色的图会被糊成色块。
 *
 * 复用 `app-android` 里 `gen_app_icon.py` 生成的那张（`ic_stat_playback`）——
 * `app` 模块引用不到 `app-android` 的 `R`，只能按名字查；查不到再退到系统自带的
 * 通知图标，最后退到应用图标（丑但不至于没有）。
 */
private fun smallIconOf(ctx: Context): Int {
    val own = runCatching {
        ctx.resources.getIdentifier("ic_stat_playback", "drawable", ctx.packageName)
    }.getOrDefault(0)
    if (own != 0) return own
    val fallback = runCatching { android.R.drawable.stat_notify_more }.getOrDefault(0)
    if (fallback != 0) return fallback
    return ctx.applicationInfo.icon
}

/** 供 `MainActivity` 查询：当前是否已持有通知权限（避免重复弹框）。 */
fun hasNotificationPermission(): Boolean {
    if (Build.VERSION.SDK_INT < 33) return canPostMessageNotifications()
    val ctx = ctxOrNull ?: return false
    return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}
