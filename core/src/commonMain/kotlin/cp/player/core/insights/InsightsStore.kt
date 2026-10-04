package cp.player.core.insights

import cp.player.core.util.PlatformSupport
import cp.player.core.util.localDateTimeOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 听歌记录的本地持久化。
 *
 * ### 存储形态：按月分片的追加型 JSONL
 *
 * ```
 * <dataDir>/insights/plays-2026-10.jsonl   ← 每行一条 PlayRecord
 * <dataDir>/insights/plays-2026-09.jsonl
 * ```
 *
 * **为什么是追加而不是「一份 JSON 数组全量重写」**：一首歌一条记录，重度用户一年
 * 上万条。全量重写的写法每写一条要重写整份文件，N 条就是 O(N²) 的写入量 ——
 * 这不是风格问题，是把闪存写坏的量级。分月还能让「只读最近几个月」成为一次小 IO。
 *
 * **为什么是 JSONL 而不是二进制**：出问题时用户能直接打开看、能手工删掉某一天的坏行；
 * 而且写一半就崩（进程被杀）只会损坏**最后一行**，前面全部完好 ——
 * 每条记录独立成行，正是为了这种「部分损坏可恢复」。
 *
 * ### 坏行策略
 * 单行解析失败**跳过但保留原文件**（不静默重写），并把失败计数交给调用方。
 * 静默「修复」会把用户的历史吃掉 —— 宁可少算一条，也不要悄悄删。
 *
 * ⚠️ 本类只做 IO，不做聚合与判定（那些在 [Insights]，纯函数、可单测）。
 */
class InsightsStore(private val directory: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 读取结果：记录 + 被跳过的坏行数（给诊断页/日志用，不弹给用户）。 */
    data class LoadResult(
        val records: List<PlayRecord>,
        val skippedLines: Int,
    )

    /** 分片文件名：按 `startedAt` 所在**本地月份**切分。 */
    private fun fileFor(epochMillis: Long): String {
        val p = localDateTimeOf(epochMillis)
        val month = if (p.month < 10) "0${p.month}" else p.month.toString()
        return "$directory/plays-${p.year}-$month.jsonl"
    }

    /**
     * 取路径的文件名部分，**同时兼容 `/` 与 `\`**。
     *
     * ⚠️ 不能用 `substringAfterLast('/')`：Windows 上 `listChildFiles` 返回的是
     * `C:\...\plays-2026-10.jsonl`，正斜杠一次都匹配不到 ⇒ 得到整条绝对路径 ⇒
     * `startsWith(FILE_PREFIX)` 恒为 false ⇒ **所有分片被静默过滤掉，历史记录读出来为空**。
     * 桌面端的主目标就是 Windows，这个坑一漏就是「习惯页永远是空的」。
     */
    private fun fileNameOf(path: String): String {
        val idx = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (idx >= 0) path.substring(idx + 1) else path
    }

    private fun monthFiles(): List<String> =
        PlatformSupport.listChildFiles(directory).filter { fileNameOf(it).startsWith(FILE_PREFIX) }

    /**
     * 读出全部历史（按 `startedAt` 升序）。
     *
     * 单行解析失败即跳过 —— 见类说明的「坏行策略」。
     */
    fun loadAll(): LoadResult {
        val files = monthFiles()
        if (files.isEmpty()) return LoadResult(emptyList(), 0)

        val records = ArrayList<PlayRecord>(256)
        var skipped = 0
        for (path in files) {
            val text = PlatformSupport.readTextFile(path) ?: continue
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val record = runCatching { json.decodeFromString<PlayRecord>(trimmed) }.getOrNull()
                if (record == null) {
                    skipped++
                } else {
                    records.add(record)
                }
            }
        }
        // 跨月的分片按文件名排序读出来是「近似有序」的，但月内与跨月都可能乱序
        // （用户改系统时间、同步进来的记录）。这里统一排序，让上层不必再关心顺序。
        records.sortBy { it.startedAt }
        return LoadResult(records, skipped)
    }

    /**
     * 追加一条记录。
     *
     * @return 是否写入成功。失败**不抛**：调用方在播放收尾路径上，写盘失败不该
     *   把播放流程带崩 —— 但要如实返回 false，由调用方决定是否记日志。
     */
    fun append(record: PlayRecord): Boolean {
        val line = runCatching { json.encodeToString(record) }.getOrNull() ?: return false
        return PlatformSupport.appendTextFile(fileFor(record.startedAt), line)
    }

    /**
     * 清空全部记录。
     *
     * ⚠️ **不可逆**。UI 侧必须先经二次确认（`CpConfirmHost`），本方法不做确认 ——
     * 它是纯 IO，判断「用户是不是真的要删」不属于这一层。
     */
    fun clear(): Boolean {
        val files = monthFiles()
        var ok = true
        for (path in files) {
            if (!PlatformSupport.deleteRecursively(path)) ok = false
        }
        return ok
    }

    companion object {
        const val FILE_PREFIX = "plays-"

        /**
         * 默认存放目录。
         *
         * 与 `modules/`、`downloads/` 同级放在应用数据目录下 —— 备份/清理逻辑
         * （设置页的「存储管理」）按目录维度遍历时才能一并看到它。
         */
        fun directoryUnder(dataDir: String): String = "$dataDir/insights"
    }
}
