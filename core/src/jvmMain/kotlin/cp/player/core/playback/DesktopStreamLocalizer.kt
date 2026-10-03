package cp.player.core.playback

import cp.player.core.cache.stableHash64
import cp.player.core.util.DesktopDataDir
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 桌面端的 [StreamLocalizer]：把无损流下载到 `~/.cpplayer/stream-cache/` 再播放。
 *
 * 见 [StreamLocalizer] 的 KDoc 说明为什么必须这么做（桌面引擎无法定位 FLAC over HTTP）。
 *
 * ### 几个刻意的设计
 * 1. **先写 `.part` 再原子改名**：中途失败/被杀时不会留下一个「看起来完整」的缓存文件，
 *    否则下次会命中半截文件，表现为「歌播到一半就没了」，比重新下载糟糕得多。
 * 2. **`Content-Length` 已知时必须核对字节数**：CDN 断流会给出长度不足的响应，
 *    不校验就会把截断的文件当成缓存命中。
 * 3. **扩展名按 magic bytes 判定**，不信 URL、更不信 `Content-Type`（上游把 FLAC 标成
 *    `audio/mpeg`）。引擎选解码器时扩展名是有效线索，存成 `.bin` 有真实风险。
 * 4. **任何失败都返回 null**（回退到「边下边播」），绝不抛异常到播放路径上。
 * 5. 缓存按 **LRU + 总容量上限** 淘汰。无损单曲动辄几十上百 MB，不设上限会吃满磁盘。
 * 6. **管理面（查看 / 搜索 / 删除 / 清理）由旁车索引支撑**（[StreamCacheIndex]）：
 *    缓存文件名是哈希，光靠文件系统恢复不出歌名。索引是**附加**文件，
 *    既存缓存的命名规则与哈希都不动 ⇒ 升级后老缓存照常命中，只是显示为「未知曲目」。
 */
