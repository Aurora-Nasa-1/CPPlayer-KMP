package cp.player.kmp.api

import kotlinx.serialization.json.JsonElement

/**
 * 统一的音乐 API 服务（KMP 版）。
 *
 * **这是应用内所有音乐 API 调用的唯一入口。**
 *
 * ### 设计原则
 * 1. 所有 API 调用都通过此接口进行，禁止直接调用 [cp.player.kmp.provider.ProviderManager]
 * 2. 方法签名返回 [JsonElement]，由调用方（Repository 层）解析为领域模型
 * 3. 内部自动注入 cookie，调用方无需手动传递
 * 4. 错误统一返回 `{"code": 500, "msg": "..."}` 格式的 JSON
 * 5. 所有方法均为 [suspend fun]，内部自动在 IO 线程执行网络调用
 *
 * ### 调用链路
 * ```
 * ViewModel → Repository → MusicApiService → ProviderManager → BackendProvider
 * ```
 *
 * @see MusicApiMethod 所有 API 方法名常量
 */
interface MusicApiService {

    // ======================== 通用 ========================

    /**
     * 通用 API 调用。当需要调用 [MusicApiMethod] 中未覆盖的新增接口时使用。
     *
     * @param method API 方法名（应来自 [MusicApiMethod] 或与后端约定的路径）
     * @param params 请求参数
     * @param cookie 认证 cookie，null 时使用默认 cookie
     * @return JSON 响应
     */
    suspend fun callApi(
        method: String,
        params: Map<String, String> = emptyMap(),
        cookie: String? = null
    ): JsonElement

    // ======================== 认证 Auth ========================

    /** 获取扫码登录的二维码 key */
    suspend fun getQrKey(): JsonElement

    /** 创建二维码图片 */
    suspend fun createQrCode(key: String): JsonElement

    /** 检查扫码登录状态 */
    suspend fun checkQrStatus(key: String): JsonElement

    /** 邮箱登录 */
    suspend fun login(email: String, password: String, md5: Boolean = false): JsonElement

    /** 手机号登录 */
    suspend fun loginWithPhone(
        phone: String,
        password: String,
        captcha: Boolean = false,
        md5: Boolean = false
    ): JsonElement

    /** 发送验证码 */
    suspend fun sendCaptcha(phone: String): JsonElement

    /** 登出 */
    suspend fun logout(): JsonElement

    /** 游客登录 */
    suspend fun loginAnonymous(): JsonElement

    /** 获取当前登录状态 */
    suspend fun getLoginStatus(cookie: String? = null): JsonElement

    // ======================== 用户 User ========================

    /** 获取用户歌单列表（包含创建的和收藏的） */
    suspend fun getUserPlaylists(uid: Long): JsonElement

    /** 获取用户创建的歌单列表 */
    suspend fun getUserCreatedPlaylists(uid: Long): JsonElement

    /** 获取用户收藏的歌单列表 */
    suspend fun getUserCollectedPlaylists(uid: Long): JsonElement

    /** 获取用户详情 */
    suspend fun getUserDetail(uid: Long): JsonElement

    /** 获取用户云盘歌曲 */
    suspend fun getUserCloud(limit: Int = 200, offset: Int = 0): JsonElement

    /** 获取喜欢的音乐 ID 列表 */
    suspend fun getLikeList(uid: Long): JsonElement

    /** 喜欢/取消喜欢歌曲 */
    suspend fun likeSong(id: String, like: Boolean): JsonElement

    /** 获取每日推荐歌曲 */
    suspend fun getRecommendedSongs(): JsonElement

    /** 获取推荐歌单 */
    suspend fun getRecommendedPlaylists(): JsonElement

    /** 不喜欢推荐歌曲 */
    suspend fun dislikeSong(id: String): JsonElement

    // ======================== 歌单 Playlist ========================

    /** 获取歌单详情 */
    suspend fun getPlaylistDetail(id: Long): JsonElement

    /** 获取歌单全部歌曲 */
    suspend fun getPlaylistTracks(id: Long, limit: Int = 1000, offset: Int = 0): JsonElement

    /** 添加歌曲到歌单 */
    suspend fun addTracksToPlaylist(pid: Long, trackIds: List<String>): JsonElement

    /** 从歌单删除歌曲 */
    suspend fun removeTracksFromPlaylist(pid: Long, trackIds: List<String>): JsonElement

    /** 创建歌单 */
    suspend fun createPlaylist(name: String, privacy: Int = 0): JsonElement

    /** 删除歌单 */
    suspend fun deletePlaylist(id: Long): JsonElement

    /**
     * 收藏/取消收藏歌单。
     * @param t 1=收藏, 2=取消收藏
     */
    suspend fun subscribePlaylist(id: Long, t: Int): JsonElement

    // ======================== 专辑 Album ========================

