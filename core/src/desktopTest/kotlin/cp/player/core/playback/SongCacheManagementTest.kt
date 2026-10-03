package cp.player.core.playback

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [DesktopStreamLocalizer] **管理面**（查看 / 搜索 / 删除 / 清理）的机制测试。
 *
 * 这一层与播放路径是同一份数据，判错的代价两头都疼：
 * - 多删 ⇒ 用户本来能离线听的曲子没了；
 * - 漏删 ⇒ 用户按了「清空」却仍然占着几百 MB，从此不再相信这个按钮。
 *
 * 因此每个用例都同时断言「目标条目没了」与「不该动的条目还在」。
 *
 * 另外钉住一件事：**没有索引记录的老缓存必须照样能被列出和删除**。
 * 索引是后加的（升级前落下的文件没有元信息），如果管理面依赖索引，老用户升级后
 * 会发现缓存既看不到也删不掉 —— 那正是「管理」这个名字最不该出现的失败方式。
 */
class SongCacheManagementTest {

    private var server: TestServer? = null
    private var cacheDir: File? = null

    @AfterTest
    fun tearDown() {
        runCatching { server?.stop() }
        runCatching { cacheDir?.deleteRecursively() }
    }

    private fun newLocalizer(maxCacheBytes: Long = 2L * 1024L * 1024L * 1024L): DesktopStreamLocalizer {
        val dir = java.nio.file.Files.createTempDirectory("cpplayer-song-cache").toFile()
        cacheDir = dir
        return DesktopStreamLocalizer(cacheDir = dir, maxCacheBytes = maxCacheBytes)
    }

    private fun start(body: ByteArray): TestServer =
        TestServer(body).also { it.start() }.also { server = it }

    /** 下载一首曲子的缓存；[key] 形如 `mediaId@音质`（与播放控制器的构造一致）。 */
    private suspend fun DesktopStreamLocalizer.fill(
        url: String,
        key: String,
        title: String? = null,
        artist: String? = null,
    ): String = localize(url, key, emptyMap(), SongCacheMeta(title = title, artist = artist))
        ?: error("测试前置条件：这次落盘必须成功（key=$key）")

    // ============ 查看 ============

    @Test
    fun `entries 带上落盘时记录的曲目信息`() = runBlocking {
        val srv = start(flacBody(2048))
        val localizer = newLocalizer()

        localizer.fill(srv.url(), "cp_api://song/42@lossless", title = "夜曲", artist = "周杰伦")

        val entries = localizer.entries()
        assertEquals(1, entries.size, "落盘一首就该列出一条")
        val only = entries.single()
        assertEquals("夜曲", only.title)
        assertEquals("周杰伦", only.artist)
        // mediaId 是不带音质那一段 —— 键是 `mediaId@音质`，拆开才能把同曲多档归到一起。
        assertEquals("cp_api://song/42", only.mediaId, "mediaId 要从稳定键里拆出来")
        assertEquals("lossless", only.qualityLevel, "音质档位要进索引，否则同曲多档分不清")
        assertEquals(File(cacheDir, only.id).length(), only.bytes, "字节数取文件系统实时值")
        assertTrue(only.lastAccessMs > 0L, "最后访问时间是「清理 N 天未播放」的唯一依据，不能缺")
    }

    @Test
    fun `musicId 里带 @ 时按最后一个 @ 拆分`() = runBlocking {
        val srv = start(flacBody(512))
        val localizer = newLocalizer()

        // mediaId 自身含 `@`（邮件式 ID 真实存在），只有最后一个 @ 才是音质分隔符。
        localizer.fill(srv.url(), "cp_api://user/a@b.com/song/7@hires", title = "T")

        val only = localizer.entries().single()
        assertEquals("cp_api://user/a@b.com/song/7", only.mediaId)
        assertEquals("hires", only.qualityLevel)
    }

    @Test
    fun `entries 按最后访问时间从新到旧`() = runBlocking {
        val srv = start(flacBody(512))
        val localizer = newLocalizer()

        val older = localizer.fill(srv.url(), "cp_api://song/1@lossless", title = "旧")
        val newer = localizer.fill(srv.url(), "cp_api://song/2@lossless", title = "新")
        // 手工把「旧」那条改到十天前，模拟真实的冷数据。
        File(older).setLastModified(System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000)

        val entries = localizer.entries()
        assertEquals(listOf("新", "旧"), entries.map { it.title })
        assertEquals(File(newer).name, entries.first().id)
    }

