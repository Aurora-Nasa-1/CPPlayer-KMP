package cp.player.kmp.api

/**
 * 统一的音乐 API 方法名常量定义（KMP 版）。
 *
 * 所有对后端 Provider 的 API 调用都应通过 [MusicApiService] 进行，
 * 方法名从此常量对象获取，禁止使用裸字符串。
 *
 * ### 命名约定
 * - 常量使用 `UPPER_SNAKE_CASE`
 * - 按业务域分组（Auth / User / Playlist / Artist / Search / Playback / Social）
 * - 值与后端 Provider 的 API 路径保持一致
 */
object MusicApiMethod {

    // ======================== 认证 Auth ========================

    /** 扫码登录 - 获取二维码 key */
    const val AUTH_QR_KEY = "login/qr/key"

    /** 扫码登录 - 创建二维码 */
    const val AUTH_QR_CREATE = "login/qr/create"

    /** 扫码登录 - 检查扫码状态 */
    const val AUTH_QR_CHECK = "login/qr/check"

    /** 邮箱登录 */
    const val AUTH_LOGIN = "login"

    /** 手机号登录 */
    const val AUTH_LOGIN_PHONE = "login/cellphone"

    /** 发送验证码 */
    const val AUTH_CAPTCHA_SENT = "captcha/sent"

    /** 登出 */
    const val AUTH_LOGOUT = "logout"

    /** 游客登录 */
    const val AUTH_ANONYMOUS = "register/anonimous"

    /** 获取登录状态 */
    const val AUTH_LOGIN_STATUS = "login/status"

    // ======================== 用户 User ========================

    /** 获取用户歌单列表（包含创建的和收藏的） */
    const val USER_PLAYLIST = "user/playlist"

    /** 获取用户创建的歌单列表 */
    const val USER_PLAYLIST_CREATE = "user/playlist/create"

    /** 获取用户收藏的歌单列表 */
    const val USER_PLAYLIST_COLLECT = "user/playlist/collect"

    /** 获取用户详情 */
    const val USER_DETAIL = "user/detail"

    /** 获取用户云盘歌曲 */
    const val USER_CLOUD = "user/cloud"

    /** 获取喜欢的音乐 ID 列表 */
    const val USER_LIKE_LIST = "likelist"

    /** 喜欢/取消喜欢歌曲 */
    const val USER_LIKE = "like"

    /** 获取每日推荐歌曲 */
    const val USER_RECOMMEND_SONGS = "recommend/songs"

    /** 获取推荐歌单 */
    const val USER_RECOMMEND_RESOURCE = "recommend/resource"

    /** 不喜欢推荐歌曲 */
    const val USER_DISLIKE_SONG = "recommend/songs/dislike"

    // ======================== 歌单 Playlist ========================

    /** 获取歌单详情 */
    const val PLAYLIST_DETAIL = "playlist/detail"

    /** 获取歌单全部歌曲 */
    const val PLAYLIST_TRACK_ALL = "playlist/track/all"

    /** 添加/删除歌单中的歌曲 */
    const val PLAYLIST_TRACKS = "playlist/tracks"

    /** 创建歌单 */
    const val PLAYLIST_CREATE = "playlist/create"

    /** 删除歌单 */
    const val PLAYLIST_DELETE = "playlist/delete"

    /** 收藏/取消收藏歌单 (t=1收藏, t=2取消收藏) */
    const val PLAYLIST_SUBSCRIBE = "playlist/subscribe"

    // ======================== 专辑 Album ========================

    /** 获取专辑详情（含歌曲列表） */
    const val ALBUM_DETAIL = "album"

    // ======================== 歌手 Artist ========================

    /** 获取歌手详情 */
    const val ARTIST_DETAIL = "artist/detail"

    /** 获取歌手歌曲列表 */
    const val ARTIST_SONGS = "artist/songs"

    /** 获取歌手专辑列表 */
    const val ARTIST_ALBUM = "artist/album"

    // ======================== 搜索 Search ========================

    /** 云搜索（统一搜索接口） */
    const val SEARCH_CLOUD = "cloudsearch"

    /** 热搜详情 */
    const val SEARCH_HOT_DETAIL = "search/hot/detail"

    /** 搜索建议 */
    const val SEARCH_SUGGEST = "search/suggest"

    /** 搜索类型：单曲 */
    const val SEARCH_TYPE_SONG = 1

    /** 搜索类型：专辑 */
    const val SEARCH_TYPE_ALBUM = 10

    /** 搜索类型：歌手 */
    const val SEARCH_TYPE_ARTIST = 100

    /** 搜索类型：歌单 */
    const val SEARCH_TYPE_PLAYLIST = 1000

    // ======================== 播放 Playback ========================

    /** 获取歌曲播放 URL (302 重定向) */
    const val SONG_URL_V1_302 = "song/url/v1/302"

    /** 获取歌曲播放 URL */
    const val SONG_URL_V1 = "song/url/v1"

