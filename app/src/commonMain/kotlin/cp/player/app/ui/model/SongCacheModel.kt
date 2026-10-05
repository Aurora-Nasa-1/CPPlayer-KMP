package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.ui.util.UiEvents
import cp.player.core.playback.SongCacheEntry
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌曲缓存明细页状态。
 *
 * @param loading 首次统计尚未完成（避免「空缓存」和「还在读」显示成同一个样子）
 * @param supported 该平台是否真的落盘；false（安卓）时整页显示一行说明即可
 * @param entries 全部缓存条目，已按最后访问时间从新到旧
 * @param query 搜索关键字；空串 = 不过滤
 */
data class SongCacheUiState(
    val loading: Boolean = true,
    val supported: Boolean = true,
    val entries: List<SongCacheEntry> = emptyList(),
    val totalBytes: Long = 0L,
    val capacityBytes: Long = 0L,
    val query: String = "",
) {
    /**
     * 过滤后的条目。
     *
     * 匹配逻辑放在 core 的 [SongCacheEntry.matches] 而不是这里：
     * 「缓存条目怎么算命中关键字」是数据本身的语义，换个界面（未来的集成接口）
     * 也该是同一套规则。
     */
    val visibleEntries: List<SongCacheEntry>
        get() = if (query.isBlank()) entries else entries.filter { it.matches(query) }

    val isFiltering: Boolean get() = query.isNotBlank()

    /** 三种「空」必须分开表达，否则用户分不清是自己的搜索没命中还是缓存真的空。 */
    val isEmptyCache: Boolean get() = !loading && entries.isEmpty()
    val hasNoMatch: Boolean get() = !loading && entries.isNotEmpty() && visibleEntries.isEmpty()
}

/**
 * 歌曲缓存明细页 ScreenModel。
 *
 * 所有磁盘操作（统计 / 列目录 / 删除）都在 IO 线程；目录可能装着几十个上百 MB 的文件，
 * 主线程做 `listFiles()` 即使很快也会被文件系统缓存抖动拖住。
 */
class SongCacheModel : ScreenModel {

    private val _state = MutableStateFlow(SongCacheUiState())
    val state: StateFlow<SongCacheUiState> = _state.asStateFlow()

    private val cache get() = AppModel.backend.songCache

    init { refresh() }

    /** 搜索关键字变更。纯内存过滤，不必防抖。 */
    fun setQuery(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    /** 重新读取条目与占用。删除后与进入页面时都走它。 */
    fun refresh() {
        screenModelScope.launch(Dispatchers.IO) {
            val entries = runCatching { cache.entries() }.getOrDefault(emptyList())
            val stats = runCatching { cache.stats() }.getOrNull()
            _state.value = _state.value.copy(
                loading = false,
                supported = stats?.supported ?: true,
                entries = entries,
                // 占用以 stats 为准（它和列表来自同一次枚举口径）；取不到时退回列表求和。
                totalBytes = stats?.bytes ?: entries.sumOf { it.bytes },
                capacityBytes = stats?.capacityBytes ?: 0L,
            )
        }
    }

    /**
     * 删除单条缓存。
     *
     * 删除失败**不是**异常路径：正在播放的那个文件在 Windows 上是锁住的，
     * 删不掉恰恰说明它正被使用。这里如实告诉用户，而不是假装成功。
     *
     * @param strings 由调用方（组合上下文）传入而不是在协程里取：通知发在 IO 协程中，
     *   那儿读不到 CompositionLocal。传进来还顺带保证用的是**点击那一刻**的语言。
     */
    fun remove(entry: SongCacheEntry, strings: CpStrings) {
        screenModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { cache.remove(entry.id) }.getOrDefault(false)
            refresh()
            val name = entry.displayName(strings)
            withContext(Dispatchers.Main) {
                UiEvents.notify(
                    if (ok) {
                        strings.songCache.deleted(name, formatBytes(entry.bytes))
                    } else {
                        strings.songCache.deleteFailed
                    },
                )
            }
        }
    }
}

