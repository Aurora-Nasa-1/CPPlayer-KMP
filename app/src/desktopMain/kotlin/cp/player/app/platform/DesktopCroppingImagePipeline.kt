package cp.player.app.platform

import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Precision
import cp.player.app.ui.util.CpCropSquareExtraKey
import cp.player.app.ui.util.CpOriginalUrlExtraKey
import cp.player.app.ui.util.canonicalCoverBaseUrl
import cp.player.app.ui.util.quantizeCoverBucketPx
import cp.player.core.provider.HostTrafficPolicy
import cp.player.core.util.DesktopDataDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 桌面端「裁剪缩略图」独立磁盘缓存（`~/.cpplayer/cache/images/cropped/`）。
 *
 * 与 Coil 原生 `DiskCache`（保存原始图/CDN 原响应）构成双层结构：
 * - 原始层：避免同一图因不同尺寸反复下载；
 * - 裁剪层：把按目标槽位居中裁剪并降采样后的 WebP/JPEG 小图落盘，二次加载与重启后
 *   跳过大图解码与网络请求，且只将真正需要的像素尺寸送入内存。
 */
internal object DesktopCroppedThumbDiskCache {

    private const val MAX_CACHE_BYTES = 128L * 1024 * 1024
    private const val PRUNE_CHECK_INTERVAL_WRITES = 32
    internal val STANDARD_BUCKETS_DESC = intArrayOf(1440, 1024, 512, 256, 128)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightWrites = ConcurrentHashMap.newKeySet<String>()
    private val writeCount = AtomicLong(0L)

    private fun rootDir(): File = DesktopDataDir.directory("cache/images/cropped")

