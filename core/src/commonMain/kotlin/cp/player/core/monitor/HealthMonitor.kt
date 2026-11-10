package cp.player.core.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max
import kotlin.math.min

/**
 * 取数健康监控（KMP 版，三级分类）。
 *
 * 三级分类语义：
 * - [HealthLevel.OK]      拿到结果，可直接使用
 * - [HealthLevel.WARNING] 音源明确做不到（[cp.player.core.BackendResult.Unsupported]），不是故障
 * - [HealthLevel.ERROR]   调用失败（异常 / Provider 错误），需回退或重试
 *
 * 覆盖范围：由取数边界（`UnifiedMusicSourceImpl` 的 `boundary`）逐次上报 [recordCall]。
 * 边界是唯一知道「这次调用是哪个操作、花了多久、结果属于哪一类」的地方，
 * 因此监控不需要（也不应该）再往各 Provider 里插桩。
 *
 * ### 性能设计
 * - 使用 [MutableStateFlow.update] 避免每次记录都完整复制列表
 * - 统计查询从 Flow snapshot 计算，不阻塞记录路径
 * - 环形缓冲区用 ArrayList 预分配，避免频繁扩容
 */
object HealthMonitor {

    private const val MAX_RECORDS = 500

    // ======================== 数据模型 ========================

    /**
     * 健康等级。ERROR 表示调用失败，WARNING 表示音源不支持该操作，OK 表示正常。
     */
    enum class HealthLevel { OK, WARNING, ERROR }

    /**
     * 一次取数操作的记录。
     *
     * 只保留强类型链路真实存在的语义：谁（音源）、做了什么（操作名）、多久、结果如何。
     * 旧 JSON 投影层的响应码 / 缺字段 / 原始报文等字段已随兼容层一起删除。
     */
    data class ApiCallRecord(
        val timestamp: Long,
        val providerId: String,
        /** 取数操作名，与 `UnifiedMusicSource` 的方法名一致（如 `getTrackDetail`）。 */
        val operation: String,
        val durationMs: Long,
        val level: HealthLevel,
        val errorMessage: String? = null,
    ) {
        /** 是否拿到结果（`Unsupported` 不算成功，但也不是故障）。 */
        val success: Boolean get() = level == HealthLevel.OK
    }

    data class ProviderHealthStats(
        val providerId: String,
        val providerName: String,
        val totalCalls: Int,
        val successCount: Int,
        val failCount: Int,
        val warningCount: Int,
        val errorCount: Int,
        val avgResponseMs: Long,
        val p95ResponseMs: Long,
        val lastError: String?,
        val lastErrorTime: Long,
        val healthScore: Float,
        /** 综合健康等级：取近 N 条记录中最差等级 */
        val overallLevel: HealthLevel
    )

    data class OperationStats(
        val operation: String,
        val totalCalls: Int,
        val successCount: Int,
        val failCount: Int,
        val avgResponseMs: Long,
        val warningCount: Int,
        val errorCount: Int
    )

    // ======================== 状态 ========================

    private val _recordsFlow = MutableStateFlow<List<ApiCallRecord>>(emptyList())
    val recordsFlow: StateFlow<List<ApiCallRecord>> = _recordsFlow.asStateFlow()

    /** 综合健康等级流，便于 UI 顶部状态指示 */
    private val _overallLevelFlow = MutableStateFlow(HealthLevel.OK)
    val overallLevelFlow: StateFlow<HealthLevel> = _overallLevelFlow.asStateFlow()

    // ======================== 记录 ========================

    /**
     * 记录一次取数操作。使用 Flow update 避免完整列表复制。
     */
    fun recordCall(record: ApiCallRecord) {
        _recordsFlow.update { current ->
            val next = if (current.size >= MAX_RECORDS) {
                ArrayList<ApiCallRecord>(MAX_RECORDS).apply {
                    addAll(current.subList(1, current.size))
                    add(record)
                }
            } else {
                ArrayList<ApiCallRecord>(current.size + 1).apply {
                    addAll(current)
                    add(record)
                }
            }
            next
        }
        _overallLevelFlow.value = computeOverallLevel(_recordsFlow.value)
    }

    fun clearRecords() {
        _recordsFlow.value = emptyList()
        _overallLevelFlow.value = HealthLevel.OK
    }

    // ======================== 查询（从 Flow snapshot 计算，不加锁） ========================

    fun getStats(providerId: String, providerName: String = providerId): ProviderHealthStats {
        return buildStats(providerId, providerName, _recordsFlow.value.filter { it.providerId == providerId })
    }

    fun getAllStats(): Map<String, ProviderHealthStats> {
        val snapshot = _recordsFlow.value
        val providerIds = snapshot.asSequence().map { it.providerId }.distinct().toList()
        return providerIds.associateWith { id ->
            buildStats(id, id, snapshot.filter { it.providerId == id })
        }
    }

