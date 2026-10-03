package cp.player.core.playback

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 索引里的一条记录：**只有从文件系统恢复不出来的字段**。
 *
 * 字节数与最后访问时间刻意不存 —— 它们直接从 `File.length()` / `File.lastModified()` 读，
 * 存一份副本只会多出一致性问题（删除 / 触碰访问时间要走两条路径同步）。
 */
@Serializable
internal data class StreamCacheMetaRecord(
    val mediaId: String? = null,
    val qualityLevel: String? = null,
    val title: String? = null,
    val artist: String? = null,
)

/** 索引文件的结构。 */
@Serializable
internal data class StreamCacheIndexFile(
    val capacityBytes: Long? = null,
    val entries: Map<String, StreamCacheMetaRecord> = emptyMap(),
)

/**
 * 歌曲缓存目录旁的 sidecar 索引：`<cacheDir>/index.json`。
 *
 * ### 为什么需要它
 *
 * 缓存文件名是 `sanitize(cacheKey)-<stableHash64(cacheKey)>`，而 `stableHash64` 是
 * **单向哈希**（且注释明确写着「不可换」——换了等于整盘缓存改名、全部失效）。
 * 于是「磁盘上这个几百 MB 的文件是哪首歌」在文件系统层面**无法回答**。
 * 管理页要显示歌名、要按歌名搜索，就必须另存一份映射。
 *
 * ### 为什么是「附加文件」而不是改文件名
 *
 * 索引是**纯附加**的：既不重命名既有文件、也不改哈希规则 ⇒
 * 升级后老缓存**继续可用**，只是管理页把它们显示成「未知曲目」（删除与清理照常）。
 * 反过来（把歌名编进文件名）会让所有既有缓存一夜作废 —— 那是几百 MB 的无谓重下。
 *
 * ### 写入策略
 *
 * 全量重写，先写 `.tmp` 再改名。索引条目数是「缓存文件数」量级（上限 2 GiB / 单曲几十 MB
 * ⇒ 几十条），全量重写的成本可以忽略；而增量改写一旦写坏，用户看到的是**整个缓存目录
 * 突然全部变成未知曲目**。原子改名把「写一半被杀」的窗口关掉。
 *
 * 所有公开方法都同步：读写方既有播放协程（落盘完成时登记）也有 UI 线程（管理页），
 * 用粗粒度锁换正确性。
 */
internal class StreamCacheIndex(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }

    private var loaded = false
    private var capacity: Long? = null
    private val records = mutableMapOf<String, StreamCacheMetaRecord>()

    /** 用户显式设置过的容量上限；null = 从没设过（用平台默认）。 */
    @Synchronized
    fun capacityBytes(): Long? {
        ensureLoaded()
        return capacity
    }

    @Synchronized
    fun setCapacityBytes(value: Long?) {
        ensureLoaded()
        capacity = value
        flush()
    }

    @Synchronized
    fun metaOf(fileName: String): StreamCacheMetaRecord? {
        ensureLoaded()
        return records[fileName]
    }

    /** 登记一条新落盘的缓存。 */
    @Synchronized
    fun put(fileName: String, record: StreamCacheMetaRecord) {
        ensureLoaded()
        records[fileName] = record
        flush()
    }

    @Synchronized
    fun remove(fileName: String) {
        ensureLoaded()
        if (records.remove(fileName) != null) flush()
    }

    /** 丢弃不在 [existing] 里的记录：被外部（文件管理器）删掉的文件不该在索引里留尸。 */
    @Synchronized
    fun retainOnly(existing: Set<String>) {
        ensureLoaded()
        if (records.keys.retainAll(existing)) flush()
    }

    /** 清空全部记录，**保留**容量设置。 */
    @Synchronized
    fun clearRecords() {
        ensureLoaded()
        if (records.isNotEmpty()) {
            records.clear()
            flush()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        // 索引缺失 / 损坏 / 字段不认识 ⇒ 当作「空的旧索引」继续跑。
        // 它只是一份展示用的元信息，绝不能因为读不出来就让缓存不可用。
        val source = file.takeIf { it.isFile } ?: return
        val parsed = runCatching { json.decodeFromString<StreamCacheIndexFile>(source.readText()) }
            .getOrNull() ?: return
        capacity = parsed.capacityBytes
        records.putAll(parsed.entries)
    }

    private fun flush() {
        runCatching {
            val parent = file.parentFile ?: return
            if (!parent.exists()) parent.mkdirs()
            val tmp = File(parent, file.name + TMP_SUFFIX)
            tmp.writeText(json.encodeToString(StreamCacheIndexFile(capacity, records.toMap())))
            if (!tmp.renameTo(file)) {
                // Windows 上目标已存在时 renameTo 会失败 → 先删再改，避免索引永远停更。
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        }
    }

    internal companion object {
        const val FILE_NAME = "index.json"
        const val TMP_SUFFIX = ".tmp"
    }
}
