package cp.player.core.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import cp.player.core.media.LocalMediaItem
import cp.player.core.media.LocalMediaOrigin
import cp.player.core.media.LocalTrackMetadata
import cp.player.core.media.MediaType
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

/**
 * Android 本地媒体源。
 *
 * - 扫描：MediaStore.Audio.Media + MediaStore.Video.Media
 *   （TITLE/ARTIST/ALBUM/DURATION/SIZE/DATE_MODIFIED/_ID/DATA），
 *   按 [LocalMediaIndex] 的 size / lastModified 元信息增量比对
 * - 导入：SAF 树 URI 经 [DocumentsContract] 遍历（调用方已
 *   takePersistableUriPermission；权限丢失时跳过并报告）
 * - 无 READ 权限时 [scan] 仅发射一条带 [ScanProgress.permissionDenied]
 *   标志的进度，供 UI 引导授权
 * - 索引持久化于 `filesDir/local-media/index.json`
 *
 * 元数据：MediaStore 提供 title/artist/album/duration（SAF 树条目只能靠文件名推断），
 * 并从 album_id 拼出专辑封面 URI、从 year/track/album_artist/genre 补深度字段。
 */
class AndroidLocalMediaSource(private val context: Context) : LocalMediaSource {

    companion object {
        private const val CHUNK_SIZE = 50
    }

    private val dataDir: File = File(context.filesDir, "local-media")
        .apply { if (!exists()) mkdirs() }

    private val index: LocalMediaIndex =
        LocalMediaIndex(File(dataDir, "index.json").absolutePath).also { it.load() }

    override val favorites: LocalFavorites =
        LocalFavorites(File(dataDir, "favorites.json").absolutePath)

    override val scanSettings: LocalScanSettingsStore =
        LocalScanSettingsStore(File(dataDir, "scan-settings.json").absolutePath)

    /**
     * 索引读改写串行锁：scan/importFolder/addExternalItems/removeItem 的
     * 「index 读改写 + _items 赋值 + save」组合操作整体置于临界区，
     * 避免下载登记与扫描并发时丢条目。
     */
    private val indexMutex = Mutex()

    private val _items = MutableStateFlow(index.items)
    private val _isScanning = MutableStateFlow(false)

    override fun items(): StateFlow<List<LocalMediaItem>> = _items.asStateFlow()

    override val isScanningFlow: StateFlow<Boolean> get() = _isScanning.asStateFlow()

    override suspend fun scan(): Flow<ScanProgress> = flow {
        if (!hasMediaReadPermission()) {
            emit(
                ScanProgress(
                    scanned = 0,
                    total = 0,
                    permissionDenied = true,
                    errorMessage = "缺少媒体读取权限，请先授权（READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE）",
                ),
            )
            return@flow
        }
        if (_isScanning.value) return@flow
        _isScanning.value = true
        try {
            // 扫描过滤规则在此生效（排除目录 / 最短时长 / 是否含视频）
            val discovered = withContext(Dispatchers.IO) {
                scanSettings.snapshot().apply(queryMediaStore())
            }
            var scanned = 0
            val total = discovered.size
            for (chunk in discovered.chunked(CHUNK_SIZE)) {
                scanned += chunk.size
                emit(ScanProgress(scanned, total, chunk))
            }
            // 「index 读改写 + _items 赋值 + save」整体临界区（临界区内无挂起点）
            indexMutex.withLock {
                val result = index.reconcile(discovered, scanRoots = null)
                _items.value = result.all
                index.save()
            }
        } finally {
            _isScanning.value = false
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun importFolder(uri: String): Int {
        if (!uri.startsWith("content://")) return 0
        val treeUri = runCatching { Uri.parse(uri) }.getOrNull() ?: return 0
        if (!DocumentsContract.isTreeUri(treeUri)) return 0

        // 调用方应已 takePersistableUriPermission；权限丢失时跳过并报告
        val persisted = context.contentResolver.persistedUriPermissions
            .any { it.uri == treeUri && it.isReadPermission }
        if (!persisted) {
            println("[LocalMedia] SAF 树读取权限丢失，跳过导入: $uri")
            return 0
        }

        return withContext(Dispatchers.IO) {
            val discovered = mutableListOf<LocalMediaItem>()
            val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return@withContext 0
            walkTree(treeUri, rootDocId, discovered)

            // 「index 读改写 + _items 赋值 + save」整体临界区
            indexMutex.withLock {
                val existingPaths = index.items.mapTo(mutableSetOf()) { it.path }
                val fresh = discovered.filter { it.path !in existingPaths }
                if (fresh.isNotEmpty()) {
                    val next = index.items + fresh
                    index.updateItems(next)
                    _items.value = next
                    index.save()
                }
                fresh.size
            }
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

    /** 是否持有媒体读取权限（API 33+ 为 READ_MEDIA_AUDIO，否则 READ_EXTERNAL_STORAGE）。 */
    fun hasMediaReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /** MediaStore 全量查询（音频 + 视频），按 DATE_MODIFIED 写入索引增量比对。 */
    private fun queryMediaStore(): List<LocalMediaItem> {
        val audio = queryCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaType.AUDIO)
        val video = if (hasMediaVideoPermission()) {
            queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO)
        } else {
            emptyList()
        }
        return audio + video
    }

    /** API 33+ 视频列单独需要 READ_MEDIA_VIDEO（未声明于清单时视为未授权）。 */
    private fun hasMediaVideoPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 查询单个 MediaStore 集合。
     *
     * 音频集合额外取 `album_id` / `year` / `track` / `album_artist` / `genre`：
     * - `album_id` → 专辑封面 URI（`content://media/external/audio/albumart/<id>`）。
     *   此前 `coverUri` 恒为 null，本地曲进不了封面取色、专辑墙与播放器页 —— 这是
     *   本地库「看起来很素」的根因。
     * - `year` / `track` → 排序与音轨号展示
     *
     * 这些列不是所有 ROM / API 版本都提供（部分为 API 30+），因此统一用
     * `getColumnIndex` 取列号并检查 -1，缺失时该项降级为 null 而不是整次查询失败。
     */
    private fun queryCollection(uri: Uri, type: MediaType): List<LocalMediaItem> {
        val isAudio = type == MediaType.AUDIO
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.TITLE)
            add(MediaStore.MediaColumns.ARTIST)
            add(MediaStore.MediaColumns.ALBUM)
            add(MediaStore.MediaColumns.DURATION)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.DATA)
            if (isAudio) {
                add(MediaStore.Audio.AlbumColumns.ALBUM_ID)
                add("year")
                add("track")
                add("album_artist")
                add("genre")
            }
        }.toTypedArray()

