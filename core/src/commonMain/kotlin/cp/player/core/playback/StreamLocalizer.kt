package cp.player.core.playback

/**
 * 落盘时一并记下的曲目信息，**仅供管理页展示**（不参与播放判定）。
 *
 * 为什么必须由调用方传进来：缓存文件名是 `sanitize(cacheKey)-<hash>`，哈希不可逆，
 * 光凭文件系统**恢复不出歌名**。而 `localize()` 的调用点（[cp.player.core.playback.PlaybackControllerImpl]）
 * 手里正好握着 `TrackSummary`，顺手记一笔是这里唯一低成本的机会。
 *
 * 旧版本落下的文件没有这份信息 —— 管理页对它们显示「未知曲目」，
 * 删除与清理能力不受影响（删除按文件名走，不依赖元信息）。
 */
data class SongCacheMeta(
    val title: String? = null,
    val artist: String? = null,
)

/**
 * 一条已落盘的歌曲缓存。
 *
 * @property id 文件名。**删除与清理的唯一凭据** —— 它永远可得（来自文件系统），
 *   而 [mediaId] / [title] 等在旧缓存上可能缺失。
 * @property mediaId 音源媒体标识；索引缺失时为 null
 * @property qualityLevel 音质档位（lossless / hires …）；索引缺失时为 null
 * @property bytes 文件字节数
 * @property lastAccessMs 最后访问时间（epoch ms），LRU 淘汰与「清理 N 天未访问」都用它
 */
data class SongCacheEntry(
    val id: String,
    val mediaId: String? = null,
    val qualityLevel: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val bytes: Long = 0L,
    val lastAccessMs: Long = 0L,
) {
    /**
     * 管理页的搜索匹配：曲名 / 歌手 / mediaId / 音质 / 文件名，任一命中即可。
     *
     * 关键字为空视为「全部命中」，调用方无需在外层再判一次空串。
     * [keyword] 与候选值两侧都降为小写：歌名里大小写混排很常见，
     * 按原样比会让用户搜 `hello` 搜不到 `Hello`。
     */
    fun matches(keyword: String): Boolean {
        val k = keyword.trim().lowercase()
        if (k.isEmpty()) return true
        return listOfNotNull(title, artist, mediaId, qualityLevel, id)
            .any { it.lowercase().contains(k) }
    }
}

/**
 * 歌曲缓存的占用概览。
 *
 * @property capacityBytes 容量上限；**0 表示该平台不落盘**（安卓 / 测试替身），
 *   管理 UI 据此整块隐藏 —— 而不是显示一个永远是 0 的数字。
 */