    /** 获取歌曲下载 URL */
    const val SONG_DOWNLOAD_URL = "song/download/url/v1"

    /** 获取歌曲详情 */
    const val SONG_DETAIL = "song/detail"

    /** 私人 FM */
    const val PERSONAL_FM = "personal_fm"

    /** 心动模式/智能播放列表 */
    const val INTELLIGENCE_LIST = "playmode/intelligence/list"

    /** 获取歌词 */
    const val LYRIC_NEW = "lyric/new"

    /** 听歌打卡（上报播放进度，影响推荐算法） */
    const val SCROBBLE = "scrobble"

    // ======================== 社交 Social ========================

    /** 音乐评论 */
    const val COMMENT_MUSIC = "comment/music"

    /** 歌单评论 */
    const val COMMENT_PLAYLIST = "comment/playlist"

    /** 专辑评论 */
    const val COMMENT_ALBUM = "comment/album"

    /** MV 评论 */
    const val COMMENT_MV = "comment/mv"

    /** 电台评论 */
    const val COMMENT_DJ = "comment/dj"

    /** 视频评论 */
    const val COMMENT_VIDEO = "comment/video"

    /** 楼层评论 */
    const val COMMENT_FLOOR = "comment/floor"

    /** 点赞评论 */
    const val COMMENT_LIKE = "comment/like"

    /** 发表/回复评论 */
    const val COMMENT_POST = "comment"

    /** 新版评论 */
    const val COMMENT_NEW = "comment/new"
    /** 未读消息数 */
    const val MESSAGE_UNREAD_COUNT = "pl/count"

    /** 最近联系人 */
    const val MESSAGE_RECENT_CONTACT = "msg/recentcontact"

    /** 私信列表 */
    const val MESSAGE_PRIVATE = "msg/private"

    /** 私信历史记录 */
    const val MESSAGE_PRIVATE_HISTORY = "msg/private/history"

    /** 标记私信已读 */
    const val MESSAGE_MARK_READ = "msg/private/mark/read"

    /** 发送文本消息 */
    const val MESSAGE_SEND_TEXT = "send/text"

    // ======================== 排行榜 Ranking ========================

    /** 所有榜单列表 */
    const val TOPLIST = "toplist"

    /** 所有榜单内容摘要 */
    const val TOPLIST_DETAIL = "toplist_detail"

    /** 新歌速递 */
    const val TOP_SONG = "top_song"

    /** 新碟上架 */
    const val TOP_ALBUM = "top_album"

    /** 热门歌手 */
    const val TOP_ARTISTS = "top_artists"

    /** 热门歌单（网友精选碟） */
    const val TOP_PLAYLIST = "top_playlist"

    /** 精品歌单 */
    const val TOP_PLAYLIST_HIGHQUALITY = "top_playlist_highquality"

    // ======================== 推荐 Discovery ========================

    /** 推荐歌单（非每日推荐，无需登录） */
    const val PERSONALIZED = "personalized"

    /** 推荐新音乐 */
    const val PERSONALIZED_NEWSONG = "personalized_newsong"

    /** 首页 Banner 轮播图 */
    const val BANNER = "banner"

    /** 历史日推可用日期列表 */
    const val HISTORY_RECOMMEND_SONGS = "history_recommend_songs"

    /** 历史日推详情 */
    const val HISTORY_RECOMMEND_SONGS_DETAIL = "history_recommend_songs_detail"

    // ======================== 相似 Similar ========================

    /** 相似歌曲 */
    const val SIMI_SONG = "simi_song"

    /** 相似歌手 */
    const val SIMI_ARTIST = "simi_artist"

    /** 相似歌单 */
    const val SIMI_PLAYLIST = "simi_playlist"

    // ======================== MV ========================

    /** MV 详情 */
    const val MV_DETAIL = "mv/detail"

    /** MV 播放地址 */
    const val MV_URL = "mv/url"

    /** 全部 MV */
    const val MV_ALL = "mv/all"

    /** 最新 MV */
    const val MV_FIRST = "mv/first"

    /** 收藏/取消收藏 MV (t=1收藏, t=2取消收藏) */
    const val MV_SUB = "mv/sub"

    /** 已收藏 MV 列表 */
    const val MV_SUBLIST = "mv/sublist"

    // ======================== 视频 Video ========================

    /** 视频详情 */
    const val VIDEO_DETAIL = "video/detail"

    /** 视频播放地址 */
    const val VIDEO_URL = "video/url"

    /** 视频分组列表 */
    const val VIDEO_GROUP = "video/group"

    /** 视频时间线 */
    const val VIDEO_TIMELINE_ALL = "video/timeline/all"

    // ======================== 电台 DJ ========================

    /** 电台详情 */
    const val DJ_DETAIL = "dj/detail"

    /** 电台节目列表 */
    const val DJ_PROGRAM = "dj/program"

