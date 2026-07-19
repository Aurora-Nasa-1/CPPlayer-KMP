package cp.player.kmp.music

/**
 * 前端统一调用的音乐源接口，整合了多 Provider 和本地音乐。
 * 所有涉及到资源定位的入参全部采用带有 Provider 命名空间的 [CPMediaId] (String)。
 */
interface UnifiedMusicSource {

    /**
     * 获取歌曲详情
     * @param mediaId 例如 "netease://song/12345" 或 "local://song/path/to/file"
     */
    suspend fun getTrackDetail(mediaId: String): MusicResult<TrackSummary>

    /**
     * 获取歌曲播放地址
     */
    suspend fun getSongUrl(mediaId: String, level: String = "standard"): MusicResult<SongUrl>

    /**
     * 搜索 (支持跨多个 provider 搜索并聚合)
     * @param keywords 关键词
     * @param providers 指定要在哪些 providerId 进行搜索，为 null 时搜索所有已加载的
     */
    suspend fun search(keywords: String, type: Int = 1, providers: List<String>? = null): MusicResult<SearchResult>

    /**
     * 获取用户歌单
     * @param providerId 指定从哪个 provider 提取
     * @param uid 用户ID
     */
    suspend fun getUserPlaylists(providerId: String, uid: Long): MusicResult<List<PlaylistSummary>>
}