        val out = mutableListOf<LocalMediaItem>()
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val iTitle = c.getColumnIndex(MediaStore.MediaColumns.TITLE)
                val iArtist = c.getColumnIndex(MediaStore.MediaColumns.ARTIST)
                val iAlbum = c.getColumnIndex(MediaStore.MediaColumns.ALBUM)
                val iDuration = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val iModified = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val iAlbumId = c.getColumnIndex(MediaStore.Audio.AlbumColumns.ALBUM_ID)
                val iYear = c.getColumnIndex("year")
                val iTrack = c.getColumnIndex("track")
                val iAlbumArtist = c.getColumnIndex("album_artist")
                val iGenre = c.getColumnIndex("genre")
                while (c.moveToNext()) {
                    val path = iData.takeIf { it >= 0 }?.let { c.getString(it) } ?: continue
                    val fileName = File(path).name
                    val albumId = iAlbumId.takeIf { it >= 0 }?.let { c.getLong(it) } ?: -1L
                    val year = iYear.takeIf { it >= 0 }?.let { c.getString(it) }?.take(4)?.toIntOrNull()
                    // MediaStore 的 track 列在部分 ROM 上是「碟号*1000+轨号」的复合值
                    val rawTrack = iTrack.takeIf { it >= 0 }?.let { c.getInt(it) } ?: 0
                    val trackNumber = when {
                        rawTrack <= 0 -> null
                        rawTrack > 1000 -> rawTrack % 1000
                        else -> rawTrack
                    }
                    val albumArtist = iAlbumArtist.takeIf { it >= 0 }?.let { c.getString(it) }
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    val genre = iGenre.takeIf { it >= 0 }?.let { c.getString(it) }
                        ?.takeIf { it.isNotBlank() }
                    out += LocalMediaItem(
                        path = path,
                        title = iTitle.takeIf { it >= 0 }?.let { c.getString(it) }
                            ?: File(path).nameWithoutExtension,
                        artist = iArtist.takeIf { it >= 0 }?.let { c.getString(it) }
                            ?.takeIf { it.isNotBlank() && it != "<unknown>" },
                        album = iAlbum.takeIf { it >= 0 }?.let { c.getString(it) }
                            ?.takeIf { it.isNotBlank() },
                        durationMs = iDuration.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L,
                        sizeBytes = iSize.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L,
                        mediaType = type,
                        coverUri = if (isAudio && albumId > 0) albumArtUri(albumId) else null,
                        source = LocalMediaOrigin.IMPORTED,
                        // DATE_MODIFIED 单位为秒 → 毫秒
                        lastModified = (iModified.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L) * 1000L,
                        metadata = if (isAudio && (year != null || trackNumber != null ||
                            albumArtist != null || genre != null)
                        ) {
                            LocalTrackMetadata(
                                albumArtist = albumArtist,
                                genre = genre,
                                year = year,
                                trackNumber = trackNumber,
                                hasEmbeddedCover = false,
                            )
                        } else null,
                    ).let { item ->
                        if (item.title.isBlank()) item.copy(title = fileName) else item
                    }
                }
            }
        }
        return out
    }

    /** MediaStore 专辑封面 URI；Coil 能直接按 content:// 解码。 */
    private fun albumArtUri(albumId: Long): String =
        android.content.ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId,
        ).toString()

    /** 递归遍历 SAF 树（[DocumentsContract]，框架 API，无额外依赖）。 */
    private fun walkTree(treeUri: Uri, docId: String, out: MutableList<LocalMediaItem>) {
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        }.getOrNull() ?: return
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        // 子树权限丢失等异常：跳过该分支并报告
        val cursor = runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        }.getOrElse { e ->
            println("[LocalMedia] SAF 子树查询失败，跳过: $docId (${e.message})")
            return
        } ?: return
        cursor.use { c ->
            while (c.moveToNext()) {
                val childId = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkTree(treeUri, childId, out)
                    continue
                }
                val mediaType = MediaType.fromFileName(name)
                if (mediaType == MediaType.OTHER) continue
                val docUri = runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                }.getOrNull() ?: continue
                out += LocalMediaItem(
                    path = docUri.toString(),
                    title = name.substringBeforeLast('.').ifBlank { name },
                    durationMs = 0L,
                    sizeBytes = c.getLong(3),
                    mediaType = mediaType,
                    source = LocalMediaOrigin.IMPORTED,
                    lastModified = c.getLong(4),
                )
            }
        }
    }
}
