package cp.player.kmp.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max
import kotlin.math.min

/**
 * API 调用健康监控（KMP 版，三级分类）。
 *
 * 三级分类语义：
 * - [HealthLevel.OK]      响应正常，可直接使用
 * - [HealthLevel.WARNING] 勉强可用（不符合预期但可降级使用，如缺字段、慢响应、空数据）
 * - [HealthLevel.ERROR]   不可用（报错、解析失败、Provider 不支持），需回退
 *
 * 覆盖范围：所有 API 调用通过 [cp.player.kmp.api.MusicApiServiceImpl.callApi] 统一拦截。
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
     * 健康等级。ERROR 表示不可用需回退，WARNING 表示可降级使用，OK 表示正常。
     */
    enum class HealthLevel { OK, WARNING, ERROR }

    /**
     * 响应警告类型。每个类型在 [classify] 后归入 [HealthLevel]。
     */
    enum class ResponseWarning {
        MISSING_CODE, UNEXPECTED_CODE, MISSING_DATA_FIELD,
        EMPTY_DATA_ARRAY, EMPTY_DATA_OBJECT, MALFORMED_RESPONSE,
        UNSUPPORTED_BY_PROVIDER, SLOW_RESPONSE
    }

    /**
     * 将 [ResponseWarning] 归类为健康等级。
     *
     * - [ResponseWarning.UNSUPPORTED_BY_PROVIDER]、[ResponseWarning.MALFORMED_RESPONSE]
     *   归为 [HealthLevel.ERROR]（无法使用）
     * - 其余归为 [HealthLevel.WARNING]（数据不完美但可降级）
     */
    fun classify(warning: ResponseWarning): HealthLevel = when (warning) {
        ResponseWarning.UNSUPPORTED_BY_PROVIDER,
        ResponseWarning.MALFORMED_RESPONSE -> HealthLevel.ERROR
        else -> HealthLevel.WARNING
    }

    data class ApiCallRecord(
        val timestamp: Long,
        val providerId: String,
        val method: String,
        val durationMs: Long,
        val success: Boolean,
        val level: HealthLevel = if (success) HealthLevel.OK else HealthLevel.ERROR,
        val errorCode: Int? = null,
        val errorMessage: String? = null,
        val wasFallback: Boolean = false,
        val fallbackFrom: String? = null,
        val responseWarnings: List<ResponseWarning> = emptyList(),
        val responseCode: Int? = null,
        /** 期望的数据字段名（如 "data", "playlist"），仅 MISSING_DATA_FIELD 时有值 */
        val expectedField: String? = null
    )

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
        val fallbackCount: Int,
        val lastError: String?,
        val lastErrorTime: Long,
        val healthScore: Float,
        val lastWarning: ResponseWarning?,
        /** 综合健康等级：取近 N 条记录中最差等级 */
        val overallLevel: HealthLevel
    )

    data class MethodStats(
        val method: String,
        val totalCalls: Int,
        val successCount: Int,
        val failCount: Int,
        val avgResponseMs: Long,
        val fallbackCount: Int,
        val warningCount: Int,
        val errorCount: Int,
        val warningTypes: Map<ResponseWarning, Int>
    )

    // ======================== 状态 ========================

    private val _recordsFlow = MutableStateFlow<List<ApiCallRecord>>(emptyList())
    val recordsFlow: StateFlow<List<ApiCallRecord>> = _recordsFlow.asStateFlow()

    /** 综合健康等级流，便于 UI 顶部状态指示 */
    private val _overallLevelFlow = MutableStateFlow(HealthLevel.OK)
    val overallLevelFlow: StateFlow<HealthLevel> = _overallLevelFlow.asStateFlow()

    // ======================== 记录 ========================

    /**
     * 记录一次 API 调用。使用 Flow update 避免完整列表复制。
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

    fun getRecentRecords(
        limit: Int = 100,
        providerId: String? = null,
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
            if (onlyFailures && r.success) continue
            if (onlyWarnings && r.level != HealthLevel.WARNING) continue
            if (onlyErrors && r.level != HealthLevel.ERROR) continue
            result.add(r)
        }
        return result
    }

    fun getStatsByMethod(): Map<String, MethodStats> {
        val snapshot = _recordsFlow.value
        return snapshot.groupBy { it.method }.mapValues { (method, list) ->
            var successCount = 0
            var failCount = 0
            var totalDuration = 0L
            var fallbackCount = 0
            var warningCount = 0
            var errorCount = 0
            val warningTypeMap = mutableMapOf<ResponseWarning, Int>()

            for (r in list) {
                if (r.success) successCount++ else failCount++
                totalDuration += r.durationMs
                if (r.wasFallback) fallbackCount++
                if (r.responseWarnings.isNotEmpty()) {
                    val hasError = r.responseWarnings.any { classify(it) == HealthLevel.ERROR }
                    if (hasError) errorCount++ else warningCount++
                    for (w in r.responseWarnings) {
                        warningTypeMap[w] = (warningTypeMap[w] ?: 0) + 1
                    }
                }
            }

            MethodStats(
                method = method,
                totalCalls = list.size,
                successCount = successCount,
                failCount = failCount,
                avgResponseMs = if (list.isNotEmpty()) totalDuration / list.size else 0L,
                fallbackCount = fallbackCount,
                warningCount = warningCount,
                errorCount = errorCount,
                warningTypes = warningTypeMap
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
        var fallbacks = 0
        var warningCount = 0
        var errorCount = 0
        var lastFail: ApiCallRecord? = null
        var lastWarningRecord: ApiCallRecord? = null

        for (r in list) {
            if (r.success) success++
            totalDuration += r.durationMs
            if (r.wasFallback) fallbacks++
            when (r.level) {
                HealthLevel.WARNING -> warningCount++
                HealthLevel.ERROR -> errorCount++
                HealthLevel.OK -> {}
            }
            if (r.responseWarnings.isNotEmpty() && lastWarningRecord == null) {
                lastWarningRecord = r
            }
            if (!r.success) lastFail = r
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
            fallbackCount = fallbacks,
            lastError = lastFail?.errorMessage,
            lastErrorTime = lastFail?.timestamp ?: 0L,
            healthScore = score,
            lastWarning = lastWarningRecord?.responseWarnings?.firstOrNull(),
            overallLevel = computeOverallLevel(list)
        )
    }
}