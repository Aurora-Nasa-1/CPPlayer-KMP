package cp.player.core.local

import cp.player.core.media.AudioMetadataReader
import cp.player.core.media.LocalMediaItem
import cp.player.core.media.LocalMediaOrigin
import cp.player.core.media.MediaType
import cp.player.core.media.toTrackMetadata
import cp.player.core.util.DesktopDataDir
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Desktop（JVM 通用文件系统）本地媒体源。
 *
 * - 扫描目录 = 系统音乐/视频目录（`user.home/Music`、`user.home/Videos`）
 *   + 持久化的「已导入文件夹」列表
 * - 扩展名白名单复用 [MediaType.fromFileName]
 *   （mp3/flac/m4a/ogg/wav/aac/mp4/mkv/mov/webm/avi）
 * - 文件遍历在 [Dispatchers.IO] 上执行，进度按 50 条/批发射
 * - 索引持久化于 `~/.cpplayer/local-media/index.json`（与 modules 目录同级风格；
 *   旧目录名 `.kmp-pro` 的迁移见 [DesktopDataDir]）
 *
 * 元数据解析保持轻量：文件名推断 title，duration 未知填 0（不引入解析库）。
 */
class DesktopLocalMediaSource(dataDir: String? = null) : LocalMediaSource {

    companion object {
        private const val CHUNK_SIZE = 50

        /**
         * 标签解析用的并发池。
         *
         * 上下限刻意收窄到 2..8：过低在 NVMe 上吃不满带宽，过高在机械盘 / 网络盘上
         * 会因为寻道竞争反而更慢。这类「IO 密集但每次只读几十 KB」的负载，
         * 并发度略高于核数是合适的，再多就是纯排队。
         */
        private val parseDispatcher = Dispatchers.IO.limitedParallelism(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val dataDirFile: File = File(
        dataDir ?: DesktopDataDir.directory("local-media").path,
    )

    private val index: LocalMediaIndex =
        LocalMediaIndex(File(dataDirFile, "index.json").absolutePath).also { it.load() }

    /**
     * 索引读改写串行锁：scan/importFolder/addExternalItems/removeItem 的
     * 「index 读改写 + _items 赋值 + save」组合操作整体置于临界区，
     * 避免下载登记与扫描并发时丢条目。
     */
    private val indexMutex = Mutex()

    private val foldersFile: File = File(dataDirFile, "imported-folders.json")

    /** 封面磁盘缓存（内嵌封面提取 / 目录封面回退）。 */
    private val coverStore = LocalCoverStore(File(dataDirFile, "covers"))

    override val favorites: LocalFavorites =
        LocalFavorites(File(dataDirFile, "favorites.json").absolutePath)

    override val scanSettings: LocalScanSettingsStore =
        LocalScanSettingsStore(File(dataDirFile, "scan-settings.json").absolutePath)

    private val importedFolders = MutableStateFlow(loadImportedFolders())

    private val _items = MutableStateFlow(index.items)
    private val _isScanning = MutableStateFlow(false)

    override fun items(): StateFlow<List<LocalMediaItem>> = _items.asStateFlow()

    override val isScanningFlow: StateFlow<Boolean> get() = _isScanning.asStateFlow()

    override suspend fun scan(): Flow<ScanProgress> = flow {
        if (_isScanning.value) return@flow
        _isScanning.value = true
        try {
            val roots = allRoots()
            val discovered = scanOnce(roots) { scanned, total, batch ->
                emit(ScanProgress(scanned, total, batch))
            }
            // 「index 读改写 + _items 赋值 + save」整体临界区（临界区内无挂起点）
            indexMutex.withLock {
                val result = index.reconcile(discovered, scanRoots = roots.map { it.absolutePath })
                _items.value = result.all
                index.save()
            }
        } finally {
            _isScanning.value = false
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 一次完整扫描：收集文件 → **并发**解析标签 → 按批回调进度 → 应用过滤规则。
     *
     * ## 为什么要并发
     *
     * 标签解析是纯 IO 等待（读文件头 / moov / OGG 尾页），单线程下每首都要
     * 走一次完整的「seek + read」，几千首的曲库会拖到几十秒。这里用
     * [parseDispatcher]（受 CPU 核数限制的 IO 池）并发解析，同时**仍然按批回调**，
     * 进度条不会退化成 0 → 100 的跳变。
     *
     * 过滤放在解析**之后**：最短时长规则依赖解析出来的时长，先滤会让时长未知
     * 的文件被整批误伤。
     */
    private suspend fun scanOnce(
        roots: List<File>,
        onProgress: suspend (scanned: Int, total: Int, batch: List<LocalMediaItem>) -> Unit = { _, _, _ -> },
    ): List<LocalMediaItem> {
        val files = withContext(Dispatchers.IO) { collectFiles(roots) }
        val settings = scanSettings.snapshot()
        val total = files.size
        var scanned = 0
        val parsed = ArrayList<LocalMediaItem>(total)
        for (chunk in files.chunked(CHUNK_SIZE)) {
            // 批内并发：每个文件一个协程（很轻），实际并行度由 parseDispatcher 收口
            val batch = coroutineScope {
                chunk.map { file -> async(parseDispatcher) { file.toItem() } }.awaitAll()
            }
            val accepted = settings.apply(batch)
            parsed += accepted
            scanned += chunk.size
            onProgress(scanned, total, accepted)
        }
        return parsed
    }

    override suspend fun importFolder(uri: String): Int {
        val dir = File(uri)
        if (!dir.isDirectory) return 0
        val abs = dir.absolutePath
        if (abs !in importedFolders.value) {
            val next = importedFolders.value + abs
            importedFolders.value = next
            saveImportedFolders(next)
        }
        if (_isScanning.value) return 0
        _isScanning.value = true
        return try {
            val roots = allRoots()
            val discovered = scanOnce(roots)
            // 「index 读改写 + _items 赋值 + save」整体临界区
            val result = indexMutex.withLock {
                val r = index.reconcile(discovered, scanRoots = roots.map { it.absolutePath })
                _items.value = r.all
                index.save()
                r
            }
            result.added.size
        } finally {
            _isScanning.value = false
        }
    }

    override fun removeItem(item: LocalMediaItem) {
        // 非挂起接口：runBlocking 进入同一临界区（临界区内无挂起点，不会死锁）
        runBlocking {
            indexMutex.withLock {
                val next = index.items.filterNot { it.path == item.path }
                index.updateItems(next)
                _items.value = next
                index.save()
            }
        }
    }

    override fun addExternalItems(items: List<LocalMediaItem>) {
        if (items.isEmpty()) return
        // 非挂起接口：runBlocking 进入同一临界区（临界区内无挂起点，不会死锁）
        runBlocking {
            indexMutex.withLock {
                val byPath = index.items.associateBy { it.path }.toMutableMap()
                items.forEach { byPath[it.path] = it }
                val next = byPath.values.toList()
                index.updateItems(next)
                _items.value = next
                index.save()
            }
        }
    }

    // ======================== 内部 ========================

    /** 系统默认音乐 / 视频目录。 */
    private fun defaultRoots(): List<File> {
        val home = System.getProperty("user.home") ?: return emptyList()
        return listOf(File(home, "Music"), File(home, "Videos"))
    }

    /** 本次扫描全部根目录（默认目录 + 已导入文件夹，仅保留存在的目录）。 */
    private fun allRoots(): List<File> =
        (defaultRoots() + importedFolders.value.map(::File))
            .filter { it.isDirectory }
            .distinctBy { it.absolutePath }

    /**
     * 遍历根目录，按扩展名白名单收集媒体文件。
     *
     * 只做「发现」不解析标签 —— 解析被拆到 [scanOnce] 里并发执行，
     * 顺序遍历目录树这件事本身很便宜，不值得并行（并行反而会打乱 inode 访问顺序）。
     */
    private fun collectFiles(roots: List<File>): List<File> {
        val out = ArrayList<File>()
        for (root in roots) {
            runCatching {
                root.walkTopDown().forEach { f ->
                    if (!f.isFile) return@forEach
                    if (MediaType.fromFileName(f.name) == MediaType.OTHER) return@forEach
                    out += f
                }
            }
        }
        return out.distinctBy { it.path }
    }

    /**
     * 组装条目：音频文件读标签（标题 / 艺人 / 专辑 / 时长 / 音质），视频只用文件名。
     *
     * 标签解析只读取文件头部若干字节（MP4 额外读 moov、OGG 额外读尾页），
     * 不读音频数据本体；读失败时静默回退到文件名，绝不让坏文件拖垮整次扫描。
     */
    private fun File.toItem(): LocalMediaItem {
        val type = MediaType.fromFileName(name)
        val tag = if (type == MediaType.AUDIO) AudioMetadataReader.read(absolutePath) else null
        val hasCover = tag?.hasEmbeddedCover == true
        return LocalMediaItem(
            path = absolutePath,
            title = tag?.title?.takeIf { it.isNotBlank() && !isPlaceholder(it) }
                ?: nameWithoutExtension.ifBlank { name },
            artist = tag?.artist?.takeIf { it.isNotBlank() && !isPlaceholder(it) },
            album = tag?.album?.takeIf { it.isNotBlank() && !isPlaceholder(it) },
            durationMs = tag?.durationMs ?: 0L,
            sizeBytes = runCatching { length() }.getOrDefault(0L),
            mediaType = type,
            coverUri = if (type == MediaType.AUDIO) {
                runCatching { coverStore.resolve(absolutePath, hasCover) }.getOrNull()
            } else null,
            source = LocalMediaOrigin.IMPORTED,
            lastModified = runCatching { lastModified() }.getOrDefault(0L),
            metadata = tag?.toTrackMetadata(),
        )
    }

    /** MediaStore / 部分转录工具会把空值写成 `<unknown>`，直接透传会在 UI 上很难看。 */
    private fun isPlaceholder(value: String): Boolean {
        val v = value.trim()
        return v.equals("<unknown>", ignoreCase = true) || v.equals("unknown", ignoreCase = true)
    }

    private fun loadImportedFolders(): List<String> = runCatching {
        val text = localMediaReadText(foldersFile.absolutePath) ?: return emptyList()
        json.decodeFromString<List<String>>(text)
    }.getOrDefault(emptyList())

    private fun saveImportedFolders(folders: List<String>) {
        localMediaWriteText(foldersFile.absolutePath, json.encodeToString(folders))
    }
}