    /** 获取专辑详情（含歌曲列表） */
    suspend fun getAlbumDetail(id: Long): JsonElement

    // ======================== 歌手 Artist ========================

    /** 获取歌手详情 */
    suspend fun getArtistDetail(id: Long): JsonElement

    /** 获取歌手歌曲列表 */
    suspend fun getArtistSongs(id: Long, limit: Int = 50): JsonElement

    /** 获取歌手专辑列表 */
    suspend fun getArtistAlbums(id: Long, limit: Int = 20): JsonElement

    // ======================== 搜索 Search ========================

    /**
     * 云搜索
     * @param type 1=单曲, 1000=歌单, 100=歌手, 10=专辑, 1014=视频
     */
    suspend fun search(keywords: String, type: Int = 1): JsonElement

    /** 获取热搜详情 */
    suspend fun getHotSearches(): JsonElement

    /** 获取搜索建议 */
    suspend fun getSearchSuggestions(keywords: String): JsonElement

    // ======================== 播放 Playback ========================

    /**
     * 获取歌曲播放 URL（302 重定向版本，优先使用）
     *
     * @param songId 歌曲 ID
     * @param level 音质等级: standard / higher / exhigh / lossless / hires / jyeffect / jymaster / sky / immersive / dolby
     */
    suspend fun getSongUrl(songId: String, level: String = "standard"): JsonElement

    /**
     * 获取歌曲播放 URL（直接返回版本，302 失败时降级使用）
     */
    suspend fun getSongUrlFallback(songId: String, level: String = "standard"): JsonElement

    /**
     * 获取歌曲下载 URL
     */
    suspend fun getSongDownloadUrl(songId: String, level: String = "standard"): JsonElement

    /** 获取歌曲详情（可批量） */
    suspend fun getSongDetail(ids: List<String>): JsonElement

    /** 获取私人 FM 歌曲 */
    suspend fun getPersonalFm(): JsonElement

    /** 获取心动模式/智能播放列表 */
    suspend fun getIntelligenceList(songId: String, playlistId: Long): JsonElement

    /** 获取歌词 */
    suspend fun getLyric(songId: String): JsonElement

    /**
     * 听歌打卡（上报播放进度，影响推荐算法和听歌排行）。
     *
     * @param songId 歌曲 ID
     * @param sourceId 来源歌单/专辑 ID
     * @param playedSeconds 已播放时长（秒）
     */
    suspend fun scrobble(songId: String, sourceId: String, playedSeconds: Int): JsonElement

    // ======================== 社交 Social ========================

    /**
     * 根据类型获取对应的评论 API 方法名
     */
    fun getCommentMethod(type: String): String {
        return MusicApiMethod.COMMENT_NEW
    }

    /**
     * 获取评论列表
     * @param id 资源 ID
     * @param type 资源类型: music / playlist / album / mv / dj / video
     * @param limit 每页数量
     * @param offset 偏移量
     */
    suspend fun getComments(
        id: String,
        type: String = "music",
        limit: Int = 20,
        offset: Int = 0,
        sortType: Int = 1
    ): JsonElement

    /**
     * 获取楼层评论
     * @param id 资源 ID
     * @param parentCommentId 父评论 ID
     * @param type 资源类型
     * @param limit 每页数量
     * @param time 翻页游标
     */
    suspend fun getFloorComments(
        id: String,
        parentCommentId: Long,
        type: String = "music",
        limit: Int = 20,
        time: Long = 0
    ): JsonElement

    /**
     * 点赞评论
     * @param id 资源 ID
     * @param cid 评论 ID
     * @param type 资源类型
     * @param liked true=点赞, false=取消点赞
     */
    suspend fun likeComment(id: String, cid: Long, type: String, liked: Boolean): JsonElement

    /**
     * 发表/回复评论
     * @param id 资源 ID
     * @param type 资源类型
     * @param content 评论内容
     * @param replyId 回复的评论 ID，null 表示发表新评论
     */
    suspend fun postComment(
        id: String,
        type: String,
        content: String,
        replyId: Long? = null
    ): JsonElement

    /** 获取未读消息数 */
    suspend fun getUnreadCount(): JsonElement

    /** 获取最近联系人 */
    suspend fun getRecentContacts(): JsonElement

    /** 获取私信列表 */
    suspend fun getPrivateMessages(): JsonElement

    /** 获取与某人的私信历史 */
    suspend fun getMessageHistory(uid: Long): JsonElement

    /** 标记私信已读 */
    suspend fun markMessageAsRead(uid: Long): JsonElement

    /** 发送文本消息 */
    suspend fun sendMessage(uid: Long, text: String): JsonElement

    // ======================== 排行榜 Ranking ========================

    /** 获取所有榜单列表 */
    suspend fun getToplist(): JsonElement

