package cp.player.kmp.download

import cp.player.kmp.media.LocalMediaItem
import cp.player.kmp.media.LocalMediaOrigin
import cp.player.kmp.media.MediaType
import cp.player.kmp.model.DownloadStatus
import cp.player.kmp.model.DownloadTask
import cp.player.kmp.music.CPMediaId
import cp.player.kmp.music.UnifiedMusicSource
import cp.player.kmp.util.PlatformContext
import cp.player.kmp.util.PlatformSupport
import cp.player.kmp.util.SettingsStorage
import cp.player.kmp.util.currentTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 通用媒体下载管理门面（对外唯一入口）。
 *
 * 只接受 [cp.player.kmp.music.CPMediaId] 形式的 mediaId（经 [UnifiedMusicSource] 取链），
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
     */
    suspend fun enqueue(
        mediaId: String,
        title: String,
        artist: String? = null,
        coverUrl: String? = null,
        mediaType: MediaType = MediaType.AUDIO,
        level: String = DEFAULT_QUALITY_LEVEL
    ): DownloadTask

    /** 暂停任务（保留 .part 供续传）。 */
    fun pause(id: String)

    /** 恢复已暂停任务（断点续传）。 */
    fun resume(id: String)

    /** 取消任务并删除 .part。 */
    fun cancel(id: String)

    /** 重试失败/已取消任务。 */
    fun retry(id: String)

    /** 移除任务记录；[deleteFile] 为 true 时删除已下载文件。 */
    fun remove(id: String, deleteFile: Boolean = false)

    /** 该 mediaId 是否已下载完成且文件仍存在。 */
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
 * @param onCompleted 下载完成回调：(任务, 本地产物条目)，供本地媒体索引登记
 * @param executor 下载执行器（缺省平台实现 JvmDownloadExecutor）
 */
class MediaDownloadManagerImpl(
    private val source: UnifiedMusicSource,
    settings: SettingsStorage,
    context: PlatformContext,
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
    private var started = false

    override val tasksFlow: StateFlow<List<DownloadTask>> get() = engine.tasksFlow

    override fun taskFlow(id: String): Flow<DownloadTask?> = engine.taskFlow(id)

    override suspend fun start() {
        if (started) return
        started = true
        // Store 已将 DOWNLOADING/PENDING 复位为 FAILED；此处补处理 COMPLETED 但文件丢失
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
        when {
            existing == null -> {
                val providerId = runCatching { CPMediaId.parse(mediaId).providerId }
                    .getOrDefault("unknown")
                val task = DownloadTask(
                    id = mediaId,
                    mediaId = mediaId,
                    providerId = providerId,
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

    override fun pause(id: String) = engine.pause(id)

    override fun resume(id: String) = engine.resume(id)

    override fun cancel(id: String) = engine.cancel(id)

    override fun retry(id: String) = engine.retry(id)

    override fun remove(id: String, deleteFile: Boolean) = engine.remove(id, deleteFile)

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
            lastModified = PlatformSupport.fileLastModified(path)
        )
        runCatching { callback(task, item) }
    }
}
