package cp.player.core.playback

/**
 * 播放元信息（与播放引擎解耦的最小集合）。
 *
 * 原与 `PlaybackEngine` / `EngineType` / `PlaybackState` 同放在 `PlaybackEngine.kt`；
 * 2026-09-25 那三者作为死代码移除后，本类单独成文件 —— 它是活的，
 * 由 [PlatformPlayer.load] 与 [PlaybackControllerImpl] 实际使用。
 */
data class PlaybackMetadata(
    val id: String = "",
    val title: String,
    val artist: String?,
    val album: String?,
    val coverUrl: String?,
    val durationMs: Long,
    /**
     * 磁盘流缓存的**稳定键**，形如 `<mediaId>@<quality>`。
     *
     * 音乐 CDN 的播放地址带一次性鉴权参数，同一次会话里刷新 token 就会换一条 URL。
     * 若缓存直接以 URL 为键，每次换 token 都会新建一份缓存条目——既浪费空间，
     * 又让「seek 回退到已下载区间」因为换了键而落空。用 mediaId + 音质当键则稳定：
     * 同一首歌同一音质的字节内容不变，换 token 也能命中；而不同音质是不同的文件，
     * 天然被区分开，不会串音。
     *
     * 为 null 时调用方回退为用 URL 当键（本地文件等场景无此问题）。
     */
    val cacheKey: String? = null,
)