    /**
     * 取最近的调用记录（从新到旧，最多 [limit] 条）。
     *
     * 所有过滤条件都**在截断之前**生效 —— `limit` 是「过滤后的条数」，
     * 不是「扫描的条数」。否则像 `getRecentRecords(limit = 1, onlyWarnings = true)`
     * 这样先截断再按 operation 挑的用法会几乎永远挑不中。
     *
     * @param operation 只看某个操作（`UnifiedMusicSource` 方法名）的记录
     */
    fun getRecentRecords(
        limit: Int = 100,
        providerId: String? = null,
        operation: String? = null,
        onlyFailures: Boolean = false,
        onlyWarnings: Boolean = false,
        onlyErrors: Boolean = false
    ): List<ApiCallRecord> {
        val snapshot = _recordsFlow.value
        val result = mutableListOf<ApiCallRecord>()
        for (i in snapshot.indices.reversed()) {
            if (result.size >= limit) break
            val r = snapshot[i]
            if (providerId != null && r.providerId != providerId) continue
            if (operation != null && r.operation != operation) continue
            if (onlyFailures && r.success) continue
            if (onlyWarnings && r.level != HealthLevel.WARNING) continue
            if (onlyErrors && r.level != HealthLevel.ERROR) continue
            result.add(r)
        }
        return result
    }

    fun getStatsByOperation(): Map<String, OperationStats> {
        val snapshot = _recordsFlow.value
        return snapshot.groupBy { it.operation }.mapValues { (operation, list) ->
            var successCount = 0
            var failCount = 0
            var totalDuration = 0L
            var warningCount = 0
            var errorCount = 0

            for (r in list) {
                if (r.success) successCount++ else failCount++
                totalDuration += r.durationMs
                when (r.level) {
                    HealthLevel.WARNING -> warningCount++
                    HealthLevel.ERROR -> errorCount++
                    HealthLevel.OK -> {}
                }
            }

            OperationStats(
                operation = operation,
                totalCalls = list.size,
                successCount = successCount,
                failCount = failCount,
                avgResponseMs = if (list.isNotEmpty()) totalDuration / list.size else 0L,
                warningCount = warningCount,
                errorCount = errorCount
            )
        }
    }

    // ======================== 内部 ========================

    /**
     * 综合健康等级：扫描最近窗口，最差等级优先（ERROR > WARNING > OK）。
     */
    private fun computeOverallLevel(records: List<ApiCallRecord>): HealthLevel {
        if (records.isEmpty()) return HealthLevel.OK
        val window = records.takeLast(100)
        return window.fold(HealthLevel.OK) { acc, r ->
            when {
                r.level == HealthLevel.ERROR -> HealthLevel.ERROR
                r.level == HealthLevel.WARNING && acc != HealthLevel.ERROR -> HealthLevel.WARNING
                else -> acc
            }
        }
    }

    private fun buildStats(providerId: String, providerName: String, list: List<ApiCallRecord>): ProviderHealthStats {
        val total = list.size
        var success = 0
        var totalDuration = 0L
        var warningCount = 0
        var errorCount = 0
        var lastFail: ApiCallRecord? = null

        for (r in list) {
            if (r.success) success++
            totalDuration += r.durationMs
            when (r.level) {
                HealthLevel.WARNING -> warningCount++
                HealthLevel.ERROR -> errorCount++
                HealthLevel.OK -> {}
            }
            if (r.level == HealthLevel.ERROR) lastFail = r
        }

        val fail = total - success
        val avgMs = if (total > 0) totalDuration / total else 0L
        val p95Ms = if (total > 0) {
            val sorted = list.asSequence().map { it.durationMs }.sorted().toList()
            sorted[(total * 0.95f).toInt().coerceIn(0, total - 1)]
        } else 0L

        val successRate = if (total > 0) success.toFloat() / total else 1f
        val responseScore = if (avgMs <= 0) 1f else max(0f, min(1f, 1f - (avgMs - 500f) / 4500f))
        val cleanRate = if (total > 0) (total - warningCount - errorCount).toFloat() / total else 1f
        val score = successRate * 0.6f + responseScore * 0.2f + cleanRate * 0.2f

        return ProviderHealthStats(
            providerId = providerId,
            providerName = providerName,
            totalCalls = total,
            successCount = success,
            failCount = fail,
            warningCount = warningCount,
            errorCount = errorCount,
            avgResponseMs = avgMs,
            p95ResponseMs = p95Ms,
            lastError = lastFail?.errorMessage,
            lastErrorTime = lastFail?.timestamp ?: 0L,
            healthScore = score,
            overallLevel = computeOverallLevel(list)
        )
    }
}
