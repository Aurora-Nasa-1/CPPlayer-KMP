package cp.player.kmp.download

import cp.player.kmp.BackendResult
import cp.player.kmp.media.MediaType
import cp.player.kmp.model.DownloadStatus
import cp.player.kmp.model.DownloadTask
import cp.player.kmp.music.SongUrl
import cp.player.kmp.music.UnifiedMusicSource
import cp.player.kmp.util.PlatformSupport
import cp.player.kmp.util.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 下载编排核心（commonMain，全程 Dispatchers.IO）。
 *
 * 职责：
 * - 队列 + [Semaphore] 并发控制（[DownloadConfig.maxConcurrent]），每个任务一个 Job；
 * - 流程：执行时现取直链（不缓存）→ expireAt 过期防护 → 解析目标文件名
 *   `标题 - 艺术家.ext`（非法字符清洗、同名冲突追加 (2)/(3)）→ 写 `<name>.part`
 *   → 已有 .part 时带 `Range: bytes=N-` 续传（服务端不支持则清空重下）
 *   → 注入 [SongUrl.cookie] 为 Cookie 头 → 完成后原子 rename 去 .part；
 * - 失败指数退避重试（1s/2s/4s，最多 3 次）后转 FAILED；
 * - pause/resume/cancel/retry/remove 控制操作；
 * - 任务清单合并为 [tasksFlow]，进度更新节流 [PROGRESS_THROTTLE_MS]；
 * - 每次状态/进度变更经 [store] 持久化。
 *
 * 目标路径稳定性：任务首次进入下载时即确定 [DownloadTask.localPath]（最终文件路径）
 * 并持久化，保证暂停/重启后续传仍写同一 `.part` 文件。
 */
