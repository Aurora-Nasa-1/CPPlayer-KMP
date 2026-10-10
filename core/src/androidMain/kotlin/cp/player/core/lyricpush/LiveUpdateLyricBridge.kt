/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/LiveLyricNotificationBridge.kt
 * Changes: 「歌词文本怎么算」的部分（词窗口 / 紧凑 chip）已在 commonMain 的
 *          buildWordWindow / compactText 里实现并单测，这里只负责通知与节流；
 *          副行/主行内容由 LyricContentMode + LyricSecondaryMode 决定（上游是三个散装 int）；
 *          封面改为可选注入；小图标 Canvas 合成。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import cp.player.core.playback.SyncedLyricLine

/**
 * Android 16「实时活动」（Live Update）通知歌词。
 *
 * 关键点有三个（都来自上游的实测，不是猜的）：
 * 1. **标题放歌词**。Android 16 把 `contentTitle` 当作实时活动的主文本；
 *    曲名要放在副标题（`contentText` / summary）里。
 * 2. **状态栏紧凑 chip 只认 `setShortCriticalText`**。它既不用 contentTitle 也不用
 *    contentText；不设的话 chip 会显示曲名或计时，而不是当前歌词。
 * 3. `setRequestPromotedOngoing(true)` 是「请求被提升为实时活动」。
 *    低版本忽略该 extra，仍然收到一条普通歌词通知，不会崩。
 *
 * 节流 220ms：这是上游对齐 SystemUI 刷新节奏调出来的值，更密没有观感收益，
 * 只会让 SystemUI 反复重排。
 */
internal class LiveUpdateLyricBridge(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val smallIcon by lazy { androidx.core.graphics.drawable.IconCompat.createWithBitmap(LyricPushArtwork.silhouetteIcon(48)) }

    private data class Payload(
        val lyric: String,
        val compactLyric: String,
        val secondaryText: String,
    )

    private var enabled = false
    private var content = LyricContentMode.ORIGINAL
    private var display = LiveUpdateDisplayMode.WORD_WINDOW
    private var secondary = LiveUpdateSecondaryMode.SONG

    private var lastPayload: Payload? = null
    private var pendingPayload: Payload? = null
    private var pendingDispatch: Runnable? = null
    private var lastDispatchElapsedMs = 0L

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) clear() else lastPayload = null
    }

    fun setConfig(
        content: LyricContentMode,
        display: LiveUpdateDisplayMode,
        secondary: LiveUpdateSecondaryMode,
    ) {
        if (this.content == content && this.display == display && this.secondary == secondary) return
        this.content = content
        this.display = display
        this.secondary = secondary
        lastPayload = null
        cancelPendingDispatch()
    }

    /** 播放暂停时调用：实时活动应该收起，而不是停在一句上。 */
    fun clear() {
        cancelPendingDispatch()
        lastPayload = null
        lastDispatchElapsedMs = 0L
        runCatching { notificationManager.cancel(NOTIFICATION_ID) }
    }

    fun destroy() {
        enabled = false
        clear()
    }

    fun sendLine(track: LyricPushTrack?, line: SyncedLyricLine?, lines: List<SyncedLyricLine>, index: Int, positionMs: Long) {
        if (!enabled) return
        if (line == null || index < 0) {
            clear()
            return
        }

        val fullLine = line.contentText(content).takeIf { it.isNotBlank() } ?: run { clear(); return }
        val wordIndex = if (content == LyricContentMode.PRONUNCIATION) -1 else currentWordIndex(line.words, positionMs)
        // 词窗口只在「原文 + 逐字 + 滚动模式」下有意义：翻译与注音没有独立时间轴，
        // 硬套窗口会切出半个句子。
        val windowed = display == LiveUpdateDisplayMode.WORD_WINDOW &&
            content == LyricContentMode.ORIGINAL &&
            line.words.isNotEmpty()

        val lyric = if (!windowed) {
            fullLine
        } else {
            val effectiveIndex = if (wordIndex >= 0) wordIndex else nearestWordIndex(line.words, positionMs)
            buildWordWindow(line.words.map { it.text }, effectiveIndex, TITLE_MAX_CODE_POINTS)
                .takeIf { it.isNotBlank() }
                ?: fullLine
        }

        // 整句没有空白（中文/日文整句不分词）时保留原样，否则 chip 会只剩 6 个字加省略号。
        val compact = compactText(lyric, preserveLongToken = lyric.none { it.isWhitespace() })

        val secondaryText = when (secondary) {
            LiveUpdateSecondaryMode.SONG -> track?.let { listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" - ") }
            LiveUpdateSecondaryMode.TRANSLATION -> line.secondaryText(LyricSecondaryMode.TRANSLATION)
            LiveUpdateSecondaryMode.PRONUNCIATION -> line.secondaryText(LyricSecondaryMode.PRONUNCIATION)
        }?.takeIf { it.isNotBlank() } ?: track?.title.orEmpty()

        val payload = Payload(lyric = lyric, compactLyric = compact, secondaryText = secondaryText)
        if (payload == lastPayload) {
            // 待发值可能是一个已被时间轴修正覆盖的旧词，别让它晚到。
            cancelPendingDispatch()
            return
        }
        if (payload == pendingPayload) return

        pendingPayload = payload
        dispatchPending()
    }

    private fun dispatchPending() {
        val payload = pendingPayload ?: return
        val now = SystemClock.elapsedRealtime()
        val remaining = if (lastDispatchElapsedMs == 0L) {
            0L
        } else {
            (MIN_UPDATE_INTERVAL_MS - (now - lastDispatchElapsedMs)).coerceAtLeast(0L)
        }
        if (remaining > 0L) {
            if (pendingDispatch == null) {
                val runnable = Runnable {
                    pendingDispatch = null
                    dispatchPending()
                }
                pendingDispatch = runnable
                mainHandler.postDelayed(runnable, remaining)
            }
            return
        }

        pendingDispatch?.let(mainHandler::removeCallbacks)
        pendingDispatch = null
        pendingPayload = null
        lastDispatchElapsedMs = now

        ensureNotificationChannel(
            context = context,
            channelId = CHANNEL_ID,
            name = CHANNEL_NAME,
            description = CHANNEL_DESCRIPTION,
            importance = NotificationManager.IMPORTANCE_LOW,
        )

        val openApp = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.let {
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(payload.lyric)
            .setContentText(payload.secondaryText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(payload.lyric)
                    .setSummaryText(payload.secondaryText),
            )
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setAutoCancel(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShortCriticalText(payload.compactLyric)
            .setRequestPromotedOngoing(true)
            .build()

        runCatching {
            notificationManager.notify(NOTIFICATION_ID, notification)
            lastPayload = payload
        }.onFailure { Log.w(TAG, "Unable to post live lyric notification", it) }
    }

    private fun cancelPendingDispatch() {
        pendingDispatch?.let(mainHandler::removeCallbacks)
        pendingDispatch = null
        pendingPayload = null
    }

    private companion object {
        const val TAG = "LiveUpdateLyric"
        const val CHANNEL_ID = "cp_player_live_lyric_updates_v1"
        const val CHANNEL_NAME = "实时歌词"
        const val CHANNEL_DESCRIPTION = "在状态栏实时活动 / 灵动区域显示当前歌词"
        const val NOTIFICATION_ID = 0x43504C02
        const val MIN_UPDATE_INTERVAL_MS = 220L
    }
}