class DesktopStreamLocalizer(
    private val cacheDir: File = DesktopDataDir.directory(CACHE_DIR_NAME),
    /**
     * 容量上限的**默认值**。用户在管理页改过之后以索引里记的为准（见 [capacityBytes]）。
     * 参数名保持 `maxCacheBytes` 是为了不动既有测试与调用点。
     */
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
) : StreamLocalizer {

    /**
     * 展示用元信息的旁车索引（歌名 / 歌手 / mediaId / 音质）。
     *
     * 必须是**附加**文件：文件名规则与 [sanitize] 的哈希都不能动，否则整盘既存缓存失效。
     * 声明在 [capacityBytes] 之前 —— 后者初始化时要读它的容量设置。
     */
    private val index = StreamCacheIndex(File(cacheDir, StreamCacheIndex.FILE_NAME))

    /**
     * 容量上限。默认取 [maxCacheBytes]，但用户可以在管理页改；
     * 改过的值经索引持久化、跨重启生效。
     */
    @Volatile
    private var capacityBytes: Long = index.capacityBytes()?.takeIf { it > 0L } ?: maxCacheBytes

    /** 同一 key 的并发下载串行化：两个请求同时落同一个文件会互相截断。 */
    private val lock = Mutex()

    override fun cachedPath(cacheKey: String): String? {
        // 只查表、不发网络请求：这个方法在播放路径上被同步调用，必须廉价。
        val hit = cachedFile(sanitize(cacheKey)) ?: return null
        // 触碰访问时间，LRU 才不会把正在听的曲子先淘汰掉。
        hit.setLastModified(System.currentTimeMillis())
        return hit.absolutePath
    }

    override suspend fun localize(
        url: String,
        cacheKey: String,
        headers: Map<String, String>,
        meta: SongCacheMeta?,
    ): String? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            // 本地文件本来就可定位，不需要落地。
            return null
        }
        return withContext(Dispatchers.IO) {
            // ⚠️ 不能用 `runCatching`：它会把 CancellationException 一起吞掉，
            // 于是「切歌后取消下载」变成空操作，旧下载继续跑到底白占带宽。
            try {
                localizeBlocking(url, cacheKey, headers, meta)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun localizeBlocking(
        url: String,
        cacheKey: String,
        headers: Map<String, String>,
        meta: SongCacheMeta?,
    ): String? = lock.withLock {
        cacheDir.mkdirs()
        val stem = sanitize(cacheKey)

        cachedFile(stem)?.let { hit ->
            // 命中：触碰访问时间，LRU 才不会把正在听的曲子先淘汰掉。
            hit.setLastModified(System.currentTimeMillis())
            return@withLock hit.absolutePath
        }

        val part = File(cacheDir, "$stem.part")
        part.delete()
        var expected = -1L
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                if (conn.responseCode !in 200..299) return@withLock null
                expected = conn.contentLengthLong
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(BUFFER_BYTES)
                        while (true) {
                            // 每轮都查一次取消：后台落盘是**可被换曲取消**的任务，
                            // 不查的话用户切歌后旧下载会一直跑到整曲下完，白占带宽。
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
            } finally {
                runCatching { conn.disconnect() }
            }

            if (part.length() <= 0L) return@withLock null
            // 长度对不上 ⇒ 响应被截断，不能当缓存用。
            if (expected > 0L && part.length() != expected) return@withLock null

            val target = File(cacheDir, "$stem${extensionFor(part)}")
            target.delete()
            if (!part.renameTo(target)) return@withLock null
            // 落盘成功才登记元信息：登记在前、写文件在后的做法会在失败时留下幽灵条目。
            recordMeta(target.name, cacheKey, meta)
            evictIfNeeded(keep = target)
            target.absolutePath
        } catch (_: Exception) {
            null
        } finally {
            // 无论成败都清掉半成品。
            part.delete()
        }
    }

    /** 命中一个**已完整**的缓存文件（`<stem>.<ext>`）。 */
    private fun cachedFile(stem: String): File? =
        cacheDir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("$stem.") && !it.name.endsWith(".part") && it.length() > 0L
        }

    /**
     * 按 magic bytes 定扩展名，回退到 `.bin`。
     *
     * 引擎选解码器会看扩展名，所以这一步有实际意义；而 `Content-Type` 不可信
     * （实测上游把 FLAC 标成 `audio/mpeg`）。
     */
    private fun extensionFor(file: File): String {
        val head = ByteArray(12)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (read < 4) return ".bin"
        fun ascii(off: Int, len: Int) = String(head, off, len, Charsets.US_ASCII)
        return when {
            ascii(0, 4) == "fLaC" -> ".flac"
            ascii(0, 4) == "OggS" -> ".ogg"
            ascii(0, 4) == "RIFF" -> ".wav"
            ascii(0, 3) == "ID3" -> ".mp3"
            ascii(0, 4) == "ADIF" -> ".aac"
            read >= 8 && ascii(4, 4) == "ftyp" -> ".m4a"
            // MP3 无 ID3 时以帧同步字开头：0xFF 后 3 位为 1。
            (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> ".mp3"
            else -> ".bin"
        }
    }

    /** 超出容量上限就按最后访问时间从旧到新删，直到降到上限以内。 */
    private fun evictIfNeeded(keep: File?) {
        val files = dataFiles()
        var total = files.sumOf { it.length() }
        if (total <= capacityBytes) return
        val oldestFirst = files.sortedBy { it.lastModified() }
        for (f in oldestFirst) {
            if (total <= capacityBytes) break
            if (keep != null && f.absolutePath == keep.absolutePath) continue
            val len = f.length()
            if (deleteQuietly(f)) {
                total -= len
                index.remove(f.name)
            }
        }
    }

    // ======================== 管理面 ========================

    override fun stats(): SongCacheStats {
        val files = dataFiles()
        return SongCacheStats(
            entries = files.size,
            bytes = files.sumOf { it.length() },
            capacityBytes = capacityBytes,
        )
    }

    override fun entries(): List<SongCacheEntry> {
        val files = dataFiles()
        // 被外部删掉的文件不该在索引里留尸：顺手收敛一次，索引不会无限长。
        index.retainOnly(files.mapTo(mutableSetOf()) { it.name })
        return files
            .map { file ->
                val record = index.metaOf(file.name)
                SongCacheEntry(
                    id = file.name,
                    mediaId = record?.mediaId,
                    qualityLevel = record?.qualityLevel,
                    title = record?.title,
                    artist = record?.artist,
                    bytes = file.length(),
                    lastAccessMs = file.lastModified(),
                )
            }
            // 从新到旧：管理页第一屏是「最近在听的」，正好是用户最可能需要看到 / 删掉的那批。
            .sortedByDescending { it.lastAccessMs }
    }

    override fun remove(id: String): Boolean {
        val target = fileFor(id) ?: return false
        val deleted = deleteQuietly(target)
        index.remove(target.name)
        return deleted
    }

    override fun clear(): Int {
        // 连 `.part` 残渣一起清：它们只在进程被杀时才会残留，留着纯占空间。
        // 但**不动索引文件本身** —— 容量设置存在里面，清缓存不该顺手把用户的偏好也清掉。
        val files = cacheDir.listFiles()?.filter { it.isFile && !isIndexFile(it.name) } ?: return 0
        var entries = 0
        for (f in files) {
            val isPartial = f.name.endsWith(".part")
            // 返回的是**缓存条目**数：半成品残渣不曾在列表里出现过，
            // 把它算进「已清理 N 首」用户会觉得数字对不上。
            if (deleteQuietly(f) && !isPartial) entries++
        }
        index.clearRecords()
        return entries
    }

    override fun clearOlderThan(olderThanMs: Long): Int {
        if (olderThanMs <= 0L) return 0
        val cutoff = System.currentTimeMillis() - olderThanMs
        var removed = 0
        for (f in dataFiles()) {
            if (f.lastModified() >= cutoff) continue
            if (deleteQuietly(f)) {
                removed++
                index.remove(f.name)
            }
        }
        return removed
    }

    override fun setCapacityBytes(bytes: Long) {
        val effective = if (bytes <= 0L) maxCacheBytes else bytes
        capacityBytes = effective
        index.setCapacityBytes(effective)
        // 缩小上限必须当场淘汰到位：用户设完数字却没变化，只会以为设置没生效。
        evictIfNeeded(keep = null)
    }

    override fun cacheDirPath(): String =
        cacheDir.absolutePath

    // ---- 管理面内部 ----

    /**
     * 把管理页传来的 id 解析成缓存目录内的一个文件。
     *
     * 只认**文件名**、且必须真的是缓存目录的直接子项 —— 否则 `remove("../../settings.json")`
     * 就能删到缓存目录之外。管理页传的是 [SongCacheEntry.id]（本就来自 `listFiles()`），
     * 这层校验防的是别的调用方与将来的重构。
     */
    private fun fileFor(id: String): File? {
        if (id.isBlank() || isIndexFile(id)) return null
        if (id.contains('/') || id.contains('\\') || id.contains("..")) return null
        val f = File(cacheDir, id)
        return f.takeIf { it.isFile && it.parentFile?.absolutePath == cacheDir.absolutePath }
    }

    /** 缓存目录下的数据文件（不含索引与临时索引）。 */
    private fun dataFiles(): List<File> =
        cacheDir.listFiles()
            ?.filter { it.isFile && !isIndexFile(it.name) && !it.name.endsWith(".part") }
            ?: emptyList()

    private fun isIndexFile(name: String): Boolean =
        name == StreamCacheIndex.FILE_NAME || name == StreamCacheIndex.FILE_NAME + StreamCacheIndex.TMP_SUFFIX

    /** 删除失败不抛：Windows 上正在播放的文件是锁住的，删不掉是正常结果而非错误。 */
    private fun deleteQuietly(file: File): Boolean =
        runCatching { file.delete() }.getOrDefault(false)

    /** 从稳定键 `mediaId@音质` 拆出展示与索引需要的两段。 */
    private fun recordMeta(fileName: String, cacheKey: String, meta: SongCacheMeta?) {
        val level = cacheKey.substringAfterLast('@', "")
        val mediaId = if (level.isEmpty()) cacheKey else cacheKey.substringBeforeLast('@')
        index.put(
            fileName,
            StreamCacheMetaRecord(
                mediaId = mediaId.takeIf { it.isNotBlank() },
                qualityLevel = level.takeIf { it.isNotBlank() },
                title = meta?.title,
                artist = meta?.artist,
            ),
        )
    }

    /**
     * 文件名安全化：`mediaId` 里带 `://`，直接当文件名会失败。
     *
     * 末尾用 [cp.player.core.cache.stableHash64] 兜底：不同 key 可能被安全化成同一个
     * 名字，不加哈希就会互相串音。**这个哈希实现不可换** —— 换了等于所有缓存文件
     * 改名，整盘 stream-cache 立即失效。
     */
    private fun sanitize(cacheKey: String): String {
        val safe = cacheKey.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .take(96)
        return "$safe-${stableHash64(cacheKey)}"
    }

    private companion object {
        const val CACHE_DIR_NAME = "stream-cache"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val BUFFER_BYTES = 64 * 1024

        /** 缓存总容量上限：2 GiB。无损单曲几十~上百 MB，够放十几首。 */
        const val DEFAULT_MAX_CACHE_BYTES = 2L * 1024L * 1024L * 1024L
    }
}
