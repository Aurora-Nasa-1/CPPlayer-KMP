package cp.player.app.ui.util

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.size.Precision
import cp.player.app.platform.DesktopCroppedThumbDiskCache
import cp.player.app.platform.installDesktopImageCacheLimit
import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.QueueItem
import cp.player.core.playback.RepeatMode
import cp.player.core.provider.HostTrafficPolicy
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CpImagePipelineTest {

    @AfterTest
    fun cleanup() {
        HostTrafficPolicy.setInBackgroundTray(false)
        HostTrafficPolicy.setDataSaver(false)
        HostTrafficPolicy.setOfflineMode(false)
        CoverPrefetcher.clear()
    }

    @Test
    fun `buildCoverImageRequest aligns keys across stale param urls and binds placeholder key`() {
        installDesktopImageCacheLimit()
        val ctx = PlatformContext.INSTANCE
        val memCache = SingletonImageLoader.get(ctx).memoryCache!!
        val seedBitmap = Bitmap().apply { allocN32Pixels(4, 4) }
        memCache[MemoryCache.Key("cover:https://p1.music.126.net/cover/1.jpg:256:c")] =
            MemoryCache.Value(seedBitmap.asImage())

        val r1 = buildCoverImageRequest(
            context = ctx,
            url = "https://p1.music.126.net/cover/1.jpg?param=140y140",
            targetPx = 512,
            placeholderBucketPx = 256,
        )
        val r2 = buildCoverImageRequest(
            context = ctx,
            url = "https://p1.music.126.net/cover/1.jpg?param=300y300",
            targetPx = 512,
        )
        assertNotNull(r1)
        assertNotNull(r2)
        assertEquals(r1.data, r2.data)
        assertEquals(r1.memoryCacheKey, r2.memoryCacheKey)
        assertEquals(r1.diskCacheKey, r2.diskCacheKey)
        assertEquals("cover:https://p1.music.126.net/cover/1.jpg:512:c", r1.memoryCacheKey)
        assertEquals("cover:https://p1.music.126.net/cover/1.jpg:256:c", r1.placeholderMemoryCacheKey?.key)
        assertEquals("cover:https://p1.music.126.net/cover/1.jpg:256:c", r2.placeholderMemoryCacheKey?.key)
        assertEquals(Precision.INEXACT, r1.precision)
    }

    @Test
    fun `buildCoverImageRequest stores unresized signed third-party url under orig disk key`() {
        val ctx = PlatformContext.INSTANCE
        val req = buildCoverImageRequest(
            context = ctx,
            url = "https://cdn.example.com/art.jpg?token=secret123",
            targetPx = 256,
            memoryCacheWrite = false,
        )
        assertNotNull(req)
        assertEquals("orig:https://cdn.example.com/art.jpg", req.diskCacheKey)
        assertEquals(CachePolicy.READ_ONLY, req.memoryCachePolicy)
    }

    @Test
    fun `DesktopCroppedThumbDiskCache findBestLocal prefers smallest sufficient bucket ascending`() {
        val canonical = "https://unit.test/cover-${System.nanoTime()}.jpg"
        val key256 = DesktopCroppedThumbDiskCache.cacheKey(canonical, 256, 256, cropSquare = true)
        val key1024 = DesktopCroppedThumbDiskCache.cacheKey(canonical, 1024, 1024, cropSquare = true)

        val dir = cp.player.core.util.DesktopDataDir.directory("cache/images/cropped")
        val f256 = File(dir, "$key256.webp")
        val f1024 = File(dir, "$key1024.webp")
        val bmp = Bitmap().apply { allocN32Pixels(8, 8) }
        val encoded = Image.makeFromBitmap(bmp).use { img ->
            img.encodeToData(EncodedImageFormat.WEBP, 80)!!.use { it.bytes }
        }
        bmp.close()
        try {
            f256.writeBytes(encoded)
            f1024.writeBytes(encoded)

            val bestFor128 = DesktopCroppedThumbDiskCache.findBestLocal(
                canonicalUrl = canonical,
                targetW = 128,
                targetH = 128,
                cropSquare = true,
                allowSmallerOnConstrained = false,
            )
            assertEquals(f256.absolutePath, bestFor128?.absolutePath)

            val bestFor512 = DesktopCroppedThumbDiskCache.findBestLocal(
                canonicalUrl = canonical,
                targetW = 512,
                targetH = 512,
                cropSquare = true,
                allowSmallerOnConstrained = false,
            )
            assertEquals(f1024.absolutePath, bestFor512?.absolutePath)

            // 仅剩 256 时，常规模式请求 512 不复用小图，受限模式可复用 256
            f1024.delete()
            assertNull(
                DesktopCroppedThumbDiskCache.findBestLocal(
                    canonicalUrl = canonical,
                    targetW = 512,
                    targetH = 512,
                    cropSquare = true,
                    allowSmallerOnConstrained = false,
                ),
            )
            val constrainedFor512 = DesktopCroppedThumbDiskCache.findBestLocal(
                canonicalUrl = canonical,
                targetW = 512,
                targetH = 512,
                cropSquare = true,
                allowSmallerOnConstrained = true,
            )
            assertEquals(f256.absolutePath, constrainedFor512?.absolutePath)
        } finally {
            f256.delete()
            f1024.delete()
        }
    }

    @Test
    fun `CoverPrefetcher resolveNextTrackCoverUrl respects repeat mode and distinct covers`() {
        val items = listOf(
            QueueItem("m1", "t1", "a1", coverUrl = "https://cdn.test/1.jpg"),
            QueueItem("m2", "t2", "a2", coverUrl = "https://cdn.test/2.jpg"),
        )
        val current = TrackSummary("1", "t1", "a1", coverUrl = "https://cdn.test/1.jpg")
        assertEquals(
            "https://cdn.test/2.jpg",
            CoverPrefetcher.resolveNextTrackCoverUrl(
                PlaybackUiState(currentTrack = current, currentIndex = 0, queue = items, repeatMode = RepeatMode.OFF),
            ),
        )
        assertNull(
            CoverPrefetcher.resolveNextTrackCoverUrl(
                PlaybackUiState(currentTrack = current, currentIndex = 0, queue = items, repeatMode = RepeatMode.ONE),
            ),
        )
        assertEquals(
            "https://cdn.test/1.jpg",
            CoverPrefetcher.resolveNextTrackCoverUrl(
                PlaybackUiState(
                    currentTrack = TrackSummary("2", "t2", "a2", coverUrl = "https://cdn.test/2.jpg"),
                    currentIndex = 1,
                    queue = items,
                    repeatMode = RepeatMode.ALL,
                ),
            ),
        )
        assertTrue(SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache != null)
        assertEquals(32L * 1024L * 1024L, SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache!!.maxSize)
    }

    @Test
    fun `DesktopCroppedThumbDiskCache putAsync persists cropped bitmap asynchronously`() {
        val canonical = "https://unit.test/async-${System.nanoTime()}.jpg"
        val bmp = Bitmap().apply { allocN32Pixels(16, 16) }
        try {
            DesktopCroppedThumbDiskCache.putAsync(canonical, 16, 16, cropSquare = true, bitmap = bmp)
            val deadline = System.currentTimeMillis() + 3_000L
            var found: File? = null
            while (System.currentTimeMillis() < deadline) {
                found = DesktopCroppedThumbDiskCache.find(canonical, 16, 16, cropSquare = true)
                if (found != null) break
                Thread.sleep(20)
            }
            assertNotNull(found, "putAsync should persist cropped image asynchronously")
            assertTrue(found.length() > 0L)
            found.delete()
        } finally {
            bmp.close()
        }
    }
}
