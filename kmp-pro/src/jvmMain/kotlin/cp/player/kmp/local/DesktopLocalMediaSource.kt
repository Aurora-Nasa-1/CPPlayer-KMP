package cp.player.kmp.local

import cp.player.kmp.media.LocalMediaItem
import cp.player.kmp.media.LocalMediaOrigin
import cp.player.kmp.media.MediaType
import java.io.File
import kotlinx.coroutines.Dispatchers
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
 * - 索引持久化于 `~/.kmp-pro/local-media/index.json`（与 modules 目录同级风格）
 *
 * 元数据解析保持轻量：文件名推断 title，duration 未知填 0（不引入解析库）。
 */
class DesktopLocalMediaSource(dataDir: String? = null) : LocalMediaSource {

    companion object {
        private const val CHUNK_SIZE = 50
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val dataDirFile: File = File(
        dataDir ?: File(System.getProperty("user.home"), ".kmp-pro/local-media").path,
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
            val discovered = withContext(Dispatchers.IO) { discoverFiles(roots) }
            var scanned = 0
            val total = discovered.size
            for (chunk in discovered.chunked(CHUNK_SIZE)) {
                scanned += chunk.size
                emit(ScanProgress(scanned, total, chunk))
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
            val discovered = withContext(Dispatchers.IO) { discoverFiles(roots) }
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

    /** 遍历根目录，按扩展名白名单收集媒体文件。 */
    private fun discoverFiles(roots: List<File>): List<LocalMediaItem> {
        val out = ArrayList<LocalMediaItem>()
        for (root in roots) {
            runCatching {
                root.walkTopDown().forEach { f ->
                    if (!f.isFile) return@forEach
                    val type = MediaType.fromFileName(f.name)
                    if (type == MediaType.OTHER) return@forEach
                    out += f.toItem(type)
                }
            }
        }
        return out.distinctBy { it.path }
    }

    /** 轻量元数据：文件名推断 title，duration 未知填 0。 */
    private fun File.toItem(type: MediaType): LocalMediaItem = LocalMediaItem(
        path = absolutePath,
        title = nameWithoutExtension.ifBlank { name },
        durationMs = 0L,
        sizeBytes = runCatching { length() }.getOrDefault(0L),
        mediaType = type,
        source = LocalMediaOrigin.IMPORTED,
        lastModified = runCatching { lastModified() }.getOrDefault(0L),
    )

    private fun loadImportedFolders(): List<String> = runCatching {
        val text = localMediaReadText(foldersFile.absolutePath) ?: return emptyList()
        json.decodeFromString<List<String>>(text)
    }.getOrDefault(emptyList())

    private fun saveImportedFolders(folders: List<String>) {
        localMediaWriteText(foldersFile.absolutePath, json.encodeToString(folders))
    }
}
