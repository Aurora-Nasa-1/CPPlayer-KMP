package cp.player.kmp.download

import cp.player.kmp.model.DownloadStatus
import cp.player.kmp.model.DownloadTask
import cp.player.kmp.util.PlatformSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 下载任务清单持久化（kotlinx.serialization → downloads.json）。
 *
 * 数据目录决策：清单文件位于 [cp.player.kmp.util.PlatformSupport.dataDir]
 * （Desktop `~/.kmp-pro/downloads.json`；Android `filesDir/downloads.json`），
 * 与 modulesDir 同级风格，避免与用户可见的下载文件目录混杂。
 *
 * 加载语义：
 * - `DOWNLOADING/PENDING` 态复位为 `FAILED`（error="上次未完成"），供用户重试；
 * - 清单上限 [MAX_RECORDS] 条，超出时裁剪：COMPLETED 优先保留（最近者优先），
 *   其余按创建时间从旧到新裁剪。
 *
 * 写入语义：临时文件 + 原子 rename，避免半写坏清单。
 */
class DownloadTaskStore(private val filePath: String) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val writeMutex = Mutex()

    /** 加载任务清单；文件缺失/损坏时返回空列表。 */
    suspend fun load(): List<DownloadTask> = withContext(Dispatchers.IO) {
        val raw = PlatformSupport.readTextFile(filePath)
        if (raw.isNullOrBlank()) return@withContext emptyList()
        val tasks = runCatching { json.decodeFromString<List<DownloadTask>>(raw) }
            .getOrDefault(emptyList())
        trimToLimit(tasks.map { task ->
            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PENDING) {
                task.copy(status = DownloadStatus.FAILED, error = "上次未完成")
            } else {
                task
            }
        })
    }

    /** 保存任务清单（超限时先裁剪）。 */
    suspend fun save(tasks: List<DownloadTask>) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val trimmed = trimToLimit(tasks)
                val tmpPath = "$filePath.tmp"
                if (!PlatformSupport.writeTextFile(tmpPath, json.encodeToString<List<DownloadTask>>(trimmed))) return@withLock
                // 检查 rename 结果：失败时记录并保留 tmp（不吞错），供后续诊断/重试
                if (!PlatformSupport.moveFile(tmpPath, filePath)) {
                    println("[DownloadTaskStore] 清单文件替换失败，保留临时文件: $tmpPath")
                }
            }
        }
    }

    /** 超限裁剪：COMPLETED 优先保留（最近优先），其余按 createdAt 从旧到新丢弃。 */
    private fun trimToLimit(tasks: List<DownloadTask>): List<DownloadTask> {
        if (tasks.size <= MAX_RECORDS) return tasks
        val byNewest = tasks.sortedByDescending { it.createdAt }
        val completed = byNewest.filter { it.status == DownloadStatus.COMPLETED }
        val others = byNewest.filter { it.status != DownloadStatus.COMPLETED }
        val keptCompleted = completed.take(MAX_RECORDS)
        val remainingSlots = (MAX_RECORDS - keptCompleted.size).coerceAtLeast(0)
        return (keptCompleted + others.take(remainingSlots)).sortedByDescending { it.createdAt }
    }

    companion object {
        /** 清单文件名 */
        const val FILE_NAME = "downloads.json"

        /** 清单上限条数 */
        const val MAX_RECORDS = 200
    }
}
