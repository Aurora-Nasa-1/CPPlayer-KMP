package cp.player.core.playback

import cp.player.core.playback.drm.HlsPlaylistNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android 与桌面共用同一份清单归一化器；这里补一条针对 Media3 场景的
 * 回归断言，防止以后把它挪回 desktopMain 或改掉 ISO-23001-7 映射。
 */
class DrmPlaylistNormalizerTest {

    @Test
    fun `media playlist becomes media3 compatible`() {
        val media = listOf(
            "#EXTM3U",
            "#EXT-X-VERSION:7",
            "#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"data:text/plain;base64,cHNzaA==\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"",
            "#EXTINF:5.0,",
            "audio/seg_0.mp4",
        ).joinToString("\n")
        val normalized = HlsPlaylistNormalizer.normalize(
            media,
            "https://cdn.example.test/abc/P42_default.m3u8",
        ) { proxy -> "PROXY($proxy)" }
        assertTrue("METHOD=SAMPLE-AES-CTR" in normalized)
        assertFalse("ISO-23001-7" in normalized)
        assertTrue("https://cdn.example.test/abc/audio/seg_0.mp4" in normalized)
        assertEquals(
            "data:text/plain;base64,cHNzaA==",
            normalized.substringAfter("URI=\"").substringBefore("\""),
        )
    }
}
