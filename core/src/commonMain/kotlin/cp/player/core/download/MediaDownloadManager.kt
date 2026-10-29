package cp.player.core.download

import cp.player.core.media.LocalMediaItem
import cp.player.core.media.LocalMediaOrigin
import cp.player.core.media.MediaType
import cp.player.core.model.DownloadStatus
import cp.player.core.model.DownloadTask
import cp.player.core.music.UnifiedMusicSource
import cp.player.core.playback.NoOpStreamLocalizer
import cp.player.core.playback.SongCacheEntry
import cp.player.core.playback.StreamLocalizer
import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformSupport
import cp.player.core.util.SettingsStorage
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 通用媒体下载管理门面（对外唯一入口）。
 *
 * 只接受 [cp.player.core.music.CPMediaId] 形式的 mediaId（经 [UnifiedMusicSource] 取链），
 * 杜绝任意 URL 下载。下载完成通过回调产出 [LocalMediaItem]（source = DOWNLOADED），
 * 由调用方接入本地媒体索引，本模块不依赖 local 包。
 */
interface MediaDownloadManager {

    /** 全部下载任务清单（实时状态 + 进度）。 */
    val tasksFlow: StateFlow<List<DownloadTask>>

    /** 单任务状态流（不存在时发射 null）。 */
    fun taskFlow(id: String): Flow<DownloadTask?>

    /** 启动恢复：加载持久化清单（应用启动时调用一次）。 */
    suspend fun start()

    /**
     * 加入下载队列。
     *
     * 幂等：同 mediaId 已完成且文件仍在 → 直接返回已有任务；
     * 进行中/暂停 → 返回已有任务；失败/已取消/文件丢失 → 自动重试。
     *
     * **缓存优先**：入队前先查歌曲缓存里有没有**同音质**的完整副本
     * （无损档位边播边落盘留下的那份）。命中就把缓存文件直接搬成下载、
     * 返回一个已完成的任务，**不联网重下**；未命中才走正常下载。
     * 匹配只认 `mediaId + 音质`（mediaId 自带 provider 前缀），
     * 不按歌名/歌手兜底 —— 见 [CacheImport.kt] 的 `isSameMediaResource`。
     */
    suspend fun enqueue(
        mediaId: String,
        title: String,
        artist: String? = null,
        coverUrl: String? = null,
        mediaType: MediaType = MediaType.AUDIO,
        level: String = DEFAULT_QUALITY_LEVEL
    ): DownloadTask

    /**
     * 把**歌曲缓存**里已完整的曲目一键转成正式下载（不联网）。
     *
     * 用户视角：缓存里躺着几十 MB 的成品音频，再点一次「下载」就重下一遍纯属浪费。
     * 实现做的事只有「复制 + 核对字节数 + 改名 + 登记任务」，登记后与普通下载产物
     * 完全一致（进下载列表、进本地曲库、可导出）。
     *
     * 两条硬规则：
     * - **只转能确定音源归属的条目**（[cp.player.core.playback.SongCacheEntry.isConvertibleToDownload]），
     *   旧缓存缺 mediaId 时宁可跳过，也不按歌名猜 provider；
     * - 已下载 / 正在下载的同一 mediaId **跳过**，不产生第二份文件。
     *
     * 成功后缓存副本会被删除（同一首歌不占两份空间）；删不掉（文件正被播放）也不影响
     * 转换结果 —— 那条缓存会在下次 LRU 淘汰时自然消失。
     *
     * @param entries 指定条目；**空列表 = 全部可转条目**
     */
    suspend fun importFromSongCache(entries: List<SongCacheEntry> = emptyList()): CacheImportSummary

    /** 暂停任务（保留 .part 供续传）。 */
    fun pause(id: String)

    /** 一键暂停全部进行中的任务。 */
    fun pauseAll()

    /** 恢复已暂停任务（断点续传）。 */
    fun resume(id: String)

    /** 一键恢复全部已暂停的任务。 */
    fun resumeAll()

    /** 取消任务并删除 .part。 */
    fun cancel(id: String)

