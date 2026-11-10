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
import cp.player.core.playback.SidecarLyricLoader
import cp.player.core.util.DesktopDataDir
import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformSupport
import cp.player.core.util.SettingsStorage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class DownloadCompanionAssetsTest {

    private val temps = mutableListOf<File>()
    private var savedDataHome: String? = null

    @BeforeTest
    fun setUp() {
        val home = tempDir("cpplayer-companion-home")
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

    @Test
    fun `下载任务完成时同步下载伴生歌词封面与云数据`() = runBlocking {
        val downloadsDir = tempDir("cpplayer-companion-downloads")
        val delivered = mutableListOf<Pair<DownloadTask, LocalMediaItem>>()
        val testLrc = "[00:01.00]七里香\n[00:05.00]周杰伦\n"

        val source = object : UnifiedMusicSource {
            override suspend fun getTrackDetail(mediaId: String): MusicResult<TrackSummary> =
                BackendResult.Success(
                    TrackSummary(
                        id = mediaId,
                        name = "七里香",
                        artist = "周杰伦",
                        album = "七里香",
                        coverUrl = "http://fake.cdn/cover.jpg",
                        durationMs = 299000L,
                    )
                )

            override suspend fun getTrackDetails(mediaIds: List<String>): MusicResult<List<TrackSummary>> =
                BackendResult.Success(mediaIds.map { getTrackDetail(it).getOrNull()!! })

            override suspend fun getSongUrl(mediaId: String, level: String): MusicResult<SongUrl> =
                BackendResult.Success(
                    SongUrl(
                        url = "http://fake.cdn/audio.flac",
                        level = level,
                        sizeBytes = 1024L,
                        expireAt = null,
                    )
                )

            override suspend fun getLyric(mediaId: String): MusicResult<String> =
                BackendResult.Success(testLrc)

            override suspend fun search(keywords: String, type: Int, providers: List<String>?): MusicResult<SearchResult> =
                BackendResult.Unsupported("")

            override suspend fun getUserPlaylists(providerId: String, uid: Long): MusicResult<List<PlaylistSummary>> =
                BackendResult.Unsupported("")
        }

        val executor = object : DownloadExecutor {
            override suspend fun download(
                url: String,
                headers: Map<String, String>,
                targetPartPath: String,
                resumeBytes: Long,
                onProgress: suspend (sessionWrittenBytes: Long, totalBytes: Long?) -> Unit,
            ): Long {
                val bytes = if (url.endsWith(".flac")) "FAKE_FLAC_AUDIO_CONTENT".encodeToByteArray()
                else "FAKE_JPEG_IMAGE_CONTENT".encodeToByteArray()
                File(targetPartPath).writeBytes(bytes)
                onProgress(bytes.size.toLong(), bytes.size.toLong())
                return bytes.size.toLong()
            }
        }

        val settings = object : SettingsStorage {
            private val map = mutableMapOf<String, String?>()
            override fun getString(key: String, default: String?): String? = map[key] ?: default
            override fun putString(key: String, value: String?) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
            override fun contains(key: String): Boolean = map.containsKey(key)
            override fun clear() = map.clear()
        }.apply {
            putString(DownloadConfig.KEY_DOWNLOAD_ROOT_DIR, downloadsDir.absolutePath)
        }

        val manager = MediaDownloadManagerImpl(
            source = source,
            settings = settings,
            context = PlatformContext(),
            onCompleted = { task, item -> delivered.add(task to item) },
            executor = executor,
        )

        val task = manager.enqueue(
            mediaId = "netease://song/1001",
            title = "七里香",
            artist = "周杰伦",
            coverUrl = "http://fake.cdn/cover.jpg",
            mediaType = MediaType.AUDIO,
            level = "lossless",
            album = "七里香",
            durationMs = 299000L,
        )

        // 等待下载完成
        val completed = manager.taskFlow(task.id).first { it?.status == DownloadStatus.COMPLETED }
        assertNotNull(completed)
        val audioPath = completed.localPath
        assertNotNull(audioPath)
        assertTrue(PlatformSupport.exists(audioPath), "主音频文件必须存在: $audioPath")

        val basePath = audioPath.substringBeforeLast('.', "")
        val lrcPath = "$basePath.lrc"
        val coverPath = "$basePath.jpg"
        val jsonPath = "$basePath.json"

        assertTrue(PlatformSupport.exists(lrcPath), "伴生歌词文件必须落地: $lrcPath")
        assertTrue(PlatformSupport.exists(coverPath), "伴生封面文件必须落地: $coverPath")
        assertTrue(PlatformSupport.exists(jsonPath), "伴生云数据文件必须落地: $jsonPath")

        // 验证边车歌词可直接解析
        val loadedLyric = SidecarLyricLoader.load(audioPath)
        assertNotNull(loadedLyric, "边车歌词加载器必须能从伴生 .lrc 直接解析")
        assertEquals(2, loadedLyric.first.size)
        assertEquals("七里香", loadedLyric.first[0].text)

        // 验证 deliverCompleted 交付的元数据
        assertEquals(1, delivered.size)
        val (_, item) = delivered.first()
        assertEquals("七里香", item.title)
        assertEquals("周杰伦", item.artist)
        assertEquals("七里香", item.album)
        assertEquals(299000L, item.durationMs)
        assertEquals("file:///$coverPath", item.coverUri)
        assertEquals("netease://song/1001", item.originalMediaId)

        // 验证任务删除且 deleteFile = true 时同步清理伴生文件
        manager.remove(task.id, deleteFile = true)
        // 允许异步协程完成删除
        var retry = 0
        while (PlatformSupport.exists(audioPath) && retry < 20) {
            kotlinx.coroutines.delay(50)
            retry++
        }
        assertFalse(PlatformSupport.exists(audioPath), "音频文件必须已被删除")
        assertFalse(PlatformSupport.exists(lrcPath), "伴生歌词必须已被删除")
        assertFalse(PlatformSupport.exists(coverPath), "伴生封面必须已被删除")
        assertFalse(PlatformSupport.exists(jsonPath), "伴生云数据必须已被删除")

        manager.shutdown()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { temps += it }
}