    /** 获取所有榜单内容摘要 */
    suspend fun getToplistDetail(): JsonElement

    /** 新歌速递 @param type 地区: 0=全部, 7=华语, 96=欧美, 8=日本, 16=韩国 */
    suspend fun getTopSongs(type: Int = 0): JsonElement

    /** 新碟上架 @param area ALL/ZH/EA/KR/JP */
    suspend fun getTopAlbums(area: String = "ALL", limit: Int = 30): JsonElement

    /** 热门歌手 */
    suspend fun getTopArtists(limit: Int = 30): JsonElement

    /** 热门歌单 @param order new/hot @param cat 分类标签 */
    suspend fun getTopPlaylists(order: String = "hot", cat: String = "全部", limit: Int = 30): JsonElement

    /** 精品歌单 */
    suspend fun getHighqualityPlaylists(cat: String = "全部", limit: Int = 30): JsonElement

    // ======================== 推荐 Discovery ========================

    /** 推荐歌单（无需登录） */
    suspend fun getPersonalizedPlaylists(limit: Int = 30): JsonElement

    /** 推荐新音乐 */
    suspend fun getPersonalizedNewSongs(limit: Int = 10): JsonElement

    /** 首页 Banner */
    suspend fun getBanner(): JsonElement

    /** 历史日推可用日期列表 */
    suspend fun getHistoryRecommendSongs(): JsonElement

    /** 历史日推详情 @param date 日期字符串 */
    suspend fun getHistoryRecommendSongsDetail(date: String): JsonElement

    // ======================== 相似 Similar ========================

    /** 获取相似歌曲 */
    suspend fun getSimilarSongs(songId: String): JsonElement

    /** 获取相似歌手 */
    suspend fun getSimilarArtists(artistId: Long): JsonElement

    /** 获取相似歌单 */
    suspend fun getSimilarPlaylists(songId: String): JsonElement

    // ======================== 签到 Signin ========================

    /** 每日签到 */
    suspend fun dailySignin(): JsonElement

    // ======================== MV ========================

    /** 获取 MV 详情 */
    suspend fun getMvDetail(mvId: String): JsonElement

    /** 获取 MV 播放地址 @param resolution 分辨率: 240/480/720/1080 */
    suspend fun getMvUrl(mvId: String, resolution: Int = 1080): JsonElement

    /** 获取全部 MV */
    suspend fun getAllMv(area: String = "全部", limit: Int = 30, offset: Int = 0): JsonElement

    /** 获取最新 MV */
    suspend fun getFirstMv(limit: Int = 20): JsonElement

    /** 收藏/取消收藏 MV @param t 1=收藏, 2=取消收藏 */
    suspend fun subscribeMv(mvId: String, t: Int): JsonElement

    /** 获取已收藏 MV 列表 */
    suspend fun getMvSublist(limit: Int = 25, offset: Int = 0): JsonElement

    // ======================== 视频 Video ========================

    /** 获取视频详情 */
    suspend fun getVideoDetail(videoId: String): JsonElement

    /** 获取视频播放地址 @param resolution 分辨率: 240/480/720/1080 */
    suspend fun getVideoUrl(videoId: String, resolution: Int = 1080): JsonElement

    /** 获取视频分组列表 */
    suspend fun getVideoGroup(): JsonElement

    /** 获取视频时间线 */
    suspend fun getVideoTimelineAll(offset: Int = 0): JsonElement

    // ======================== 电台 DJ ========================

    /** 获取电台详情 */
    suspend fun getDjDetail(djId: String): JsonElement

    /** 获取电台节目列表 */
    suspend fun getDjProgram(djId: String, limit: Int = 30, offset: Int = 0, asc: Boolean = false): JsonElement

    /** 获取热门电台 */
    suspend fun getDjHot(limit: Int = 30, offset: Int = 0): JsonElement

    /** 获取电台排行榜 */
    suspend fun getDjToplist(limit: Int = 30, offset: Int = 0): JsonElement

    /** 获取推荐电台 */
    suspend fun getDjRecommend(): JsonElement

    /** 收藏/取消收藏电台 @param t 1=收藏, 0=取消收藏 */
    suspend fun subscribeDj(djId: String, t: Int): JsonElement

    /** 获取已收藏电台列表 */
    suspend fun getDjSublist(): JsonElement

    /** 获取推荐节目 */
    suspend fun getProgramRecommend(limit: Int = 30, offset: Int = 0): JsonElement

    // ======================== 专辑扩展 Album Ext ========================

    /** 获取数字专辑列表 */
    suspend fun getAlbumList(limit: Int = 30, offset: Int = 0): JsonElement

    /** 获取最新专辑 */
    suspend fun getAlbumNew(): JsonElement

    /** 获取最新专辑（分页） */
    suspend fun getAlbumNewest(limit: Int = 30, offset: Int = 0): JsonElement