    /** 一键取消全部未完成的任务。 */
    fun cancelAll()

    /** 重试失败/已取消任务。 */
    fun retry(id: String)

    /** 移除任务记录；[deleteFile] 为 true 时删除已下载文件。 */
    fun remove(id: String, deleteFile: Boolean = false)

    /** 该 mediaId 是否已下载完成且文件仍存在。 */
    /** 批量移除任务记录；[deleteFile] 为 true 时删除已下载文件与临时文件。 */
    fun removeTasks(ids: Collection<String>, deleteFile: Boolean = false) {
        ids.forEach { remove(it, deleteFile) }
    }

    fun isDownloaded(mediaId: String): Boolean

    /** 关闭（取消所有下载协程）。 */
    fun shutdown()

    companion object {
        /** 默认音质档位 */
        const val DEFAULT_QUALITY_LEVEL = "exhigh"
    }
}

/**
 * [MediaDownloadManager] 默认实现。
 *
 * @param source 统一音源（构造注入，取直链唯一通道）
 * @param settings 设置存储（下载根目录配置）
 * @param context 平台上下文（默认下载/数据目录解析）
 * @param cache 歌曲缓存句柄（无损流落盘的那份副本）。用来做「同音质缓存直接转下载」：
 *   缺省 [NoOpStreamLocalizer] 表示「没有缓存可用」（安卓 / 测试替身），行为与旧版一致。
 *   ⚠️ 必须与播放器/管理页是**同一实例**，否则读到的索引与磁盘上的缓存不是一回事。
 * @param onCompleted 下载完成回调：(任务, 本地产物条目)，供本地媒体索引登记
 * @param executor 下载执行器（缺省平台实现 JvmDownloadExecutor）
 */