    fun cacheKey(canonicalUrl: String, width: Int, height: Int, cropSquare: Boolean): String {
        val raw = "$canonicalUrl|${width}x${height}|${if (cropSquare) "c" else "f"}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0F])
            }
        }
    }

    fun find(canonicalUrl: String, width: Int, height: Int, cropSquare: Boolean): File? {
        val dir = rootDir()
        val key = cacheKey(canonicalUrl, width, height, cropSquare)
        val webp = File(dir, "$key.webp")
        if (webp.isFile && webp.length() > 0L) return webp
        val jpg = File(dir, "$key.jpg")
        if (jpg.isFile && jpg.length() > 0L) return jpg
        return null
    }

    /**
     * 按优先级在本地裁剪缓存中查找可复用源文件，避免同一张封面在不同档位之间重复走网络下载：
     * 1. 精确命中目标宽高；
     * 2. 命中任意更高分辨率档位（>= targetPx），交给本地 Skia 降采样/裁剪后直接复用；
     * 3. 处于托盘后台/省流/离线模式时，若没有任何更高档位，也允许复用已有更低档位避免偷跑流量。
     */
    fun findBestLocal(
        canonicalUrl: String,
        targetW: Int,
        targetH: Int,
        cropSquare: Boolean,
        allowSmallerOnConstrained: Boolean,
    ): File? {
        find(canonicalUrl, targetW, targetH, cropSquare)?.let { return it }
        val minNeeded = max(targetW, targetH)
        for (bucket in STANDARD_BUCKETS_DESC) {
            if (bucket < minNeeded) continue
            find(canonicalUrl, bucket, bucket, cropSquare)?.let { return it }
            if (cropSquare) {
                find(canonicalUrl, bucket, bucket, cropSquare = false)?.let { return it }
            }
        }
        if (allowSmallerOnConstrained) {
            for (bucket in STANDARD_BUCKETS_DESC) {
                if (bucket >= minNeeded) continue
                find(canonicalUrl, bucket, bucket, cropSquare)?.let { return it }
                if (cropSquare) {
                    find(canonicalUrl, bucket, bucket, cropSquare = false)?.let { return it }
                }
            }
        }
        return null
    }

    fun isFromCroppedCache(file: File): Boolean = runCatching {
        val rootPath = rootDir().canonicalPath
        file.canonicalPath.startsWith(rootPath)
    }.getOrDefault(false)

    fun putAsync(canonicalUrl: String, width: Int, height: Int, cropSquare: Boolean, bitmap: Bitmap) {
        val key = cacheKey(canonicalUrl, width, height, cropSquare)
        if (!inFlightWrites.add(key)) return
        val bytes = encodeBitmapBytes(bitmap)
        if (bytes == null) {
            inFlightWrites.remove(key)
            return
        }
        val (ext, encoded) = bytes
        ioScope.launch {
            try {
                val dir = rootDir()
                val target = File(dir, "$key.$ext")
                if (target.isFile && target.length() > 0L) return@launch
                val tmp = File(dir, "$key.$ext.part")
                tmp.writeBytes(encoded)
                runCatching {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.onFailure {
                    runCatching {
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                if (writeCount.incrementAndGet() % PRUNE_CHECK_INTERVAL_WRITES == 0L) {
                    pruneIfNeeded(dir)
                }
            } catch (_: Throwable) {
            } finally {
                inFlightWrites.remove(key)
            }
        }
    }

    fun totalBytes(): Long = runCatching {
        rootDir().listFiles()?.sumOf { if (it.isFile && !it.name.endsWith(".part")) it.length() else 0L } ?: 0L
    }.getOrDefault(0L)

    fun clear() {
        runCatching {
            rootDir().listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    private fun encodeBitmapBytes(bitmap: Bitmap): Pair<String, ByteArray>? {
        return runCatching {
            Image.makeFromBitmap(bitmap).use { img ->
                val webp = img.encodeToData(EncodedImageFormat.WEBP, 85)
                if (webp != null) {
                    webp.use { "webp" to it.bytes }
                } else {
                    img.encodeToData(EncodedImageFormat.JPEG, 88)?.use { "jpg" to it.bytes }
                }
            }
        }.getOrNull()
    }

    private fun pruneIfNeeded(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES * 3L / 4L) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= len
            }
        }
    }
}

/**
 * 桌面端双层缓存 Fetcher：
 * 1. 先查裁剪缩略图盘缓存（命中即直接读小图，零网络/零原始大图 IO）；
 * 2. 未命中时，若原始图盘缓存 `orig:<canonicalUrl>` 已存在，直接复用原图给裁剪解码器，避免重复联网；
 * 3. 均未命中则交回 Coil 默认 Fetcher（网络或本地文件）拉取并缓存原图。
 */
internal class DesktopCachedCroppedFetcher(
    private val file: File,
    private val options: Options,
    private val diskCacheKey: String?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val ext = file.extension.lowercase()
        val mime = when (ext) {
            "webp" -> "image/webp"
            "png" -> "image/png"
            else -> "image/jpeg"
        }
        return SourceFetchResult(
            source = ImageSource(
                file = file.toOkioPath(),
                fileSystem = options.fileSystem,
                diskCacheKey = diskCacheKey,
            ),
            mimeType = mime,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!options.diskCachePolicy.readEnabled) return null
            val targetW = (options.size.width as? Dimension.Pixels)?.px ?: return null
            val targetH = (options.size.height as? Dimension.Pixels)?.px ?: return null
            val url = data.toString()
            if (url.isBlank()) return null
            val canonical = options.getExtra(CpOriginalUrlExtraKey) ?: canonicalCoverBaseUrl(url)
            val cropSquare = options.getExtra(CpCropSquareExtraKey)
            val bucketW = quantizeCoverBucketPx(targetW)
            val bucketH = quantizeCoverBucketPx(targetH)

            val allowConstrainedReuse = HostTrafficPolicy.shouldDeferNetworkFallback()
            DesktopCroppedThumbDiskCache.findBestLocal(
                canonicalUrl = canonical,
                targetW = bucketW,
                targetH = bucketH,
                cropSquare = cropSquare,
                allowSmallerOnConstrained = allowConstrainedReuse,
            )?.let { local ->
                return DesktopCachedCroppedFetcher(local, options, diskCacheKey = null)
            }

            // 如果原始图或任意可用分桶图已经在 Coil DiskCache 中，直接复用避免二次下载
            val diskCache = imageLoader.diskCache
            if (diskCache != null) {
                val origKey = "orig:$canonical"
                diskCache.openSnapshot(origKey)?.use { snapshot ->
                    val origFile = snapshot.data.toFile()
                    if (origFile.isFile && origFile.length() > 0L) {
                        return DesktopCachedCroppedFetcher(origFile, options, diskCacheKey = origKey)
                    }
                }
                val minNeeded = max(bucketW, bucketH)
                for (b in DesktopCroppedThumbDiskCache.STANDARD_BUCKETS_DESC) {
                    if (b < minNeeded && !allowConstrainedReuse) continue
                    val key = "cover_disk:$canonical:$b"
                    diskCache.openSnapshot(key)?.use { snapshot ->
                        val file = snapshot.data.toFile()
                        if (file.isFile && file.length() > 0L) {
                            return DesktopCachedCroppedFetcher(file, options, diskCacheKey = key)
                        }
                    }
                }
            }
            return null
        }
    }
}

