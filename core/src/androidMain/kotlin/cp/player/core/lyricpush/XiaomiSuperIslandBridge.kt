/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/XiaomiSuperIslandLyricBridge.kt
 * Changes:
 *   - **XMSF 断网旁路已补回**（2026-10-10）：三档隔离（关闭 / 标准 / 增强）由
 *     [XmsfIsolationController] 驱动，底层走 [XmsfFirewall] 的 Shizuku wrapped binder。
 *     未授予 Shizuku 权限时自动退化为「直接发送」，即上游 `XMSF_MODE_DISABLED` 的行为。
 *   - 删掉分享卡片、媒体控制按钮（后者需要 app 的矢量图标资源；core 没有 res）。
 *     进度条常驻，作为岛内唯一的动态元素。
 *   - 断句/权重逻辑移到 commonMain 的 XiaomiSuperIslandLayout（可单测）。
 *   - 封面改为可选注入（LyricPushArtworkProvider），强调色由封面平均色推出。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.xzakota.hyper.notification.focus.FocusNotification
import com.xzakota.hyper.notification.focus.util.FocusUtils
import cp.player.core.playback.SyncedLyricLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * HyperOS 超级小岛歌词投放。
 *
 * 协议：**焦点通知**（Focus Notification）。载荷是一组 `miui.focus.*` extras，
 * 由 focus-api 的 Kotlin DSL 生成；真正的渲染在 SystemUI 侧。
 *
 * ### 节流是必须的，不是优化
 * 每次 Focus 更新都可能让 HyperOS **重新展开一次岛**。逐字歌词每帧一变，
 * 不节流的话岛会一直处于「展开中」，用户看到的是闪烁而不是歌词。
 * 这里沿用上游实测的 1.5s 最小渲染间隔：第一句立即出，之后每 1.5s 才动一次
 * 可见文本；更细的逐字节奏交给 Live Update 那条链路。
 */
