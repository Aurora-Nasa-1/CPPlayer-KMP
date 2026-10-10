/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream references（同一个 helper 在 Halcyon 的 LyriconBridge / SuperLyricBridge /
 * OPlusLyricHandler / LiveLyricNotificationBridge 里各存了一份近似实现）：
 *   - LyricLine.displayDurationMs / primaryEndMs
 *   - XiaomiSuperIslandLyricBridge.cachedArtworkResources / scaleArtwork / roundedNotificationArtwork
 * Changes: 合并成一份共用实现（三份必然漂移）；专辑封面改为**可选注入**（见
 *          LyricPushArtworkProvider），core 不引入图片加载依赖；小图标由
 *          Canvas 合成而非 R.drawable，core 没有资源目录。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import cp.player.core.playback.SyncedLyricLine

/** 无 `endTime` 时的兜底时长（与 Halcyon 一致）。 */
internal const val FALLBACK_LINE_DURATION_MS = 3_000L

/** 曲目身份键：用于「是不是同一首歌」的去重。 */
internal fun LyricPushTrack.trackKey(): String =
    listOf(id, sourceId.orEmpty(), title, artist, album).joinToString("|")

/**
 * 一行的结束时间。
 *
 * 优先级：显式 [SyncedLyricLine.endTime] → 最后一个逐字词的结束 → 下一行的开始 →
 * 「本行 + 3s」。**不能只用下一行开始时间**：最后一行没有下一行，会退化成一个
 * 很短的窗口，接收端表现为「最后一句一闪而过」。
 */
internal fun SyncedLyricLine.lineEndMs(
    lines: List<SyncedLyricLine>,
    index: Int,
): Long {
    endTime?.takeIf { it > time }?.let { return it }
    words.maxOfOrNull { it.endTime }?.takeIf { it > time }?.let { return it }
    lines.getOrNull(index + 1)?.time?.takeIf { it > time }?.let { return it }
    return time + FALLBACK_LINE_DURATION_MS
}

/** 播放进度百分比（0..100）；时长未知时返回 0。 */
internal fun progressPercent(positionMs: Long, durationMs: Long): Int =
    if (durationMs > 0L) {
        ((positionMs.coerceIn(0L, durationMs) * 100L) / durationMs).toInt().coerceIn(0, 100)
    } else {
        0
    }

/** `#AARRGGBB` 形式的十六进制色值（focus-api 的岛上色字段要这个格式）。 */
internal fun Int.toHexArgb(): String {
    val hex = (0xFFFFFF and this).toString(16).uppercase()
    return "#FF" + "0".repeat(6 - hex.length) + hex
}

/**
 * 专辑封面来源。
 *
 * **存在的理由**：core 没有图片加载器（Coil 在 app 模块），而超级岛与通知的观感
 * 很大程度取决于封面。与其在 core 里塞一套下载 + 缓存，不如让已经持有
 * 播放器封面状态的 app 侧注入 —— 拿不到就返回 null，各 bridge 都设计了无封面的
 * 退化路径。
 *
 * ⚠️ 返回的 Bitmap 由调用方负责缩放，**不要**把它放进 Intent（Binder 有 1MB 上限，
 * 一张专辑封面就能超）。
 */
fun interface LyricPushArtworkProvider {
    /** 当前曲目的封面；null = 暂无（不要抛异常）。 */
    fun currentArtwork(): Bitmap?
}

/** 需要「当前封面」的 bridge 共享的归一化工具。 */
internal object LyricPushArtwork {

    /** 缩放到 [size]×[size]；尺寸已合适时**返回原对象**（身份比较才能命中缓存）。 */
    fun scale(source: Bitmap, size: Int): Bitmap =
        if (source.width == size && source.height == size) source
        else Bitmap.createScaledBitmap(source, size, size, true)

    /** 圆角方形封面（通知大图用）。 */
    fun rounded(source: Bitmap, size: Int, radiusFraction: Float = 0.0625f): Bitmap {
        val scaled = scale(source, size)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(
                scaled,
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        val radius = size * radiusFraction
        Canvas(output).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        return output
    }

    /**
     * 用 Canvas 合成一个应用图标（core 没有 res/drawable）。
     *
     * 画的是「圆角方块 + 一个八分音符」：音叉状符头 + 符干 + 旗。纯几何，不依赖字体，
     * 因此在任何 ROM 上渲染结果一致 —— 用 `Canvas.drawText("♪")` 则会因字体缺字变方块。
     */
    fun appIcon(size: Int, accentColor: Int = DEFAULT_ACCENT): Bitmap = drawNote(size, accentColor, true)

    /**
     * 通知栏小图标：**透明底 + 白色音符**。
     *
     * 小图标在系统里只取 alpha 通道（颜色由系统决定），所以这里必须是剪影而不是彩图 ——
     * 用带底色的方形图标会被裁成一个纯白方块。
     */
    fun silhouetteIcon(size: Int): Bitmap = drawNote(size, Color.WHITE, false)

    private fun drawNote(size: Int, accentColor: Int, withBackground: Boolean): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        if (withBackground) {
            val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
            canvas.drawRoundRect(
                RectF(0f, 0f, size.toFloat(), size.toFloat()),
                size * 0.28f,
                size * 0.28f,
                accent,
            )
        }

        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val unit = size / 16f
        // 符干
        canvas.drawRoundRect(
            RectF(unit * 9f, unit * 4.5f, unit * 10.4f, unit * 11.5f),
            unit * 0.7f,
            unit * 0.7f,
            note,
        )
        // 旗：用直线段拼的四边形。
        // 刻意不用 Path.quadraticTo / quadraticBezierTo —— 两者在不同 compileSdk 上的
        // 可见性不一致（一个被移除、一个是 Kotlin 扩展），而这里只是个装饰性图形，
        // 不值得为它绑定某个 SDK 版本。
        val flag = android.graphics.Path().apply {
            moveTo(unit * 10.4f, unit * 4.5f)
            lineTo(unit * 12.9f, unit * 6.1f)
            lineTo(unit * 12.2f, unit * 8.4f)
            lineTo(unit * 10.4f, unit * 6.6f)
            close()
        }
        canvas.drawPath(flag, note)
        // 符头（斜置椭圆）
        canvas.save()
        canvas.rotate(-20f, unit * 7.6f, unit * 11.6f)
        canvas.drawOval(
            RectF(unit * 4.8f, unit * 10.2f, unit * 10.4f, unit * 13.0f),
            note,
        )
        canvas.restore()
        return output
    }

    const val DEFAULT_ACCENT = 0xFF3482FF.toInt()
}

/** 确保通知渠道存在（已存在则不动 —— Android 不允许修改已有渠道的重要性）。 */
internal fun ensureNotificationChannel(
    context: Context,
    channelId: String,
    name: String,
    description: String,
    importance: Int,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(channelId) != null) return

    val channel = NotificationChannel(channelId, name, importance).apply {
        this.description = description
        setShowBadge(false)
        setSound(null, null)
        enableVibration(false)
    }
    manager.createNotificationChannel(channel)
}