class MediaDownloadManagerImpl(
    private val source: UnifiedMusicSource,
    settings: SettingsStorage,
    context: PlatformContext,
    private val cache: StreamLocalizer = NoOpStreamLocalizer,
    private val onCompleted: ((DownloadTask, LocalMediaItem) -> Unit)? = null,
    executor: DownloadExecutor = defaultDownloadExecutor()
) : MediaDownloadManager {

    /** 下载配置（根目录 / 并发数）。 */
    val config = DownloadConfig(settings, context)

    private val store = DownloadTaskStore(
        PlatformSupport.dataDir(context) + "/" + DownloadTaskStore.FILE_NAME
    )
    private val engine = DownloadEngine(source, config, executor, store, ::deliverCompleted)
    private val enqueueMutex = Mutex()
    private val lifecycleMutex = Mutex()

    /** 一键转下载串行化：两个入口同时点会为同一首算两次目标文件名。 */
    private val importMutex = Mutex()
    private var started = false

    override val tasksFlow: StateFlow<List<DownloadTask>> get() = engine.tasksFlow

    override fun taskFlow(id: String): Flow<DownloadTask?> = engine.taskFlow(id)

    override suspend fun start() {
        lifecycleMutex.withLock {
            if (started) return
            // Mark started only after a successful restore, so a transient storage failure can retry.
            val restored = store.load().map { task ->
                if (task.status == DownloadStatus.COMPLETED &&
                    task.localPath?.let { PlatformSupport.exists(it) } != true
                ) {
                    task.copy(status = DownloadStatus.FAILED, error = "本地文件丢失，请重新下载")
                } else {
                    task
                }
            }
            engine.restore(restored)
            store.save(restored)
            started = true
        }
    }

    override suspend fun enqueue(
        mediaId: String,
        title: String,
        artist: String?,
        coverUrl: String?,
        mediaType: MediaType,
        level: String
    ): DownloadTask = enqueueMutex.withLock {
        val existing = engine.tasksFlow.value.firstOrNull { it.mediaId == mediaId }

        // 已完成且文件还在 ⇒ 幂等返回（不重复转、也不重复下）
        if (existing != null && existing.status == DownloadStatus.COMPLETED &&
            existing.localPath?.let { PlatformSupport.exists(it) } == true
        ) {
            return@withLock existing
        }

        // 「同音质缓存直接转下载」：没有在途任务时才敢动 —— 进行中/暂停的任务有自己的
        // Job 与 .part，顶掉它会同时产生两份文件、并让那个 Job 之后写回过期状态。
        // 末尾的 COMPLETED 分支（走到这里 = 文件已丢失，上面已提前返回）也算「等重下」。
        if (existing == null ||
            existing.status == DownloadStatus.FAILED ||
            existing.status == DownloadStatus.CANCELLED ||
            existing.status == DownloadStatus.COMPLETED
        ) {
            adoptFromCache(mediaId, title, artist, coverUrl, mediaType, level)?.let { return@withLock it }
        }

        when {
            existing == null -> {
                val task = DownloadTask(
                    id = mediaId,
                    mediaId = mediaId,
                    providerId = providerIdOf(mediaId),
                    title = title,
                    artist = artist,
                    coverUrl = coverUrl,
                    mediaType = mediaType,
                    qualityLevel = level,
                    createdAt = currentTimeMillis()
                )
                engine.submit(task)
                task
            }
            existing.status == DownloadStatus.COMPLETED &&
                existing.localPath != null &&
                PlatformSupport.exists(existing.localPath) -> existing
            existing.status == DownloadStatus.FAILED ||
                existing.status == DownloadStatus.CANCELLED ||
                existing.status == DownloadStatus.COMPLETED -> {
                // 失败 / 已取消 / 完成但文件丢失 → 自动重试
                engine.retry(existing.id)
                existing.copy(status = DownloadStatus.PENDING, error = null)
            }
            else -> existing // PENDING / DOWNLOADING / PAUSED 原样返回
        }
    }

    override suspend fun importFromSongCache(entries: List<SongCacheEntry>): CacheImportSummary =
        importMutex.withLock {
            val candidates = if (entries.isEmpty()) {
                runCatching { cache.entries() }.getOrDefault(emptyList())
            } else {
                entries
            }
            var converted = 0
            var skipped = 0
            var failed = 0
            for (entry in candidates) {
                val mediaId = entry.mediaId?.takeIf { it.isNotBlank() }
                val level = entry.qualityLevel?.takeIf { it.isNotBlank() }
                if (!entry.isConvertibleToDownload || mediaId == null || level == null) {
                    // 旧缓存（索引没记 mediaId / 音质）⇒ 认不出是哪个音源的哪首歌，宁可不转
                    skipped++
                    continue
                }
                val existing = engine.tasksFlow.value.firstOrNull { it.mediaId == mediaId }
                val busy = existing != null && when (existing.status) {
                    DownloadStatus.PENDING,
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.PAUSED -> true
                    else -> false
                }
                val alreadyDownloaded = existing != null &&
                    existing.status == DownloadStatus.COMPLETED &&
                    existing.localPath?.let { PlatformSupport.exists(it) } == true
                if (busy || alreadyDownloaded) {
                    skipped++
                    continue
                }
                val sourcePath = runCatching { cache.entryPath(entry.id) }.getOrNull()
                if (sourcePath == null) {
                    skipped++
                    continue
                }
                val task = DownloadTask(
                    id = mediaId,
                    mediaId = mediaId,
                    providerId = providerIdOf(mediaId),
                    // 索引没记标题时用中性占位：core 没有文案树，这个词只会成为文件名前缀，
                    // 而 entries() 已尽量从音频标签补过标题（见 DesktopStreamLocalizer.entries）。
                    title = entry.title?.takeIf { it.isNotBlank() } ?: FALLBACK_TITLE,
                    artist = entry.artist,
                    coverUrl = null,
                    mediaType = MediaType.AUDIO,
                    qualityLevel = level,
                    createdAt = currentTimeMillis(),
                )
                val done = engine.adoptFromLocalFile(task, sourcePath)
                if (done == null) {
                    failed++
                } else {
                    // 已经搬成正式下载 ⇒ 缓存副本没有存在的必要（同一首歌别占两份空间）。
                    // 删不掉（正被播放）也正常：那条缓存会随 LRU 淘汰自然消失。
                    runCatching { cache.remove(entry.id) }
                    converted++
                }
            }
            CacheImportSummary(converted = converted, skipped = skipped, failed = failed)
        }

    /**
     * 找**同 mediaId + 同音质**的完整缓存并把它搬成下载；没有则 null。
     *
     * 只对音频生效：缓存目录只存音频流，视频走另一条链路（MV/本地文件），
     * 拿一份音频文件去满足视频下载会得到「文件在、画面没有」的坏结果。
     */
    private suspend fun adoptFromCache(
        mediaId: String,
        title: String,
        artist: String?,
        coverUrl: String?,
        mediaType: MediaType,
        level: String,
    ): DownloadTask? {
        if (mediaType != MediaType.AUDIO) return null
        // 缓存只可能存在于「需要落盘的无损档位」（见 StreamLocalizer.LOSSLESS_LEVELS）：
        // 请求别的档位时必然没有同音质缓存，直接短路，省掉一次缓存目录全扫
        // （歌单「全部下载」是几百次入队，那里每次全扫一遍并不便宜）。
        if (!cache.isLocalizing(level)) return null
        val entry = runCatching {
            cache.entries().firstOrNull {
                it.qualityLevel == level && isSameMediaResource(it.mediaId, mediaId)
            }
        }.getOrNull() ?: return null
        val sourcePath = runCatching { cache.entryPath(entry.id) }.getOrNull() ?: return null
        val task = DownloadTask(
            id = mediaId,
            mediaId = mediaId,
            providerId = providerIdOf(mediaId),
            title = title,
            artist = artist,
            coverUrl = coverUrl,
            mediaType = mediaType,
            qualityLevel = level,
            createdAt = currentTimeMillis(),
        )
        val completed = engine.adoptFromLocalFile(task, sourcePath) ?: return null
        runCatching { cache.remove(entry.id) }
        return completed
    }

    override fun pause(id: String) = engine.pause(id)
    override fun pauseAll() = engine.pauseAll()

    override fun resume(id: String) = engine.resume(id)
    override fun resumeAll() = engine.resumeAll()

    override fun cancel(id: String) = engine.cancel(id)
    override fun cancelAll() = engine.cancelAll()

    override fun retry(id: String) = engine.retry(id)

    override fun remove(id: String, deleteFile: Boolean) = engine.remove(id, deleteFile)
    override fun removeTasks(ids: Collection<String>, deleteFile: Boolean) = engine.removeTasks(ids, deleteFile)

    override fun isDownloaded(mediaId: String): Boolean =
        engine.tasksFlow.value.any { task ->
            task.mediaId == mediaId &&
                task.status == DownloadStatus.COMPLETED &&
                task.localPath?.let { PlatformSupport.exists(it) } == true
        }

    override fun shutdown() = engine.shutdown()

    /** 下载完成 → 组装 LocalMediaItem(source=DOWNLOADED) 交付回调（不依赖 local 包）。 */
    private fun deliverCompleted(task: DownloadTask) {
        val callback = onCompleted ?: return
        val path = task.localPath ?: return
        val item = LocalMediaItem(
            path = path,
            title = task.title,
            artist = task.artist,
            durationMs = 0L, // 时长探测由本地索引侧负责
            sizeBytes = PlatformSupport.fileSize(path),
            mediaType = task.mediaType,
            coverUri = task.coverUrl,
            source = LocalMediaOrigin.DOWNLOADED,
            lastModified = PlatformSupport.fileLastModified(path),
            originalMediaId = task.mediaId,
        )
        runCatching { callback(task, item) }
    }

    private companion object {
        /**
         * 转下载时索引里没有标题的占位名。
         *
         * core 没有文案树（见 `docs/dev/I18N.md`），这个词最终只会出现在文件名前缀里；
         * 正常路径上 [cp.player.core.playback.StreamLocalizer.entries] 已经从音频标签把
         * 标题补出来了，这里只兜底「连标签都没有」的极少数缓存。
         */
        const val FALLBACK_TITLE = "untitled"
    }
}