data class SongCacheStats(
    val entries: Int = 0,
    val bytes: Long = 0L,
    val capacityBytes: Long = 0L,
) {
    /** 该平台是否真的在落盘。 */
    val supported: Boolean get() = capacityBytes > 0L

    /** 占用比例 0f..1f；不支持时为 0f（调用方无需再判空）。 */
    val usedRatio: Float
        get() = if (capacityBytes <= 0L) 0f
        else (bytes.toDouble() / capacityBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

/**
 * 把远程流**落地成本地文件**后再交给引擎播放。
 *
 * ### 为什么需要它（实测结论，别再重复调研）
 * 桌面引擎（rodio）对 **FLAC over HTTP** 的 seek 是**静默空操作**：`seekTo()` 返回成功、
 * 位置却不动，于是「拖进度条没反应」。用真实音源字节 + 真实引擎测出的矩阵：
 *
 * | 格式 | 传输 | seek |
 * |---|---|---|
 * | WAV / MP3 | Range HTTP | ✅ |
 * | **FLAC** | **Range HTTP** | **❌** |
 * | FLAC / WAV / MP3 | 本地文件 | ✅ |
 *
 * **只失败在 `FLAC × HTTP` 这一格**。已排除的变量：`Accept-Ranges`、`Content-Length`、
 * 206/`Content-Range` 正确性、`Content-Type`（上游把 FLAC 标成 `audio/mpeg`，引擎靠
 * magic bytes 嗅探所以播放正常）。引擎版本 0.11.3 / 0.11.4 表现一致。
 *
 * 所以无损档位必须最终播**本地文件**，音质才不变、seek 才恢复可用。
 * 安卓（ExoPlayer）没有这个问题，它的 actual 是 [NoOpStreamLocalizer]。
 *
 * ### 怎么用它：边播边落盘，拖动时才切换
 * ⚠️ **不要 `await` 完 [localize] 再开播** —— 那要等整曲下完才出声
 * （一首 4 分钟无损 ≈ 25–35 MB，慢网下要等十几秒）。调用方应：
 *
 * 1. 先用**流地址立刻开播**（0 等待）；
 * 2. 同时查 [cachedPath]，命中就直接播本地（秒开且立刻可拖）；
 * 3. 未命中则后台并行 [localize]，期间 `isLocalizing = true`（UI 据此禁用进度条并说明原因）；
 * 4. 落盘完成**不要立刻切**（换源会有咔哒声），而是**等用户第一次拖动时**再切到本地文件并 seek。
 *
 * 正常听歌零中断，拖动本就是预期的断点 —— 于是「秒开 + 保无损 + 可拖动」三者兼得。
 *
 * ### 契约
 * - [cachedPath] 返回**已完整**的本地副本路径，没有则 `null`（同步、不阻塞）；
 * - [localize] 返回**本地文件路径** ⇒ 该曲的本地副本就绪，之后可以切过去播；
 * - [localize] 返回 `null` ⇒ 本曲没有本地副本（非 HTTP、非无损档位、下载失败都走这里），
 *   引擎只能继续放流 —— 此时 seek 在桌面端**不可用**。
 *
 * **下载失败必须返回 null 而不是抛异常**：宁可退回「能播但不能拖」，
 * 也不能因为缓存问题让用户听不了歌。
 *
 * @param cacheKey 稳定键（`mediaId@音质`）。**不含 URL** —— CDN 的鉴权参数会过期换新，
 *   用它当键会让每次播放都重新下载一份。
 * @param headers 取流所需的请求头（主要是 `Cookie`）。
 */
interface StreamLocalizer {

    /** 是否值得为这个音质档位付「落盘」的代价。 */
    fun isLocalizing(qualityLevel: String): Boolean = qualityLevel in LOSSLESS_LEVELS

    /**
     * 同步查**已完整**的本地副本；没有则 `null`。
     *
     * 带默认实现返回 `null`：平台没实现缓存（安卓、测试替身）时语义就是「没有副本」，
     * 调用方无需区分「不支持」与「未命中」。
     *
     * ⚠️ 必须是**同步且廉价**的（一次目录查表），它在播放路径上被调用。
     */
    fun cachedPath(cacheKey: String): String? = null

    suspend fun localize(
        url: String,
        cacheKey: String,
        headers: Map<String, String>,
        /** 供管理页展示的曲目信息；null = 不记录（老调用点语义不变）。 */
        meta: SongCacheMeta? = null,
    ): String?

    // ======================== 管理面 ========================
    //
    // 这一组**全部带默认实现**：安卓（ExoPlayer 能定位 HTTP FLAC，从不落盘）与测试替身
    // 天然「没有缓存可管」，默认值即正确语义，不必逐个实现空方法。
    // 默认 `capacityBytes = 0` 是「不支持」的判据，管理 UI 据此整块隐藏。

    /** 缓存占用概览。默认全 0（= [SongCacheStats.supported] 为 false）。 */
    fun stats(): SongCacheStats = SongCacheStats()

    /**
     * 已落盘的缓存条目，按最后访问时间**从新到旧**。
     *
     * 默认空列表。实现方请把「读索引 + 列目录」合并成一次调用 ——
     * 它是管理页整页数据的来源，逐条查询会在条目多时产生 N 次目录扫描。
     */
    fun entries(): List<SongCacheEntry> = emptyList()

    /**
     * 按 [SongCacheEntry.id]（文件名）删除单条。
     *
     * @return 是否真的删掉了文件（文件已不存在也算 false，调用方据此刷新而非当作错误）
     */
    fun remove(id: String): Boolean = false

    /** 清空全部缓存，返回删除的条目数。 */
    fun clear(): Int = 0

    /**
     * 删除 [lastAccessMs] 早于 `now - olderThanMs` 的条目，返回删除的条目数。
     *
     * 用它而不是「全清」：无损单曲几十上百 MB，全清一次要重下；
     * 而 LRU 本来就会淘汰冷数据，这里只是把同一策略交到用户手里。
     */
    fun clearOlderThan(olderThanMs: Long): Int = 0

    /**
     * 调整容量上限（字节）。传 `<= 0` 表示恢复平台默认。
     *
     * 实现方**应当**在缩小上限后立刻按 LRU 淘汰到位，否则用户设完看不到变化。
     */
    fun setCapacityBytes(bytes: Long) {}

    /**
     * 缓存目录的绝对路径；null = 该平台没有磁盘缓存目录。
     *
     * 桌面端「打开目录」用它，用户能直接核对 / 备份那些文件。
     */
    fun cacheDirPath(): String? = null

    companion object {
        /**
         * 无损档位。
         *
         * 只有这些档位才会拿到 FLAC 流，也才需要「先落盘」。MP3/AAC 档位（`standard` /
         * `exhigh`）本来就能定位，为它们多等一次整曲下载是纯粹的损失。
         */
        val LOSSLESS_LEVELS = setOf("lossless", "hires", "jymaster", "sky")
    }
}

/** 不做任何事：安卓（ExoPlayer 能定位 HTTP FLAC）与测试用它。 */
object NoOpStreamLocalizer : StreamLocalizer {
    override fun isLocalizing(qualityLevel: String): Boolean = false
    override suspend fun localize(
        url: String,
        cacheKey: String,
        headers: Map<String, String>,
        meta: SongCacheMeta?,
    ): String? = null
    // 管理面全部走接口默认值：stats() 的 capacityBytes = 0 ⇒ 管理 UI 整块隐藏。
}

/** 平台默认实现：桌面走真实下载，安卓是空实现。 */
expect fun createStreamLocalizer(): StreamLocalizer
