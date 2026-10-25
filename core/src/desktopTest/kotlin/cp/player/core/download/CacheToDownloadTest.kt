package cp.player.core.download

import cp.player.core.BackendResult
import cp.player.core.media.LocalMediaItem
import cp.player.core.media.MediaType
import cp.player.core.model.DownloadStatus
import cp.player.core.model.DownloadTask
import cp.player.core.music.MusicResult
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.SearchResult
import cp.player.core.music.SongUrl
import cp.player.core.music.TrackSummary
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.playback.DesktopStreamLocalizer
import cp.player.core.playback.SongCacheEntry
import cp.player.core.playback.SongCacheMeta
import cp.player.core.playback.StreamLocalizer
import cp.player.core.util.DesktopDataDir
import cp.player.core.util.PlatformContext
import cp.player.core.util.SettingsStorage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 「歌曲缓存 → 正式下载」的机制测试。
 *
 * 这条链路最容易出的错不是崩溃，而是**静默交付错文件**：
 * 拿 A 音源的缓存去满足 B 音源的同名歌曲（歌名、时长都对得上，音频却不是那一份）。
 * 所以这里的用例主要钉三件事：
 * 1. 只有 `mediaId + 音质` 都对上才转（provider 不同一律不转）；
 * 2. 转出来的文件字节数必须与缓存一致，且缓存副本随之消失（不占两份空间）；
 * 3. 「一键转」只转能确定音源归属的条目，缺信息的旧缓存老老实实跳过。
 */
class CacheToDownloadTest {

    private val temps = mutableListOf<File>()
    private var savedDataHome: String? = null