class DownloadEngine(
    private val source: UnifiedMusicSource,
    private val config: DownloadConfig,
    private val executor: DownloadExecutor,
    private val store: DownloadTaskStore? = null,
    private val onTaskCompleted: ((DownloadTask) -> Unit)? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("DownloadEngine"))
    private val semaphore = Semaphore(config.maxConcurrent)

    private val jobsMutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()

    private val progressMutex = Mutex()
    private val lastProgressEmit = mutableMapOf<String, Long>()

    /** Serialize snapshots so a slow disk write cannot overwrite a newer state. */
    private val persistMutex = Mutex()

    /** 目标路径解析锁：「解析路径 + 写入 localPath」整体原子化，避免同名任务并发解析出同一路径。 */
    private val pathMutex = Mutex()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

    /** 全部任务清单（状态 + 进度实时合并）。 */
    val tasksFlow: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** 单任务状态流（不存在时发射 null）。 */
    fun taskFlow(id: String): Flow<DownloadTask?> =
        tasksFlow.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    /** 查询单个任务。 */
    fun taskById(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    /**
     * 启动恢复：装载持久化清单（复位逻辑由 Store / Manager 先行处理）。
     *
     * 按 id 合并而非整体替换：引擎中已存在的任务（如异步 start 与快速入队交错时
     * 先经 [submit] 入队的新任务）原样保留，不会被恢复清单抹掉。
     */
    fun restore(tasks: List<DownloadTask>) {
        _tasks.update { existing ->
            val existingIds = existing.mapTo(mutableSetOf()) { it.id }
            existing + tasks.filter { it.id !in existingIds }
        }
    }

    /** 提交新任务（或重置已有任务）并立即入队。 */
    suspend fun submit(task: DownloadTask) {
        val fresh = task.copy(status = DownloadStatus.PENDING, error = null)
        _tasks.update { list ->
            if (list.any { it.id == fresh.id }) list.map { if (it.id == fresh.id) fresh else it }
            else list + fresh
        }
        persist()
        launchJob(fresh.id)
    }

    /** 暂停：条件式原子转 PAUSED（仅从 PENDING/DOWNLOADING），成功后 cancel Job（保留 .part 供续传）。 */
    fun pause(id: String) {
        scope.launch {
            // 源状态校验置于 updateTask 变换内原子完成，避免与 executeOnce 的状态写竞态
            val paused = updateTaskIf(
                id,
                predicate = { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING },
                transform = { it.copy(status = DownloadStatus.PAUSED) }
            )
            if (paused == null) return@launch
            jobsMutex.withLock { jobs[id] }?.cancel()
        }
    }

    /** 恢复：PAUSED → PENDING 重新入队续传。 */
    fun resume(id: String) {
        scope.launch { requeue(id, setOf(DownloadStatus.PAUSED)) }
    }

    /**
     * 重试：FAILED/CANCELLED/COMPLETED → PENDING 重新入队。
     *
     * 含 COMPLETED：覆盖「已完成但 localPath 为空 / 本地文件丢失」场景
     * （enqueue 幂等分支对该场景调用本方法，须真实触发重新下载）。
     */
    fun retry(id: String) {
        scope.launch {
            requeue(id, setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.COMPLETED))
        }
    }

    /** 取消：条件式原子转 CANCELLED，Job 终止后按最新 localPath 删除 .part。 */
    fun cancel(id: String) {
        scope.launch {
            // 源状态校验置于 updateTask 变换内原子完成（COMPLETED/CANCELLED 不可取消）
            val cancelled = updateTaskIf(
                id,
                predicate = { it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.COMPLETED },
                transform = { it.copy(status = DownloadStatus.CANCELLED) }
            )
            if (cancelled == null) return@launch
            jobsMutex.withLock { jobs[id] }?.cancelAndJoin()
            // cancelAndJoin 之后重新读取最新 localPath：下载协程可能已写入新路径，
            // 提前快照会漏删新生成的 .part
            taskById(id)?.localPath?.let { PlatformSupport.deleteRecursively("$it.part") }
        }
    }

    /** 移除任务记录；[deleteFile] 为 true 时连同已下载文件与 .part 一并删除。 */
    fun remove(id: String, deleteFile: Boolean = false) {
        scope.launch {
            jobsMutex.withLock { jobs[id] }?.cancelAndJoin()
            jobsMutex.withLock { jobs.remove(id) }
            val task = taskById(id)
            if (deleteFile && task != null) {
                task.localPath?.let {
                    PlatformSupport.deleteRecursively(it)
                    PlatformSupport.deleteRecursively("$it.part")
                }
            }
            _tasks.update { list -> list.filterNot { it.id == id } }
            progressMutex.withLock { lastProgressEmit.remove(id) }
            persist()
        }
    }

    /** 关闭引擎（取消所有任务协程）。 */
    fun shutdown() {
        scope.cancel()
    }

    // ============ 内部实现 ============

    private suspend fun requeue(id: String, fromStatuses: Set<DownloadStatus>) {
        val task = taskById(id) ?: return
        if (task.status !in fromStatuses) return
        updateTask(id) { it.copy(status = DownloadStatus.PENDING, error = null) }
        launchJob(id)
    }

    /** 必须在挂起上下文调用；jobs 读写统一经 jobsMutex。 */
    private suspend fun launchJob(id: String) {
        val job = scope.launch {
            semaphore.withPermit { runDownload(id) }
        }
        jobsMutex.withLock {
            jobs[id]?.cancel()
            jobs[id] = job
        }
        job.invokeOnCompletion {
            scope.launch { jobsMutex.withLock { if (jobs[id] === job) jobs.remove(id) } }
        }
    }

    private suspend fun runDownload(id: String) {
        var attempts = 0
        var backoffMs = INITIAL_BACKOFF_MS
        var resumeRetried = false
        while (true) {
            val task = taskById(id) ?: return
            if (task.status != DownloadStatus.PENDING && task.status != DownloadStatus.DOWNLOADING) return
            try {
                executeOnce(task)
                return
            } catch (ce: CancellationException) {
                // pause/cancel 已先行写入目标状态，直接终止
                throw ce
            } catch (nrs: ResumeNotSupportedException) {
                // 服务端不支持续传：清空 .part 立即重下（不计入重试次数，仅一次）
                if (resumeRetried) {
                    markFailed(id, "服务端不支持断点续传")
                    return
                }
                resumeRetried = true
                // 读最新 localPath（旧快照可能尚未写入首次解析的路径）
                taskById(id)?.localPath?.let { PlatformSupport.deleteRecursively("$it.part") }
            } catch (e: Exception) {
                attempts++
                if (attempts > MAX_RETRIES) {
                    markFailed(id, e.message ?: "下载失败")
                    return
                }
                delay(backoffMs)
                backoffMs *= 2
            }
        }
    }

    private suspend fun executeOnce(task: DownloadTask) {
        // 条件式原子迁移：仅 PENDING/DOWNLOADING → DOWNLOADING（重试循环内二次进入时保持 DOWNLOADING），
        // 防止覆盖 pause/cancel 竞态写入的 PAUSED/CANCELLED；迁移失败说明已被控制操作接管，直接退出
        val active = updateTaskIf(
            task.id,
            predicate = { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING },
            transform = { it.copy(status = DownloadStatus.DOWNLOADING, error = null) }
        )
        if (active == null) return

        // 1. 执行时现取直链（不缓存），expireAt 过期/接近过期则重新取
        val songUrl = fetchSongUrl(task)
        ensureNotInterrupted(task.id)

        // 2. 确定目标路径（首次解析后持久化到 localPath，保证续传稳定）；
        //    「解析路径 + 写入 localPath」整体置于 pathMutex 临界区完成路径预订，
        //    避免不同 mediaId 同名任务并发解析出同一目标路径、交错写同一 .part
        val targetPath = task.localPath?.takeIf { it.isNotBlank() }
            ?: pathMutex.withLock {
                // 锁内复查：等锁期间可能已被其它分支写入
                val locked = taskById(task.id)?.localPath?.takeIf { it.isNotBlank() }
                if (locked != null) {
                    locked
                } else {
                    val resolved = resolveTargetPath(task, songUrl.url)
                    updateTask(task.id) { it.copy(localPath = resolved) }
                    resolved
                }
            }
        ensureNotInterrupted(task.id)
        val parentDir = targetPath.substringBeforeLast('/', "")
        if (parentDir.isNotBlank()) PlatformSupport.ensureDir(parentDir)

        // 3. 断点续传：已有 .part 则带 Range 头
        val partPath = "$targetPath.part"
        val resumeBytes = if (PlatformSupport.exists(partPath)) PlatformSupport.fileSize(partPath) else 0L
        val headers = buildMap {
            songUrl.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            if (resumeBytes > 0L) put("Range", "bytes=$resumeBytes-")
        }

        // 4. 流式下载（进度节流 250ms）
        val totalBytes = songUrl.sizeBytes
        val written = executor.download(songUrl.url, headers, partPath, resumeBytes) { sessionWritten ->
            reportProgress(task.id, resumeBytes + sessionWritten, totalBytes)
        }

        // 挂起点后复查状态：pause/cancel 已转目标态则自行退出，避免完成写覆盖
        ensureNotInterrupted(task.id)

        // 5. 完成：原子 rename 去 .part
        if (!PlatformSupport.moveFile(partPath, targetPath)) {
            throw DownloadFailedException("下载临时文件重命名失败: $partPath")
        }
        val downloaded = resumeBytes + written
        // 条件式完成迁移：仅从 PENDING/DOWNLOADING → COMPLETED，不覆盖并发写入的 PAUSED/CANCELLED
        val completed = updateTaskIf(
            task.id,
            predicate = { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING },
            transform = {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 1f,
                    downloadedBytes = downloaded,
                    totalBytes = totalBytes ?: downloaded,
                    localPath = targetPath,
                    error = null
                )
            }
        )
        progressMutex.withLock { lastProgressEmit.remove(task.id) }
        if (completed != null) onTaskCompleted?.invoke(completed)
    }

    /**
     * 挂起点之后复查任务状态：已被 pause/cancel 迁移（或任务被移除）时抛出
     * [CancellationException] 自行退出（runDownload 透传终止），防止后续写覆盖目标状态。
     */
    private fun ensureNotInterrupted(id: String) {
        when (taskById(id)?.status) {
            DownloadStatus.PAUSED, DownloadStatus.CANCELLED ->
                throw CancellationException("下载任务已被暂停/取消: $id")
            null -> throw CancellationException("下载任务不存在: $id")
            else -> Unit
        }
    }

    /** 现取直链；expireAt 为空、或距过期 > [EXPIRE_GUARD_MS] 方可使用，否则重取。 */
    private suspend fun fetchSongUrl(task: DownloadTask): SongUrl {
        var lastError: String? = null
        repeat(URL_FETCH_ATTEMPTS) {
            when (val result = source.getSongUrl(task.mediaId, task.qualityLevel)) {
                is BackendResult.Success -> {
                    val songUrl = result.data
                    when {
                        songUrl.url.isBlank() -> lastError = "取直链失败：地址为空"
                        songUrl.expireAt == null ||
                            songUrl.expireAt > currentTimeMillis() + EXPIRE_GUARD_MS -> return songUrl
                        else -> lastError = "直链已过期，重试取链"
                    }
                }
                is BackendResult.Error -> lastError = result.message
                is BackendResult.Unsupported ->
                    throw DownloadFailedException("取直链失败：${result.message}")
            }
        }
        throw DownloadFailedException(lastError ?: "取直链失败")
    }

    /** 解析目标文件路径：`标题 - 艺术家.ext`，清洗非法字符，同名冲突追加 (2)/(3)。 */
    private fun resolveTargetPath(task: DownloadTask, url: String): String {
        val root = config.rootDir
        val ext = inferExtension(url, task.mediaType)
        val base = buildString {
            append(sanitizeFileName(task.title))
            task.artist?.takeIf { it.isNotBlank() }?.let { append(" - ").append(sanitizeFileName(it)) }
        }.ifBlank { "download" }
        val taken = _tasks.value.mapNotNull { it.localPath }.toSet()
        var candidate = "$base.$ext"
        var ordinal = 2
        while (PlatformSupport.exists("$root/$candidate") ||
            PlatformSupport.exists("$root/$candidate.part") ||
            "$root/$candidate" in taken
        ) {
            candidate = "$base ($ordinal).$ext"
            ordinal++
        }
        return "$root/$candidate"
    }

    /** 扩展名推断：优先 URL 路径后缀，其次按 [MediaType] 缺省。 */
    private fun inferExtension(url: String, mediaType: MediaType): String {
        val fileName = url.substringBefore('?').substringAfterLast('#').substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
        if (ext.isNotBlank() && ext.length <= 5) return ext
        return when (mediaType) {
            MediaType.AUDIO -> "mp3"
            MediaType.VIDEO -> "mp4"
            MediaType.OTHER -> "bin"
        }
    }

    /** 清洗文件名非法字符（Windows/Unix 通用），限长并折叠空白。 */
    private fun sanitizeFileName(name: String): String =
        name.replace(ILLEGAL_FILE_NAME_CHARS, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_FILE_NAME_LENGTH)
            .ifBlank { "untitled" }

    /** 进度节流上报（250ms）。 */
    private suspend fun reportProgress(id: String, downloadedBytes: Long, totalBytes: Long?) {
        val now = currentTimeMillis()
        val shouldEmit = progressMutex.withLock {
            val last = lastProgressEmit[id] ?: 0L
            if (now - last >= PROGRESS_THROTTLE_MS) {
                lastProgressEmit[id] = now
                true
            } else {
                false
            }
        }
        if (!shouldEmit) return
        updateTask(id) {
            it.copy(
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes ?: it.totalBytes,
                progress = if (totalBytes != null && totalBytes > 0L) {
                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    it.progress
                }
            )
        }
    }

    private suspend fun markFailed(id: String, message: String) {
        updateTask(id) { it.copy(status = DownloadStatus.FAILED, error = message) }
    }

    /**
     * 条件式更新单个任务并持久化：仅当 [predicate] 对当前任务成立时应用 [transform]
     *（源状态校验与写入在同一 [MutableStateFlow.update] 变换内原子完成）。
     *
     * @return 应用后的任务；谓词不成立或任务不存在时返回 null（未做任何写入）
     */
    private suspend fun updateTaskIf(
        id: String,
        predicate: (DownloadTask) -> Boolean,
        transform: (DownloadTask) -> DownloadTask
    ): DownloadTask? {
        var updated: DownloadTask? = null
        _tasks.update { list ->
            list.map {
                if (it.id == id && predicate(it)) {
                    val next = transform(it)
                    updated = next
                    next
                } else {
                    it
                }
            }
        }
        if (updated != null) persist()
        return updated
    }

    /** 更新单个任务并持久化；返回更新后的任务（不存在时 null）。 */
    private suspend fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask): DownloadTask? {
        var updated: DownloadTask? = null
        _tasks.update { list ->
            list.map {
                if (it.id == id) {
                    val next = transform(it)
                    updated = next
                    next
                } else {
                    it
                }
            }
        }
        persist()
        return updated
    }

    private suspend fun persist() {
        val taskSnapshot = _tasks.value
        persistMutex.withLock {
            store?.save(taskSnapshot)
        }
    }

    companion object {
        /** 最大重试次数（指数退避 1s/2s/4s） */
        private const val MAX_RETRIES = 3

        /** 初始退避时长（毫秒） */
        private const val INITIAL_BACKOFF_MS = 1000L

        /** 进度上报节流间隔（毫秒） */
        private const val PROGRESS_THROTTLE_MS = 250L

        /** 直链过期防护余量：距过期不足该值则重新取链（毫秒） */
        private const val EXPIRE_GUARD_MS = 60_000L

        /** 单次执行内的取链尝试次数（过期刷新） */
        private const val URL_FETCH_ATTEMPTS = 3

        /** 文件名最大长度 */
        private const val MAX_FILE_NAME_LENGTH = 120

        /** 文件名非法字符（Windows + Unix 控制字符） */
        private val ILLEGAL_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    }
}