internal class XiaomiSuperIslandBridge(
    context: Context,
    private val scope: CoroutineScope,
    private val artworkProvider: LyricPushArtworkProvider?,
) {

    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** XMSF 隔离调度：标准档「阻断→发→延迟恢复」，增强档「阻断后保持」。 */
    private val isolation = XmsfIsolationController(appContext, scope)

    private val appIcon by lazy { Icon.createWithBitmap(LyricPushArtwork.appIcon(96)) }
    private val smallIcon by lazy { Icon.createWithBitmap(LyricPushArtwork.silhouetteIcon(48)) }

    @Volatile
    private var enabled = false
    @Volatile
    private var config = XiaomiSuperIslandConfig()

    private var lastPayloadKey: String? = null
    private var lastRenderAtMs = 0L
    private var pendingRender: Job? = null
    private var pendingRequest: RenderRequest? = null
    private var pauseDismissJob: Job? = null

    /** 当前曲目的封面缩放缓存（**按对象身份**命中；换歌才重建）。 */
    private var artworkSource: Bitmap? = null
    private var artworkCover: Icon? = null
    private var artworkIsland: Icon? = null
    private var artworkSmall: Icon? = null

    private data class RenderRequest(
        val track: LyricPushTrack,
        val displayLyric: String,
        val fullLyric: String,
        val progress: Int,
        val accentColor: Int,
        val trackKey: String,
    )

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        lastPayloadKey = null
        if (enabled) {
            ensureChannel()
            // 冷启动也要能用：开启渠道时先挂上 Shizuku keepalive。
            // 未授权 / 未装 Shizuku 时内部只安排重试，**不会弹窗**（见 ShizukuKeepAlive）。
            ShizukuKeepAlive.ensureBound(appContext)
        } else {
            clear()
            ShizukuKeepAlive.unbind()
        }
    }

    fun setConfig(config: XiaomiSuperIslandConfig) {
        val sanitized = config.sanitized()
        // 隔离档位必须**先于**早退应用：`XiaomiSuperIslandConfig()` 的默认档就是 STANDARD，
        // 若把 applyMode 放在 `if (this.config == sanitized) return` 之后，
        // 「配置恰好等于默认值」这条最常见路径会让控制器一直停在 OFF。
        isolation.applyMode(sanitized.xmsfMode, sanitized.xmsfBlockDurationMs)
        if (this.config == sanitized) return
        this.config = sanitized
        lastPayloadKey = null
    }

    /** 当前设备是否可能渲染超级岛（不满足时上游会静默降级为普通通知）。 */
    fun isSupported(): Boolean = runCatching { FocusUtils.isSupportIsland() }.getOrDefault(false)

    fun sendFrame(
        track: LyricPushTrack?,
        lines: List<SyncedLyricLine>,
        index: Int,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        if (!enabled || track == null || index < 0) return
        val line = lines.getOrNull(index) ?: return

        pauseDismissJob?.cancel()
        pauseDismissJob = null

        val active = config
        val fullLyric = line.contentText(active.content).takeIf { it.isNotBlank() } ?: return

        // 标准布局显示「当前滚动到的局部」，完整布局显示整句。
        val displayLyric = when {
            active.lyricMode == XiaomiSuperIslandConfig.LyricMode.FULL -> fullLyric
            !active.scrollingEnabled -> fullLyric
            else -> {
                val wordIndex = currentWordIndex(line.words, positionMs)
                if (wordIndex < 0 || line.words.isEmpty()) {
                    fullLyric
                } else {
                    buildWordWindow(line.words.map { it.text }, wordIndex, TITLE_MAX_CODE_POINTS)
                        .takeIf { it.isNotBlank() } ?: fullLyric
                }
            }
        }

        val progress = progressPercent(positionMs, durationMs)
        val accent = resolveAccentColor(active)
        val trackKey = track.trackKey()
        val payloadKey = listOf(trackKey, displayLyric, fullLyric, progress, active.hashCode(), accent)
            .joinToString("|")
        if (payloadKey == lastPayloadKey) return
        lastPayloadKey = payloadKey

        renderThrottled(
            RenderRequest(
                track = track,
                displayLyric = displayLyric,
                fullLyric = fullLyric,
                progress = progress,
                accentColor = accent,
                trackKey = trackKey,
            ),
        )
    }

    /** 暂停时按配置延迟收起岛（0 = 立即收起）。 */
    fun onPlaybackPaused() {
        if (!enabled) return
        // 暂停即恢复网络（移植指南 §5.4）：增强档靠这一句结束「播放期保持阻断」。
        isolation.restoreNow()
        pauseDismissJob?.cancel()
        val dismissDelay = config.dismissDelayMs.toLong()
        if (dismissDelay <= 0L) {
            clear()
        } else {
            pauseDismissJob = scope.launch {
                delay(dismissDelay)
                clear()
            }
        }
    }

    fun clear() {
        // 清空歌词 / 关闭功能一律恢复网络，别把 XMSF 留在断网状态。
        isolation.restoreNow()
        pauseDismissJob?.cancel()
        pauseDismissJob = null
        pendingRender?.cancel()
        pendingRender = null
        pendingRequest = null
        lastRenderAtMs = 0L
        lastPayloadKey = null
        runCatching { XiaomiSuperIslandLyricService.stop(appContext) }
        runCatching { notificationManager.cancel(XiaomiSuperIslandLyricService.NOTIFICATION_ID) }
    }

    fun destroy() {
        enabled = false
        clear()
        isolation.restoreNow()
        artworkSource = null
        artworkCover = null
        artworkIsland = null
        artworkSmall = null
    }

    private fun renderThrottled(request: RenderRequest) {
        val now = System.currentTimeMillis()
        val remaining = (lastRenderAtMs + MIN_RENDER_INTERVAL_MS - now).coerceAtLeast(0L)
        if (remaining == 0L) {
            pendingRender?.cancel()
            pendingRender = null
            pendingRequest = null
            lastRenderAtMs = now
            dispatch(buildNotification(request), request.trackKey)
            return
        }

        // 存最新请求而不是排队：中间那些帧已经过期了，逐帧补发只会让岛持续闪烁。
        pendingRequest = request
        pendingRender?.cancel()
        pendingRender = scope.launch {
            delay(remaining)
            val latest = pendingRequest ?: return@launch
            pendingRequest = null
            pendingRender = null
            lastRenderAtMs = System.currentTimeMillis()
            dispatch(buildNotification(latest), latest.trackKey)
        }
    }

    private fun dispatch(notification: Notification, trackKey: String) {
        if (!enabled) return
        // 隔离窗口：标准档「阻断 → 发通知 → 延迟恢复」，增强档「阻断后保持到暂停」。
        // 未授予 Shizuku 权限时 aroundPublish 直接发出去 —— 基础可用性不受隔离影响。
        isolation.aroundPublish {
            XiaomiSuperIslandLyricService.publish(appContext, notification)
        }
        Log.d(TAG, "Published super island lyric for $trackKey")
    }

    private fun buildNotification(request: RenderRequest): Notification {
        val active = config
        val artwork = artworkProvider?.currentArtwork()
        val coverIcon = refreshArtwork(artwork, request.trackKey)
        val accentHex = request.accentColor.toHexArgb()
        val textColor = if (active.textColorEnabled) accentHex else DEFAULT_NEUTRAL_TEXT
        val progressColor = if (active.progressColorEnabled) accentHex else DEFAULT_NEUTRAL_TEXT
        val titleWithArtist = listOf(request.track.title, request.track.artist)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { request.track.title }

        val extras = FocusNotification.buildV3 {
            business = "lyric_display"
            isShowNotification = true
            enableFloat = false
            updatable = true
            islandFirstFloat = false
            aodTitle = request.displayLyric.take(20).ifBlank { MUSIC_NOTE }

            ticker = request.displayLyric.ifBlank { request.fullLyric }

            val coverKey = coverIcon?.let { createPicture(PIC_COVER, it) }
            val islandKey = coverIslandIcon?.let { createPicture(PIC_ISLAND, it) }
            val smallIslandKey = coverSmallIcon?.let { createPicture(PIC_ISLAND_SMALL, it) }
            val appKey = createPicture(PIC_APP, appIcon)
            tickerPic = coverKey ?: appKey

            chatInfo {
                picProfile = coverKey
                title = request.fullLyric
                content = titleWithArtist
                appIconPkg = appContext.packageName
            }

            progressInfo {
                progress = request.progress
                colorProgress = progressColor
                colorProgressEnd = progressColor
            }

            island {
                islandProperty = 1
                if (active.textColorEnabled) highlightColor = accentHex

                bigIslandArea {
                    val showLeftCover = islandKey != null &&
                        (active.lyricMode != XiaomiSuperIslandConfig.LyricMode.FULL || active.fullLyricShowLeftCover)
                    val leftWeight = XiaomiSuperIslandLayout.weightForCharacters(
                        if (showLeftCover) active.leftWithCoverTextChars else active.leftWithoutCoverTextChars,
                    )
                    val rightWeight = XiaomiSuperIslandLayout.weightForCharacters(active.rightTextChars)
                    val split = if (active.lyricMode == XiaomiSuperIslandConfig.LyricMode.FULL) {
                        XiaomiSuperIslandLayout.splitFullLyric(
                            text = request.fullLyric,
                            showLeftCover = showLeftCover,
                            leftMaxWeight = leftWeight,
                            rightMaxWeight = rightWeight,
                        )
                    } else {
                        XiaomiSuperIslandLayout.Split(
                            left = XiaomiSuperIslandLayout.takeByWeight(titleWithArtist, leftWeight),
                            right = XiaomiSuperIslandLayout.takeByWeight(request.displayLyric, rightWeight),
                        )
                    }
                    imageTextInfoLeft {
                        type = 1
                        if (showLeftCover) {
                            picInfo {
                                type = 1
                                pic = islandKey
                            }
                        }
                        textInfo {
                            title = split.left.ifBlank { MUSIC_NOTE }
                            showHighlightColor = active.textColorEnabled
                            narrowFont = false
                        }
                    }
                    textInfo = com.xzakota.hyper.notification.island.model.TextInfo().apply {
                        title = split.right.ifBlank { MUSIC_NOTE }
                        showHighlightColor = active.textColorEnabled
                        narrowFont = false
                    }
                }

                smallIslandArea {
                    combinePicInfo {
                        if (smallIslandKey != null) {
                            picInfo {
                                type = 1
                                pic = smallIslandKey
                            }
                        }
                        progressInfo {
                            progress = request.progress
                            colorReach = if (active.textColorEnabled) accentHex else DEFAULT_NEUTRAL_TEXT
                            colorUnReach = SEEK_BAR_TRACK
                        }
                    }
                }
            }
        }

        val openApp = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.let {
                PendingIntent.getActivity(
                    appContext,
                    CONTENT_INTENT_REQUEST,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        // ⚠️ minSdk = 24：`Notification.Builder(Context, String)` 是 **API 26** 才有的构造器，
        // 低版本直接 NoSuchMethodError。渠道本身也是 API 26 才存在，所以这里必须分支。
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(appContext, CHANNEL_ID)
        } else {
            Notification.Builder(appContext)
        }
        builder
            .setSmallIcon(smallIcon)
            .setContentTitle(request.fullLyric)
            .setContentText(titleWithArtist)
            .setSubText(appContext.packageName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(request.accentColor)
            .addExtras(extras)
        if (openApp != null) builder.setContentIntent(openApp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    // ---- 封面缩放缓存 ----

    private val coverIslandIcon: Icon? get() = artworkIsland
    private val coverSmallIcon: Icon? get() = artworkSmall

    /**
     * 按**对象身份**命中缓存重建封面图标。
     *
     * 为什么按身份而不是按内容：焦点通知的图片会被 parcel 给 SystemUI，
     * 每帧重建四份全尺寸 Icon 会让岛反复上传同一张专辑封面。播放器在换歌时
     * 换的是 Bitmap 对象，所以身份比较正好落在「换歌才重建」这个粒度上。
     */
    private fun refreshArtwork(artwork: Bitmap?, trackKey: String): Icon? {
        if (artwork == null || artwork.isRecycled) {
            artworkSource = null
            artworkCover = null
            artworkIsland = null
            artworkSmall = null
            return null
        }
        if (artworkSource === artwork) return artworkCover

        artworkSource = artwork
        artworkCover = Icon.createWithBitmap(LyricPushArtwork.rounded(artwork, 192))
        artworkIsland = Icon.createWithBitmap(LyricPushArtwork.scale(artwork, 120))
        artworkSmall = Icon.createWithBitmap(LyricPushArtwork.scale(artwork, 88))
        return artworkCover
    }

    /**
     * 强调色：自定义色优先，否则取封面平均色；都没有时用默认蓝。
     *
     * 用平均色而不是「取最多的色」：专辑封面通常是渐变构图，量化取主色容易得到一个
     * 几乎看不清的深色，而平均色无论封面多花都稳定落在中间调上。
     */
    private fun resolveAccentColor(active: XiaomiSuperIslandConfig): Int {
        if (active.colorSource == XiaomiSuperIslandConfig.IslandColorSource.CUSTOM) {
            return active.customColor
        }
        val artwork = artworkSource ?: artworkProvider?.currentArtwork() ?: return LyricPushArtwork.DEFAULT_ACCENT
        return averageColor(artwork) ?: LyricPushArtwork.DEFAULT_ACCENT
    }

    /** 采样取平均色；图过小或全透明时返回 null（调用方回退默认色）。 */
    private fun averageColor(source: Bitmap): Int? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val step = (minOf(source.width, source.height) / 8).coerceAtLeast(1)
        var red = 0L
        var green = 0L
        var blue = 0L
        var samples = 0
        var y = step / 2
        while (y < source.height) {
            var x = step / 2
            while (x < source.width) {
                val pixel = source.getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha > 0x20) {
                    red += (pixel shr 16) and 0xFF
                    green += (pixel shr 8) and 0xFF
                    blue += pixel and 0xFF
                    samples++
                }
                x += step
            }
            y += step
        }
        if (samples == 0) return null
        val r = (red / samples).coerceIn(0, 255).toInt()
        val g = (green / samples).coerceIn(0, 255).toInt()
        val b = (blue / samples).coerceIn(0, 255).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun ensureChannel() {
        ensureNotificationChannel(
            context = appContext,
            channelId = CHANNEL_ID,
            name = CHANNEL_NAME,
            description = CHANNEL_DESCRIPTION,
            importance = NotificationManager.IMPORTANCE_HIGH,
        )
    }

    private companion object {
        const val TAG = "SuperIslandLyric"

        /**
         * 渠道 id 带 `_v2` 后缀。
         *
         * 上游踩过的坑：渠道一旦建过就不能改重要性，早期版本建成了 LOW，
         * HyperOS 因此不把它当焦点通知。要修只能换 id。
         */
        const val CHANNEL_ID = "cp_player_super_island_lyric_v2"
        const val CHANNEL_NAME = "小米超级岛歌词"
        const val CHANNEL_DESCRIPTION = "在 HyperOS 超级岛显示当前歌词"

        const val MIN_RENDER_INTERVAL_MS = 1_500L
        const val CONTENT_INTENT_REQUEST = 3600

        const val PIC_COVER = "miui.focus.pic_notification_cover"
        const val PIC_APP = "miui.focus.pic_app"
        const val PIC_ISLAND = "miui.focus.pic_island"
        const val PIC_ISLAND_SMALL = "miui.land.pic_island"

        const val MUSIC_NOTE = "♪"
        const val DEFAULT_NEUTRAL_TEXT = "#757575"
        const val SEEK_BAR_TRACK = "#333333"
    }
}