    /** 热门电台 */
    const val DJ_HOT = "dj/hot"

    /** 电台排行榜 */
    const val DJ_TOPLIST = "dj/toplist"

    /** 推荐电台 */
    const val DJ_RECOMMEND = "dj/recommend"

    /** 收藏/取消收藏电台 (t=1收藏, t=0取消收藏) */
    const val DJ_SUB = "dj/sub"

    /** 已收藏电台列表 */
    const val DJ_SUBLIST = "dj/sublist"

    /** 推荐节目 */
    const val PROGRAM_RECOMMEND = "program/recommend"

    // ======================== 专辑扩展 Album Ext ========================

    /** 数字专辑列表 */
    const val ALBUM_LIST = "album/list"

    /** 最新专辑 */
    const val ALBUM_NEW = "album/new"

    /** 最新专辑（分页） */
    const val ALBUM_NEWEST = "album/newest"

    /** 收藏/取消收藏专辑 (t=1收藏, t=2取消收藏) */
    const val ALBUM_SUB = "album/sub"

    /** 已收藏专辑列表 */
    const val ALBUM_SUBLIST = "album/sublist"

    // ======================== 歌手扩展 Artist Ext ========================

    /** 歌手热门 50 首 */
    const val ARTIST_TOP_SONG = "artist/top/song"

    /** 收藏/取消收藏歌手 (t=1收藏, t=2取消收藏) */
    const val ARTIST_SUB = "artist/sub"

    /** 已收藏歌手列表 */
    const val ARTIST_SUBLIST = "artist/sublist"

    /** 歌手 MV 列表 */
    const val ARTIST_MV = "artist/mv"

    /** 歌手分类列表 */
    const val ARTIST_LIST = "artist/list"

    /** 歌手粉丝数量 */
    const val ARTIST_FOLLOW_COUNT = "artist/follow/count"

    // ======================== 用户扩展 User Ext ========================

    /** 用户听歌排行 */
    const val USER_RECORD = "user/record"

    /** 用户关注列表 */
    const val USER_FOLLOWS = "user/follows"

    /** 用户粉丝列表 */
    const val USER_FOLLOWEDS = "user/followeds"

    /** 用户动态 */
    const val USER_EVENT = "user/event"

    /** 更新用户信息 */
    const val USER_UPDATE = "user/update"

    /** 用户账号信息 */
    const val USER_ACCOUNT = "user/account"

    /** 用户电台 */
    const val USER_DJ = "user/dj"

    // ======================== 歌单扩展 Playlist Ext ========================

    /** 歌单分类列表 */
    const val PLAYLIST_CATLIST = "playlist/catlist"

    /** 热门歌单标签 */
    const val PLAYLIST_HOT = "playlist/hot"

    /** 更新歌单信息 */
    const val PLAYLIST_UPDATE = "playlist/update"

    /** 歌单收藏者 */
    const val PLAYLIST_SUBSCRIBERS = "playlist/subscribers"

    /** 更新歌单标签 */
    const val PLAYLIST_TAGS_UPDATE = "playlist/tags/update"

    /** 更新歌单描述 */
    const val PLAYLIST_DESC_UPDATE = "playlist/desc/update"

    /** 更新歌单名 */
    const val PLAYLIST_NAME_UPDATE = "playlist/name/update"

    /** 精品歌单标签 */
    const val PLAYLIST_HIGHQUALITY_TAGS = "playlist/highquality/tags"

    // ======================== 云盘扩展 Cloud Ext ========================

    /** 删除云盘歌曲 */
    const val USER_CLOUD_DEL = "user/cloud/del"

    /** 云盘上传（从匹配的歌曲） */
    const val CLOUD_IMPORT = "cloud/import"

    /** 云盘歌曲匹配 */
    const val CLOUD_MATCH = "cloud/match"

    // ======================== 签到 Signin ========================

    /** 每日签到 */
    const val DAILY_SIGNIN = "daily/signin"

    // ======================== 其他 Other ========================

    /** 检查歌曲是否可用 */
    const val CHECK_MUSIC = "check/music"

    /** 批量请求 */
    const val BATCH = "batch"

    /** 通用 API（透传原始路径） */
    const val API = "api"

    /** 日历（音乐日历） */
    const val CALENDAR = "calendar"

    /** 动态 */
    const val EVENT = "event"

    /** 删除动态 */
    const val EVENT_DEL = "event/del"

    /** 转发动态 */
    const val EVENT_FORWARD = "event/forward"

    /** 最近播放歌曲 */
    const val RECORD_RECENT_SONG = "record/recent/song"

    /** 分享歌曲 */
    const val SHARE_RESOURCE = "share/resource"

    /** 获取电台订阅 */
    const val DJ_SUBLIST_FULL = "dj/sublist"

    /** 获取电台节目详情 */
    const val DJ_PROGRAM_DETAIL = "dj/program/detail"
}