    @BeforeTest
    fun setUp() {
        // 下载任务清单会写进数据目录 ⇒ 必须重定向到临时目录，绝不碰真实的 ~/.cpplayer
        val home = tempDir("cpplayer-cache2dl-home")
        savedDataHome = System.getProperty(DesktopDataDir.PROP_HOME)
        System.setProperty(DesktopDataDir.PROP_HOME, home.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        if (savedDataHome == null) System.clearProperty(DesktopDataDir.PROP_HOME)
        else System.setProperty(DesktopDataDir.PROP_HOME, savedDataHome!!)
        temps.forEach { runCatching { it.deleteRecursively() } }
        temps.clear()
    }

    // ============ 入队时的缓存命中 ============

    @Test
    fun `同音质同音源的完整缓存直接转成下载且不联网`() = runBlocking {
        val cacheDir = tempDir("cpplayer-cache-hit")
        val cached = writeCacheFile(cacheDir, "netease_song_42-lossless-a1b2.flac", 4096)
        // 转换成功后缓存文件会被删掉，长度要先记下来再断言
        val cachedBytes = cached.readBytes()
        val cache = FakeCache(cacheDir).apply {
            items += entry(cached.name, "netease://song/42", "lossless", "夜曲", "周杰伦")
        }
        val source = RecordingSource()
        val delivered = mutableListOf<Pair<DownloadTask, LocalMediaItem>>()
        val manager = manager(cache = cache, source = source) { task, item -> delivered += task to item }

        val task = manager.enqueue("netease://song/42", "夜曲", "周杰伦", null, MediaType.AUDIO, "lossless")

        assertEquals(DownloadStatus.COMPLETED, task.status, "命中缓存就该直接是已完成，而不是排队")
        assertTrue(task.fromCache, "要能区分「转来的」与「下下来的」")
        assertEquals("netease", task.providerId)
        assertFalse(source.called, "命中缓存就不该去取直链")
        val target = File(task.localPath!!)
        assertTrue(target.isFile, "下载目录里必须真的出现文件")
        assertEquals(cachedBytes.size.toLong(), target.length(), "字节数必须与缓存一致")
        assertTrue(target.readBytes().contentEquals(cachedBytes), "内容必须逐字节一致")
        assertEquals(listOf(cached.name), cache.removed, "转换成功后缓存副本要删掉")
        assertFalse(cached.exists(), "同一首歌不该同时占缓存与下载两份空间")
        assertEquals(1, delivered.size, "转下载的产物也要登记进本地曲库")
        assertEquals("netease://song/42", delivered.single().first.mediaId)
        manager.shutdown()
    }

    @Test
    fun `音源不同时即使 id 与音质相同也不转`() = runBlocking {
        val cacheDir = tempDir("cpplayer-cache-provider")
        // 同一个数字 id、同一个音质 —— 只有 provider 不同
        val cached = writeCacheFile(cacheDir, "netease_song_42-lossless-c3d4.flac", 2048)
        val cache = FakeCache(cacheDir).apply {
            items += entry(cached.name, "netease://song/42", "lossless", "夜曲", "周杰伦")
        }
        val manager = manager(cache = cache, source = RecordingSource())

        val task = manager.enqueue("qqmusic://song/42", "夜曲", "周杰伦", null, MediaType.AUDIO, "lossless")

        assertFalse(task.fromCache, "别的音源的缓存不能拿来充数")
        assertNull(task.localPath, "没有命中缓存就不该有本地文件")
        assertTrue(cached.exists(), "不该动另一个音源的缓存")
        assertTrue(cache.removed.isEmpty(), "不该删另一个音源的缓存记录")
        manager.shutdown()
    }

    @Test
    fun `音质不同时不转换`() = runBlocking {
        val cacheDir = tempDir("cpplayer-cache-quality")
        val cached = writeCacheFile(cacheDir, "netease_song_7-lossless-e5f6.flac", 2048)
        val cache = FakeCache(cacheDir).apply {
            items += entry(cached.name, "netease://song/7", "lossless", "晴天", "周杰伦")
        }
        val manager = manager(cache = cache, source = RecordingSource())

        val task = manager.enqueue("netease://song/7", "晴天", "周杰伦", null, MediaType.AUDIO, "hires")

        assertFalse(task.fromCache, "要求同音质：lossless 的缓存不能当成 hires 交付")
        assertTrue(cached.exists())
        manager.shutdown()
    }

    @Test
    fun `视频下载不会拿音频缓存充数`() = runBlocking {
        val cacheDir = tempDir("cpplayer-cache-video")
        val cached = writeCacheFile(cacheDir, "netease_song_9-lossless-ffee.flac", 1024)
        val cache = FakeCache(cacheDir).apply {
            items += entry(cached.name, "netease://song/9", "lossless", "某 MV", null)
        }
        val manager = manager(cache = cache, source = RecordingSource())

        val task = manager.enqueue("netease://song/9", "某 MV", null, null, MediaType.VIDEO, "lossless")

        assertFalse(task.fromCache, "音频缓存没有画面，不能当成视频下载")
        assertTrue(cached.exists())
        manager.shutdown()
    }

    // ============ 一键转下载 ============

    @Test
    fun `一键转下载只转能确定音源的条目并统计跳过数`() = runBlocking {
        val cacheDir = tempDir("cpplayer-cache-import")
        val first = writeCacheFile(cacheDir, "netease_song_1-lossless-1111.flac", 3000)
        val second = writeCacheFile(cacheDir, "netease_song_2-hires-2222.flac", 5000)
        // 旧版本落下的缓存：索引里没有 mediaId / 音质 ⇒ 认不出是哪个音源的哪首歌
        val legacy = writeCacheFile(cacheDir, "stream_old_nohash.flac", 1000)
        val cache = FakeCache(cacheDir).apply {
            items += entry(first.name, "netease://song/1", "lossless", "第一首", "甲", accessMs = 3L)
            items += entry(second.name, "netease://song/2", "hires", "第二首", "乙", accessMs = 2L)
            items += SongCacheEntry(
                id = legacy.name,
                mediaId = null,
                qualityLevel = "hires",
                title = null,
                artist = null,
                bytes = legacy.length(),
                lastAccessMs = 1L,
            )
        }
        val manager = manager(cache = cache, source = RecordingSource())

        val summary = manager.importFromSongCache()

        assertEquals(2, summary.converted, "两条有完整索引的都要转")
        assertEquals(1, summary.skipped, "认不出音源的旧缓存要跳过")
        assertEquals(0, summary.failed)
        val completed = manager.tasksFlow.value.filter { it.status == DownloadStatus.COMPLETED }
        assertEquals(2, completed.size)
        assertTrue(completed.all { it.fromCache }, "转来的任务都要打上 fromCache")
        assertEquals(
            setOf("netease://song/1", "netease://song/2"),
            completed.mapTo(mutableSetOf()) { it.mediaId },
        )
        assertTrue(legacy.exists(), "瞎转旧缓存等于交付错音频")
        val firstTask = completed.first { it.mediaId == "netease://song/1" }
        assertEquals(3000L, File(firstTask.localPath!!).length(), "落盘文件字节数必须与缓存一致")
        assertFalse(first.exists(), "转成功后缓存副本要删掉")
        assertFalse(second.exists(), "转成功后缓存副本要删掉")

        // 再点一次：已经没有可转的了（缓存副本已消失），不能产生重复任务
        val again = manager.importFromSongCache()
        assertEquals(0, again.converted)
        assertEquals(2, manager.tasksFlow.value.size, "重复点不该多出任务")
        manager.shutdown()
    }

    @Test
    fun `转换指定条目时不会顺带转别的`() = runBlocking {
        val cacheDir = tempDir("cpplayer-cache-single")
        val first = writeCacheFile(cacheDir, "netease_song_11-lossless-aaaa.flac", 1500)
        val second = writeCacheFile(cacheDir, "netease_song_12-lossless-bbbb.flac", 1500)
        val cache = FakeCache(cacheDir).apply {
            items += entry(first.name, "netease://song/11", "lossless", "十一", null)
            items += entry(second.name, "netease://song/12", "lossless", "十二", null)
        }
        val manager = manager(cache = cache, source = RecordingSource())

        val summary = manager.importFromSongCache(listOf(cache.items.first { it.mediaId == "netease://song/11" }))

        assertEquals(1, summary.converted)
        assertFalse(first.exists(), "被指定的那条要转走")
        assertTrue(second.exists(), "没被指定的另一条必须原样保留")
        assertEquals(1, manager.tasksFlow.value.size, "只该登记一条下载")
        manager.shutdown()
    }

    // ============ provider 判据 / 路径校验 ============

    @Test
    fun `provider 不同就不是同一条媒体`() {
        assertTrue(isSameMediaResource("netease://song/42", "netease://song/42"))
        assertTrue(isSameMediaResource("netease://song/42", "Netease://SONG/42"), "写法差异不该漏命中")
        assertFalse(
            isSameMediaResource("netease://song/42", "qqmusic://song/42"),
            "同 id 不同音源是不同的音频，绝不能被当成同一条",
        )
        assertFalse(isSameMediaResource(null, "netease://song/42"), "没有 mediaId 的旧缓存不参与匹配")
        assertFalse(isSameMediaResource("garbage", "netease://song/42"))
        assertFalse(isSameMediaResource("netease://song/42", "netease://album/42"))
    }

    @Test
    fun `缓存句柄只交出缓存目录内的文件`() {
        val cacheDir = tempDir("cpplayer-cache-entrypath")
        val file = File(cacheDir, "abc.flac").apply { writeBytes(ByteArray(8)) }
        val localizer = DesktopStreamLocalizer(cacheDir = cacheDir)

        assertEquals(file.absolutePath, localizer.entryPath("abc.flac"))
        assertNull(localizer.entryPath("../abc.flac"), "路径穿越必须被拒绝")
        assertNull(localizer.entryPath("missing.flac"))
    }

    // ============ 测试替身 ============

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { temps += it }

    private fun writeCacheFile(dir: File, name: String, bytes: Int): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }

    private fun entry(
        id: String,
        mediaId: String?,
        level: String?,
        title: String?,
        artist: String?,
        accessMs: Long = 1L,
    ) = SongCacheEntry(
        id = id,
        mediaId = mediaId,
        qualityLevel = level,
        title = title,
        artist = artist,
        bytes = 0L,
        lastAccessMs = accessMs,
    )

    private fun manager(
        cache: StreamLocalizer,
        source: UnifiedMusicSource,
        onCompleted: ((DownloadTask, LocalMediaItem) -> Unit)? = null,
    ): MediaDownloadManagerImpl {
        val downloadsDir = tempDir("cpplayer-cache2dl-downloads")
        val settings = MemorySettings().apply {
            putString(DownloadConfig.KEY_DOWNLOAD_ROOT_DIR, downloadsDir.absolutePath)
        }
        return MediaDownloadManagerImpl(
            source = source,
            settings = settings,
            context = PlatformContext(),
            cache = cache,
            onCompleted = onCompleted,
            executor = NeverCalledExecutor,
        )
    }

    /** 只存在于内存的设置存储：测试不碰真实配置。 */
    private class MemorySettings : SettingsStorage {
        private val map = mutableMapOf<String, String?>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() = map.clear()
    }

    /** 只实现「管理面」的缓存替身：真的读写临时目录，便于验证文件是否被搬走。 */
    private class FakeCache(private val dir: File) : StreamLocalizer {
        val items = mutableListOf<SongCacheEntry>()
        val removed = mutableListOf<String>()

        override suspend fun localize(
            url: String,
            cacheKey: String,
            headers: Map<String, String>,
            meta: SongCacheMeta?,
        ): String? = null

        override fun entries(): List<SongCacheEntry> = items.toList()

        override fun entryPath(id: String): String? =
            items.firstOrNull { it.id == id }?.let { File(dir, it.id) }?.takeIf { it.isFile }?.absolutePath

        override fun remove(id: String): Boolean {
            removed += id
            items.removeAll { it.id == id }
            return runCatching { File(dir, id).delete() }.getOrDefault(false)
        }
    }

    /** 记录「有没有真的去取直链」——命中缓存时必须一次都不取。 */
    private class RecordingSource : UnifiedMusicSource {
        var called = false
            private set

        override suspend fun getTrackDetail(mediaId: String): MusicResult<TrackSummary> = offline()

        override suspend fun getTrackDetails(mediaIds: List<String>): MusicResult<List<TrackSummary>> = offline()

        override suspend fun getSongUrl(mediaId: String, level: String): MusicResult<SongUrl> {
            called = true
            return offline()
        }

        override suspend fun search(
            keywords: String,
            type: Int,
            providers: List<String>?,
        ): MusicResult<SearchResult> = offline()

        override suspend fun getUserPlaylists(
            providerId: String,
            uid: Long,
        ): MusicResult<List<PlaylistSummary>> = offline()

        private fun <T> offline(): MusicResult<T> = BackendResult.Error("测试环境不联网")
    }

    /** 转下载链路里不该有任何网络读写；真被调用就说明实现跑偏了。 */
    private object NeverCalledExecutor : DownloadExecutor {
        override suspend fun download(
            url: String,
            headers: Map<String, String>,
            targetPartPath: String,
            resumeBytes: Long,
            onProgress: suspend (sessionWrittenBytes: Long, totalBytes: Long?) -> Unit,
        ): Long = error("缓存转下载不应触发网络下载：$url")
    }
}