    @Test
    fun `stats 汇总条数与字节 并给出容量上限`() = runBlocking {
        val srv = start(flacBody(4096))
        val localizer = newLocalizer(maxCacheBytes = 100_000L)

        assertEquals(0, localizer.stats().entries, "还没下载时必须是 0，而不是把索引文件算进去")
        localizer.fill(srv.url(), "cp_api://song/1@lossless")
        localizer.fill(srv.url(), "cp_api://song/2@lossless")

        val stats = localizer.stats()
        assertEquals(2, stats.entries, "索引文件不是缓存条目，不能计入")
        assertEquals(2L * (4096 + 4), stats.bytes)
        assertEquals(100_000L, stats.capacityBytes)
        assertTrue(stats.supported)
        assertTrue(localizer.cacheDirPath().isNotBlank(), "桌面端必须有目录可「打开」")
    }

    @Test
    fun `搜索按歌名 歌手 音质 与文件名匹配`() = runBlocking {
        val srv = start(flacBody(256))
        val localizer = newLocalizer()
        localizer.fill(srv.url(), "cp_api://song/1@lossless", title = "Hello World", artist = "Adele")

        val entry = localizer.entries().single()
        assertTrue(entry.matches("hello"), "搜索要忽略大小写")
        assertTrue(entry.matches("adele"), "歌手也要能搜")
        assertTrue(entry.matches("lossless"), "音质档位也要能搜")
        assertTrue(entry.matches(entry.id), "文件名要能搜（老缓存唯一能搜的东西）")
        assertTrue(entry.matches("  "), "空白关键字视为不过滤")
        assertFalse(entry.matches("不存在的歌"))
    }

    // ============ 删除 ============

    @Test
    fun `remove 只删指定的那一条`() = runBlocking {
        val srv = start(flacBody(1024))
        val localizer = newLocalizer()
        localizer.fill(srv.url(), "cp_api://song/1@lossless", title = "留下")
        localizer.fill(srv.url(), "cp_api://song/2@lossless", title = "删掉")

        val target = localizer.entries().first { it.title == "删掉" }
        assertTrue(localizer.remove(target.id), "删除必须真的删掉文件")

        val remaining = localizer.entries()
        assertEquals(listOf("留下"), remaining.map { it.title }, "其它条目不能被牵连")
        assertFalse(File(cacheDir, target.id).exists())
    }

    @Test
    fun `remove 拒绝越出缓存目录的路径`() = runBlocking {
        val srv = start(flacBody(256))
        val localizer = newLocalizer()
        localizer.fill(srv.url(), "cp_api://song/1@lossless")

        val outsider = File(cacheDir!!.parentFile, "cp-outside-${System.nanoTime()}.txt")
        outsider.writeText("不能被删")
        try {
            assertFalse(localizer.remove("../${outsider.name}"), "路径穿越必须被拒")
            assertFalse(localizer.remove("index.json"), "索引文件不是缓存条目，不能从管理面删掉")
            assertTrue(outsider.isFile, "缓存目录之外的文件必须毫发无伤")
        } finally {
            outsider.delete()
        }
    }

    @Test
    fun `老缓存没有索引记录也能列出和删除`() = runBlocking {
        val localizer = newLocalizer()
        // 模拟升级前落下的文件：名字符合命名规则，但索引里没有它。
        val legacy = File(cacheDir, "cp_api_song_9_lossless-1a2b3c.flac")
        legacy.writeBytes(flacBody(2048))
        assertFalse(File(cacheDir, StreamCacheIndex.FILE_NAME).exists(), "前置条件：此刻还没有索引文件")

        val entries = localizer.entries()
        assertEquals(1, entries.size, "没有索引的旧缓存也必须在列表里出现")
        assertEquals(legacy.name, entries.single().id)
        assertNull(entries.single().title, "缺少元信息就如实为 null，由界面显示成「未知曲目」")

        assertTrue(localizer.remove(entries.single().id), "老缓存也必须能删掉")
        assertTrue(localizer.entries().isEmpty())
    }