/**
 * 桌面端按目标像素裁剪并降采样的 Skia 解码器：
 * - 通过 `Codec` 头信息读取原图尺寸，不把整张大图直接留在内存；
 * - 若编解码器支持缩放采样（JPEG/WebP 等），先按近似倍率抽样解码，再居中裁剪 + 缩放到精确槽位；
 * - 将结果异步写入 [DesktopCroppedThumbDiskCache]，并只把最终精确大小的 [Bitmap] 交给 Coil `MemoryCache`。
 */
internal class DesktopCroppingDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val canonicalUrl: String?,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val fileOnDisk = source.fileOrNull()?.toFile()
        val alreadyCropped = fileOnDisk != null && DesktopCroppedThumbDiskCache.isFromCroppedCache(fileOnDisk)
        val bytes = fileOnDisk?.readBytes() ?: source.source().use { it.readByteArray() }
        if (bytes.isEmpty()) return null

        val data = Data.makeFromBytes(bytes)
        try {
            val codec = Codec.makeFromData(data)
            if (codec == null) {
                val img = Image.makeFromEncoded(bytes)
                return img.use { decodeAndCropFromImage(it, alreadyCropped) }
            }
            return codec.use { c ->
                val srcW = c.width
                val srcH = c.height
                if (srcW <= 0 || srcH <= 0) return null

                val cropSquare = options.getExtra(CpCropSquareExtraKey)
                val reqW = (options.size.width as? Dimension.Pixels)?.px
                val reqH = (options.size.height as? Dimension.Pixels)?.px

                val (dstW, dstH, needCrop) = computeTargetBounds(
                    srcW = srcW,
                    srcH = srcH,
                    reqW = reqW,
                    reqH = reqH,
                    cropSquare = cropSquare,
                    precision = options.precision,
                )

                if (!needCrop && dstW == srcW && dstH == srcH) {
                    val bitmap = Bitmap()
                    if (!bitmap.allocPixels(ImageInfo.makeN32Premul(srcW, srcH))) return null
                    c.readPixels(bitmap)
                    return DecodeResult(image = bitmap.asImage(), isSampled = false)
                }

                // Skiko 的 Codec 不暴露 getScaledDimensions，且任意缩放读像素会触发 kInvalidScale；
                // 先解码到临时原尺寸 Bitmap 再居中裁剪缩放到目标尺寸，并在 finally 立即释放临时缓冲
                val srcBitmap = Bitmap()
                if (!srcBitmap.allocPixels(ImageInfo.makeN32Premul(srcW, srcH))) return null
                try {
                    c.readPixels(srcBitmap)
                    val outBitmap = Bitmap()
                    if (!outBitmap.allocN32Pixels(dstW, dstH, opaque = false)) {
                        outBitmap.close()
                        return null
                    }
                    Image.makeFromBitmap(srcBitmap).use { srcImg ->
                        Canvas(outBitmap).use { canvas ->
                            val srcRect = computeCenterCropRect(srcW, srcH, dstW, dstH, needCrop)
                            val dstRect = Rect.makeWH(dstW.toFloat(), dstH.toFloat())
                            canvas.drawImageRect(srcImg, srcRect, dstRect, SamplingMode.LINEAR, null, true)
                        }
                    }
                    val exactCacheHit = alreadyCropped && dstW == srcW && dstH == srcH
                    if (!exactCacheHit && options.diskCachePolicy.writeEnabled && canonicalUrl != null) {
                        DesktopCroppedThumbDiskCache.putAsync(canonicalUrl, dstW, dstH, cropSquare, outBitmap)
                    }
                    return DecodeResult(
                        image = outBitmap.asImage(),
                        isSampled = dstW < srcW || dstH < srcH,
                    )
                } finally {
                    srcBitmap.close()
                }
            }
        } finally {
            data.close()
        }
    }

    private fun decodeAndCropFromImage(img: Image, alreadyCropped: Boolean): DecodeResult? {
        val srcW = img.width
        val srcH = img.height
        if (srcW <= 0 || srcH <= 0) return null
        val cropSquare = options.getExtra(CpCropSquareExtraKey)
        val reqW = (options.size.width as? Dimension.Pixels)?.px
        val reqH = (options.size.height as? Dimension.Pixels)?.px
        val (dstW, dstH, needCrop) = computeTargetBounds(
            srcW, srcH, reqW, reqH, cropSquare, options.precision,
        )
        val outBitmap = Bitmap()
        if (!outBitmap.allocN32Pixels(dstW, dstH, opaque = false)) return null
        Canvas(outBitmap).use { canvas ->
            val srcRect = computeCenterCropRect(srcW, srcH, dstW, dstH, needCrop)
            val dstRect = Rect.makeWH(dstW.toFloat(), dstH.toFloat())
            canvas.drawImageRect(img, srcRect, dstRect, SamplingMode.LINEAR, null, true)
        }
        val exactCacheHit = alreadyCropped && dstW == srcW && dstH == srcH
        if (!exactCacheHit && options.diskCachePolicy.writeEnabled && canonicalUrl != null) {
            DesktopCroppedThumbDiskCache.putAsync(canonicalUrl, dstW, dstH, cropSquare, outBitmap)
        }
        return DecodeResult(image = outBitmap.asImage(), isSampled = dstW < srcW || dstH < srcH)
    }

    private fun computeTargetBounds(
        srcW: Int,
        srcH: Int,
        reqW: Int?,
        reqH: Int?,
        cropSquare: Boolean,
        precision: Precision,
    ): Triple<Int, Int, Boolean> {
        if (reqW == null && reqH == null) {
            return Triple(srcW, srcH, false)
        }
        val targetW = (reqW ?: reqH ?: srcW).coerceAtLeast(1)
        val targetH = (reqH ?: reqW ?: srcH).coerceAtLeast(1)
        val aspectMismatch = abs(srcW.toFloat() / srcH - targetW.toFloat() / targetH) > 0.02f
        val needCrop = cropSquare && aspectMismatch

        if (precision == Precision.INEXACT && !needCrop && srcW <= targetW && srcH <= targetH) {
            return Triple(srcW, srcH, false)
        }
        val maxSide = if (needCrop) min(srcW, srcH) else max(srcW, srcH)
        val outW = if (needCrop) min(targetW, maxSide) else min(targetW, srcW)
        val outH = if (needCrop) min(targetH, maxSide) else min(targetH, srcH)
        return if (!needCrop && (srcW != outW || srcH != outH)) {
            val scale = min(outW.toFloat() / srcW, outH.toFloat() / srcH).coerceAtMost(1f)
            Triple((srcW * scale).roundToInt().coerceAtLeast(1), (srcH * scale).roundToInt().coerceAtLeast(1), false)
        } else {
            Triple(outW.coerceAtLeast(1), outH.coerceAtLeast(1), needCrop)
        }
    }

    private fun computeCenterCropRect(
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        crop: Boolean,
    ): Rect {
        if (!crop) return Rect.makeWH(srcW.toFloat(), srcH.toFloat())
        val srcAspect = srcW.toFloat() / srcH
        val dstAspect = dstW.toFloat() / dstH
        return if (srcAspect > dstAspect) {
            val cropW = srcH * dstAspect
            val left = (srcW - cropW) / 2f
            Rect.makeXYWH(left, 0f, cropW, srcH.toFloat())
        } else {
            val cropH = srcW / dstAspect
            val top = (srcH - cropH) / 2f
            Rect.makeXYWH(0f, top, srcW.toFloat(), cropH)
        }
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (result.mimeType?.equals("image/svg+xml", ignoreCase = true) == true) return null
            val canonical = options.getExtra(CpOriginalUrlExtraKey)
            return DesktopCroppingDecoder(result.source, options, canonical)
        }
    }
}