    /** 收藏/取消收藏专辑 @param t 1=收藏, 2=取消收藏 */
    suspend fun subscribeAlbum(id: Long, t: Int): JsonElement

    /** 获取已收藏专辑列表 */
    suspend fun getAlbumSublist(limit: Int = 25, offset: Int = 0): JsonElement

    // ======================== 歌手扩展 Artist Ext ========================

    /** 获取歌手热门 50 首 */
    suspend fun getArtistTopSong(id: Long): JsonElement

    /** 收藏/取消收藏歌手 @param t 1=收藏, 2=取消收藏 */
    suspend fun subscribeArtist(id: Long, t: Int): JsonElement

    /** 获取已收藏歌手列表 */
    suspend fun getArtistSublist(): JsonElement

    /** 获取歌手 MV 列表 */
    suspend fun getArtistMv(id: Long, limit: Int = 30, offset: Int = 0): JsonElement

    /** 获取歌手分类列表 @param type 分类: -1=全部, 1=男歌手, 2=女歌手, 3=乐队 @param area 地区: -100=全部, 7=华语, 96=欧美, 8=日本, 16=韩国 @param initial 首字母 */
    suspend fun getArtistList(type: Int = -1, area: Int = -100, initial: String = "", limit: Int = 30, offset: Int = 0): JsonElement

    /** 获取歌手粉丝数量 */
    suspend fun getArtistFollowCount(id: Long): JsonElement

    // ======================== 用户扩展 User Ext ========================

    /**
     * 获取用户听歌排行
     * @param uid 用户 ID
     * @param type 0=所有时间, 1=最近一周
     */
    suspend fun getUserRecord(uid: Long, type: Int = 0): JsonElement

    /** 获取用户关注列表 */
    suspend fun getUserFollows(uid: Long, limit: Int = 30, offset: Int = 0): JsonElement

    /** 获取用户粉丝列表 */
    suspend fun getUserFolloweds(uid: Long, limit: Int = 30, offset: Int = 0): JsonElement

    /** 获取用户动态 */
    suspend fun getUserEvent(uid: Long, limit: Int = 30): JsonElement

    /**
     * 更新用户信息
     * @param params 可包含: nickname, signature, gender, birthday, province, city
     */
    suspend fun updateUser(params: Map<String, String>): JsonElement

    /** 获取用户账号信息 */
    suspend fun getUserAccount(): JsonElement

    /** 获取用户电台 */
    suspend fun getUserDj(uid: Long): JsonElement

    // ======================== 歌单扩展 Playlist Ext ========================

    /** 获取歌单分类列表 */
    suspend fun getPlaylistCatlist(): JsonElement

    /** 获取热门歌单标签 */
    suspend fun getPlaylistHot(): JsonElement

    /** 更新歌单信息（名称、标签、描述等） */
    suspend fun updatePlaylist(id: Long, name: String? = null, desc: String? = null, tags: List<String>? = null): JsonElement

    /** 获取歌单收藏者列表 */
    suspend fun getPlaylistSubscribers(id: Long, limit: Int = 20, offset: Int = 0): JsonElement

    /** 获取精品歌单标签 */
    suspend fun getPlaylistHighqualityTags(): JsonElement

    /** 更新歌单描述 */
    suspend fun updatePlaylistDesc(id: Long, desc: String): JsonElement

    /** 更新歌单名称 */
    suspend fun updatePlaylistName(id: Long, name: String): JsonElement

    // ======================== 云盘扩展 Cloud Ext ========================

    /** 删除云盘歌曲 */
    suspend fun deleteUserCloud(songIds: List<String>): JsonElement

    /** 导入云盘歌曲（匹配已有歌曲） */
    suspend fun cloudImport(songId: String, matchSongId: String): JsonElement

    /** 云盘歌曲匹配 */
    suspend fun cloudMatch(uid: Long, songId: String, adjustSongId: String): JsonElement

    // ======================== 其他 Other ========================

    /** 检查歌曲是否可用 */
    suspend fun checkMusic(id: String, br: Int = 999000): JsonElement

    /** 批量请求 @param apiRequests "{\"api1\": json_body, ...}" */
    suspend fun batch(apiRequests: String): JsonElement

    /** 获取音乐日历 */
    suspend fun getCalendar(): JsonElement

    /** 获取动态 */
    suspend fun getEvent(limit: Int = 30): JsonElement

    /** 删除动态 */
    suspend fun deleteEvent(evId: Long): JsonElement

    /** 转发动态 */
    suspend fun forwardEvent(evId: Long, uid: Long, forwards: String = ""): JsonElement

    /** 获取最近播放歌曲 */
    suspend fun getRecentSongs(limit: Int = 30): JsonElement

    /** 分享资源 */
    suspend fun shareResource(id: String, type: String, msg: String = ""): JsonElement
}