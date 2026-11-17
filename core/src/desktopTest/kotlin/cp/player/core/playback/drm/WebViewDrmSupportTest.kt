package cp.player.core.playback.drm

import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebViewDrmSupportTest {

    @Test
    fun `discovery returns first existing fixed candidate`() {
        val existing = createTempFile("msedge")
        val missing = createTempPath("chrome")
        val found = WebViewDrmBrowser.findExecutable(
            fixed = listOf(missing.toString(), existing),
            families = emptyList(),
        )
        assertEquals(existing, found)
    }

    @Test
    fun `discovery picks highest version inside a versioned family`() {
        val root = createTempDir()
        writeFakeExecutable(root, "155.0.1", "msedge.exe")
        val newest = writeFakeExecutable(root, "157.0.9", "msedge.exe")
        val found = WebViewDrmBrowser.findExecutable(
            fixed = emptyList(),
            families = listOf(WebViewDrmBrowser.VersionedBrowserFamily(root.toString(), "msedge.exe")),
        )
        assertEquals(newest, found)
    }

    @Test
    fun `discovery prefers fixed candidates over versioned families`() {
        val fixed = createTempFile("chrome")
        val root = createTempDir()
        writeFakeExecutable(root, "157.0.9", "msedge.exe")
        val found = WebViewDrmBrowser.findExecutable(
            fixed = listOf(fixed),
            families = listOf(WebViewDrmBrowser.VersionedBrowserFamily(root.toString(), "msedge.exe")),
        )
        assertEquals(fixed, found)
    }

    @Test
    fun `discovery returns null when nothing exists`() {
        val root = createTempDir()
        val found = WebViewDrmBrowser.findExecutable(
            fixed = listOf(createTempPath("missing").toString()),
            families = listOf(WebViewDrmBrowser.VersionedBrowserFamily(root.toString(), "msedge.exe")),
        )
        assertNull(found)
    }

    @Test
    fun `normalizer rewrites iso method and proxies playlist references`() {
        val upstream = "https://cdn.example.test/abc/P42_default.m3u8"
        val master = listOf(
            "#EXTM3U",
            "#EXT-X-VERSION:7",
            "#EXT-X-SESSION-KEY:METHOD=ISO-23001-7,URI=\"data:text/plain;base64,cHNzaA==\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"",
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"alac\",URI=\"audio/alac.m3u8\"",
            "#EXT-X-STREAM-INF:BANDWIDTH=256000,AUDIO=\"aud\",CODECS=\"mp4a.40.2\"",
            "audio/aac.m3u8",
        ).joinToString("\n")
        val normalized = HlsPlaylistNormalizer.normalize(master, upstream) { proxy -> "PROXY($proxy)" }
        assertTrue("METHOD=SAMPLE-AES-CTR" in normalized, "ISO-23001-7 必须改写为 SAMPLE-AES-CTR")
        assertTrue("data:text/plain;base64,cHNzaA==" in normalized, "data: URI 的 PSSH 不应被改写")
        assertTrue("PROXY(https://cdn.example.test/abc/audio/aac.m3u8)" in normalized, "variant 清单应代理化")
        assertTrue("PROXY(https://cdn.example.test/abc/audio/alac.m3u8)" in normalized, "audio group 清单应代理化")
    }

    @Test
    fun `normalizer keeps segments absolute and rewrites relative segment uris`() {
        val upstream = "https://cdn.example.test/abc/audio/aac.m3u8"
        val media = listOf(
            "#EXTM3U",
            "#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"data:text/plain;base64,cHNzaA==\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"",
            "#EXT-X-MAP:URI=\"init_0.mp4\"",
            "seg_0.mp4",
            "https://already.example/seg_1.mp4",
        ).joinToString("\n")
        val normalized = HlsPlaylistNormalizer.normalize(media, upstream) { proxy -> "PROXY($proxy)" }
        assertTrue("URI=\"https://cdn.example.test/abc/audio/init_0.mp4\"" in normalized, "init segment 应改绝对地址且不过代理")
        assertTrue("https://cdn.example.test/abc/audio/seg_0.mp4" in normalized, "分片应改绝对地址直连 CDN")
        assertTrue("https://already.example/seg_1.mp4" in normalized, "已是绝对地址的分片保持原样")
        assertTrue("PROXY(" !in normalized, "媒体清单内的分片不应被代理化")
    }

    @Test
    fun `normalizer handles query strings and session keys with ctR variants`() {
        val upstream = "https://cdn.example.test/abc/P42_default.m3u8?token=abc123"
        val master = listOf(
            "#EXTM3U",
            "#EXT-X-SESSION-KEY:METHOD=ISO-23001-7,URI=\"skd://itunes.example/track\"",
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"alac\",URI=\"audio/alac.m3u8?token=abc123\"",
            "#EXT-X-STREAM-INF:BANDWIDTH=1411200,AUDIO=\"aud\",CODECS=\"alac\"",
            "audio/alac.m3u8?token=abc123",
        ).joinToString("\n")
        val normalized = HlsPlaylistNormalizer.normalize(master, upstream) { proxy -> "PROXY($proxy)" }
        assertTrue("skd://itunes.example/track" in normalized, "FairPlay skd URI 必须原样保留")
        assertTrue(
            "PROXY(https://cdn.example.test/abc/audio/alac.m3u8?token=abc123)" in normalized,
            "带查询参数的清单引用同样要代理化：$normalized",
        )
        assertTrue("METHOD=SAMPLE-AES-CTR" in normalized)
    }

    private fun createTempFile(name: String): String {
        val path = createTempPath(name)
        path.createFile()
        path.writeText("fake")
        return path.toString()
    }

    private fun writeFakeExecutable(root: java.nio.file.Path, version: String, exeName: String): String {
        val file = root.resolve(version).resolve(exeName)
        file.parent.createDirectories()
        file.writeText("fake")
        return file.toString()
    }

    private fun createTempPath(name: String): java.nio.file.Path =
        kotlin.io.path.createTempDirectory("drm-support-test").resolve(name)

    private fun createTempDir(): java.nio.file.Path =
        kotlin.io.path.createTempDirectory("drm-support-test")
}
