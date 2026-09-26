package cp.player.core.playback

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

    suspend fun localize(url: String, cacheKey: String, headers: Map<String, String>): String?

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
    override suspend fun localize(url: String, cacheKey: String, headers: Map<String, String>): String? = null
}

/** 平台默认实现：桌面走真实下载，安卓是空实现。 */
expect fun createStreamLocalizer(): StreamLocalizer