/**
 * 管理页显示的曲目名：老缓存没有索引记录时给一个明确的占位，而不是空白行。
 *
 * 收 [CpStrings]：占位词是界面文案，`unknownTrack` 必须跟着语言走。
 */
internal fun SongCacheEntry.displayName(strings: CpStrings): String =
    title?.takeIf { it.isNotBlank() } ?: strings.songCache.unknownTrack

/**
 * 歌曲缓存容量上限的可选档位（字节 → 展示文案）。
 *
 * 做成**离散档**而不是滑条：容量是个「够用就好」的粗粒度决定（无损单曲几十~上百 MB），
 * 滑条会让人以为需要精确到 MB，还得额外解释「一首歌大概多大」。
 * 四档覆盖从「偶尔缓存几首」到「常听无损」。
 *
 * 放在 model 层而不是设置页文件里：它是**数据**（档位表），且需要能被单测直接验
 * （设置页里的 private 顶层函数测不到）。
 */
internal val SONG_CACHE_CAPACITY_OPTIONS: List<Pair<Long, String>> = listOf(
    512L * 1024L * 1024L to "512 MB",
    1L * 1024L * 1024L * 1024L to "1 GB",
    2L * 1024L * 1024L * 1024L to "2 GB",
    4L * 1024L * 1024L * 1024L to "4 GB",
)

/**
 * 当前上限落在哪一档。
 *
 * 精确匹配优先；匹配不上时（老版本写下的自定义值）退到**不超过它的最大档**，
 * 而不是兜底到第一档 —— 后者会让界面谎报一个比实际更小的上限。
 */
internal fun songCacheCapacityIndex(capacityBytes: Long): Int {
    val exact = SONG_CACHE_CAPACITY_OPTIONS.indexOfFirst { it.first == capacityBytes }
    if (exact >= 0) return exact
    return SONG_CACHE_CAPACITY_OPTIONS.indexOfLast { it.first <= capacityBytes }.coerceAtLeast(0)
}

/**
 * 音质档位的中文名。
 *
 * 与 `AppModel.qualityLevels` 保持同一套用词，但**不复用它**：那份列表只覆盖
 * 界面可选的 4 档，而缓存里可能出现 `jymaster` / `sky` 这类由音源或旧版本写下的档位，
 * 复用会让它们显示成空白。词表本体在 `QualityStrings`（两种语言共用一份判据）。
 */
internal fun qualityLabel(level: String, strings: CpStrings): String =
    strings.quality.labelOf(level)

/**
 * 相对时间：`刚刚` / `N 分钟前` / `N 小时前` / `N 天前` / `N 个月前`。
 *
 * 刻意不用 `kotlinx.datetime` —— 桌面运行时的它是 0.7.x 而编译期是 0.6.x，
 * 一碰就 `NoClassDefFoundError`，包在 `runCatching` 里还会静默退化成空串
 * （专辑发行年份整整丢过一栏，见 AGENTS.md）。这里只需要「现在」一个时间点，
 * 用 `currentTimeMillis()` 就够了。
 *
 * 五个分支的措辞全在文案层（`SongCacheStrings.relative*`），这里只做单位换算 ——
 * 中英的语序不同（「5 分钟前」vs「5 min ago」），拼在调用方就没法翻译了。
 */
internal fun relativeTime(ms: Long, strings: CpStrings, now: Long = currentTimeMillis()): String {
    if (ms <= 0L) return strings.songCache.timeUnknown
    val minutes = ((now - ms).coerceAtLeast(0L)) / 60_000L
    val s = strings.songCache
    return when {
        minutes < 1L -> s.timeJustNow
        minutes < 60L -> s.timeMinutesAgo(minutes)
        minutes < 60L * 24L -> s.timeHoursAgo(minutes / 60L)
        minutes < 60L * 24L * 30L -> s.timeDaysAgo(minutes / (60L * 24L))
        else -> s.timeMonthsAgo(minutes / (60L * 24L * 30L))
    }
}