    // ============ 清理 ============

    @Test
    fun `clearOlderThan 只删早于阈值的条目`() = runBlocking {
        val srv = start(flacBody(512))
        val localizer = newLocalizer()
        val stale = localizer.fill(srv.url(), "cp_api://song/old@lossless", title = "冷")
        val fresh = localizer.fill(srv.url(), "cp_api://song/new@lossless", title = "热")
        File(stale).setLastModified(System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000)

        val removed = localizer.clearOlderThan(30L * 24 * 60 * 60 * 1000)

        assertEquals(1, removed)
        assertEquals(listOf("热"), localizer.entries().map { it.title }, "最近在听的不能被误删")
        assertTrue(File(fresh).isFile)
        assertEquals(0, localizer.clearOlderThan(0L), "非正阈值是无效请求，不能变成「全删」")
    }

    @Test
    fun `clear 清掉全部条目但保留容量设置`() = runBlocking {
        val srv = start(flacBody(512))
        val localizer = newLocalizer()
        localizer.fill(srv.url(), "cp_api://song/1@lossless")
        localizer.fill(srv.url(), "cp_api://song/2@lossless")
        localizer.setCapacityBytes(64L * 1024L * 1024L)
        // 顺手留一个半成品，确认它也会被清掉。
        File(cacheDir, "leftover.part").writeBytes(byteArrayOf(1, 2, 3))

        val removed = localizer.clear()

        assertEquals(2, removed, "返回的必须是缓存条目数，不含 .part 残渣")
        assertEquals(0, localizer.stats().entries)
        assertTrue(localizer.entries().isEmpty())
        assertTrue(
            (cacheDir!!.listFiles() ?: emptyArray()).none { it.name.endsWith(".part") },
            ".part 残渣只在进程被杀时留下，清缓存时该一并带走",
        )
        // 容量是用户的偏好，不是缓存内容；清缓存不该顺手把它抹掉。
        assertEquals(64L * 1024L * 1024L, localizer.stats().capacityBytes, "容量设置必须跨 clear 保留")
    }

    @Test
    fun `缩小容量上限会立刻淘汰到位`() = runBlocking {
        val srv = start(flacBody(4096))
        val localizer = newLocalizer(maxCacheBytes = 1024L * 1024L)
        localizer.fill(srv.url(), "cp_api://song/1@lossless")
        localizer.fill(srv.url(), "cp_api://song/2@lossless")
        localizer.fill(srv.url(), "cp_api://song/3@lossless")
        assertEquals(3, localizer.stats().entries, "前置条件：三首都缓上了")

        localizer.setCapacityBytes(5_000L)

        // 单曲 4100 字节：降到 5000 后只放得下一首。
        assertEquals(1, localizer.stats().entries, "设完上限却看不到变化，用户只会以为按钮坏了")
        assertTrue(localizer.stats().bytes <= 5_000L)
    }

    @Test
    fun `容量设置跨实例保留`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("cpplayer-song-cache-persist").toFile()
        cacheDir = dir
        DesktopStreamLocalizer(cacheDir = dir).setCapacityBytes(8L * 1024L * 1024L)

        // 模拟重启：同一个缓存目录上新建一个 localizer。
        val reopened = DesktopStreamLocalizer(cacheDir = dir)

        assertEquals(
            8L * 1024L * 1024L,
            reopened.stats().capacityBytes,
            "容量设置要落盘，否则用户每次重启都得重设一遍",
        )
    }

    // ============ 辅助 ============

    private companion object {
        /** 只用来让扩展名判定走到 `.flac` 分支，不需要是完整可解码的 FLAC。 */
        val FLAC_HEADER = "fLaC".toByteArray(Charsets.US_ASCII)
        fun flacBody(size: Int): ByteArray = FLAC_HEADER + ByteArray(size)
    }

    /** 极简 HTTP 服务端：把同一份字节反复交给 [DesktopStreamLocalizer]。 */
    private class TestServer(private val body: ByteArray) {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        fun start() {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/song.flac") { exchange ->
                exchange.responseHeaders.add("Content-Type", "audio/mpeg") // 故意谎报，模拟上游
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
        }

        fun stop() = server.stop(0)

        fun url(): String = "http://127.0.0.1:${server.address.port}/song.flac"
    }
}
