package cp.player.core.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * 「API 方法 → 期望数据字段」的**唯一**契约表。
 *
 * 健康监控判定一次响应是否「符合预期」时只看两件事：业务码（[ApiResponseCodes]）和
 * 这里声明的**期望字段**。原先两张表内联在 `MusicApiServiceImpl` 的 companion 里，
 * 于是「表里的字段名和真实响应形状对不对」没有任何测试能钉住 —— 现在搬到本文件，
 * 与 `ApiFieldContractTest` 成对出现。
 *
 * ### 为什么不能只认一个字段名
 * 同一个端点在两类 Provider 上形状不同：
 * - **NeteaseCloudMusicApi(Node)** 会把上游 body 再包一层 `data`；
 * - 本仓库的 `netease-module-rust` 是**原样透传**上游 body（见它的
 *   `src/server/mod.rs`：「返回原始 Netease 响应 body」）⇒ 字段平铺在根层。
 *
 * 只写一个名字，另一个形状就会被判成缺字段、在诊断页刷出
 * 「期望字段: data, 实际字段: [code, msg]」这种**误报**：
 *
 * | 端点 | 真实响应 | 曾被误报为 |
 * |---|---|---|
 * | `pl/count` | `{"code":200,"msg":5}` | 期望字段 `data` |
 * | `login/status` | `{"code":200,"account":{…},"profile":{…}}`（平铺）| 期望字段 `data` |
 *
 * 所以：**主字段** + **等价字段**（[ALIASES]，按端点声明）+ **通用回退字段**
 * （[FALLBACK_FIELDS]）三选一命中即算通过。
 *
 * ⚠️ 别把 `profile` / `account` 之流直接塞进 [FALLBACK_FIELDS] —— 那是一张
 * **全局**表，加进去会让所有端点在缺字段时都能靠一个无关的 `profile` 蒙混过关。
 * 形状随端点而异的，写 [ALIASES]。
 */
internal object ApiFieldContract {

    /**
     * 方法 → 期望的主要数据字段名。
     *
     * 名字一律取**该字段在响应根层**的键名（形状差异由 [ALIASES] 兜）。
     */
    val EXPECTED_FIELDS: Map<String, String> = mapOf(
        MusicApiMethod.SEARCH_CLOUD to "result",
        MusicApiMethod.AUTH_QR_KEY to "data",
        MusicApiMethod.AUTH_QR_CREATE to "data",
        MusicApiMethod.AUTH_QR_CHECK to "data",
        MusicApiMethod.AUTH_LOGIN to "profile",
        MusicApiMethod.AUTH_LOGIN_PHONE to "profile",
        MusicApiMethod.AUTH_CAPTCHA_SENT to "data",
        MusicApiMethod.AUTH_LOGOUT to "code",
        MusicApiMethod.AUTH_ANONYMOUS to "code",
        // 主字段取 NCM(Node) 的包裹层 `data`；平铺 Provider 靠 ALIASES 的 profile/account 兜住。
        MusicApiMethod.AUTH_LOGIN_STATUS to "data",
        MusicApiMethod.USER_LIKE to "songs",
        MusicApiMethod.USER_DISLIKE_SONG to "data",
        MusicApiMethod.PLAYLIST_TRACKS to "body",
        MusicApiMethod.PLAYLIST_CREATE to "playlist",
        MusicApiMethod.PLAYLIST_DELETE to "code",
        MusicApiMethod.PLAYLIST_SUBSCRIBE to "code",
        MusicApiMethod.SEARCH_HOT_DETAIL to "data",
        MusicApiMethod.SEARCH_SUGGEST to "result",
        MusicApiMethod.PERSONAL_FM to "data",
        MusicApiMethod.INTELLIGENCE_LIST to "data",
        MusicApiMethod.SCROBBLE to "data",
        MusicApiMethod.COMMENT_MV to "comments",
        MusicApiMethod.COMMENT_DJ to "comments",
        MusicApiMethod.COMMENT_VIDEO to "comments",
        MusicApiMethod.COMMENT_LIKE to "data",
        MusicApiMethod.COMMENT_POST to "comment",
        MusicApiMethod.COMMENT_NEW to "data",
        // 未读数就在根层 `msg`（{"code":200,"msg":5}）。原先写 `data`：
        // 平铺 Provider 直接缺字段，包一层的 Provider 又会把 `data` 当通过 → 两头都不对。
        MusicApiMethod.MESSAGE_UNREAD_COUNT to "msg",
        MusicApiMethod.MESSAGE_MARK_READ to "data",
        MusicApiMethod.MESSAGE_SEND_TEXT to "msg",
        MusicApiMethod.HISTORY_RECOMMEND_SONGS to "data",
        MusicApiMethod.HISTORY_RECOMMEND_SONGS_DETAIL to "data",
        MusicApiMethod.MV_SUB to "data",
        MusicApiMethod.DJ_SUB to "data",
        MusicApiMethod.ALBUM_SUB to "data",
        MusicApiMethod.ARTIST_SUB to "data",
        MusicApiMethod.ARTIST_FOLLOW_COUNT to "data",
        MusicApiMethod.USER_UPDATE to "data",
        MusicApiMethod.PLAYLIST_UPDATE to "data",
        MusicApiMethod.PLAYLIST_TAGS_UPDATE to "data",
        MusicApiMethod.PLAYLIST_DESC_UPDATE to "data",
        MusicApiMethod.PLAYLIST_NAME_UPDATE to "data",
        MusicApiMethod.USER_CLOUD_DEL to "data",
        MusicApiMethod.CLOUD_IMPORT to "data",
        MusicApiMethod.CLOUD_MATCH to "data",
        MusicApiMethod.DAILY_SIGNIN to "data",
        MusicApiMethod.CHECK_MUSIC to "success",
        MusicApiMethod.BATCH to "data",
        MusicApiMethod.API to "data",
        MusicApiMethod.EVENT_DEL to "data",
        MusicApiMethod.EVENT_FORWARD to "data",
        MusicApiMethod.SHARE_RESOURCE to "data",
        MusicApiMethod.DJ_SUBLIST_FULL to "djRadios",
        MusicApiMethod.DJ_PROGRAM_DETAIL to "program",

        MusicApiMethod.USER_PLAYLIST to "playlist",
        MusicApiMethod.USER_PLAYLIST_CREATE to "playlist",
        MusicApiMethod.USER_PLAYLIST_COLLECT to "playlist",
        MusicApiMethod.USER_DETAIL to "profile",
        MusicApiMethod.USER_CLOUD to "data",
        MusicApiMethod.USER_LIKE_LIST to "ids",
        MusicApiMethod.USER_RECOMMEND_SONGS to "data",
        MusicApiMethod.USER_RECOMMEND_RESOURCE to "recommend",
        MusicApiMethod.PLAYLIST_DETAIL to "playlist",
        MusicApiMethod.PLAYLIST_TRACK_ALL to "songs",
        MusicApiMethod.ALBUM_DETAIL to "album",
        MusicApiMethod.ARTIST_DETAIL to "data",
        MusicApiMethod.ARTIST_SONGS to "songs",
        MusicApiMethod.ARTIST_ALBUM to "hotAlbums",
        MusicApiMethod.SONG_DETAIL to "songs",
        MusicApiMethod.LYRIC_NEW to "lrc",
        MusicApiMethod.COMMENT_MUSIC to "comments",
        MusicApiMethod.COMMENT_PLAYLIST to "comments",
        MusicApiMethod.COMMENT_ALBUM to "comments",
        MusicApiMethod.COMMENT_FLOOR to "comments",
        MusicApiMethod.MESSAGE_PRIVATE to "msgs",
        MusicApiMethod.MESSAGE_PRIVATE_HISTORY to "msgs",
        MusicApiMethod.MESSAGE_RECENT_CONTACT to "data",
        MusicApiMethod.SONG_URL_V1 to "data",
        MusicApiMethod.SONG_URL_V1_302 to "data",
        MusicApiMethod.SONG_DOWNLOAD_URL to "data",
        MusicApiMethod.TOPLIST to "list",
        MusicApiMethod.TOPLIST_DETAIL to "list",
        MusicApiMethod.TOP_SONG to "data",
        MusicApiMethod.TOP_ALBUM to "albums",
        MusicApiMethod.TOP_ARTISTS to "artists",
        MusicApiMethod.TOP_PLAYLIST to "playlists",
        MusicApiMethod.TOP_PLAYLIST_HIGHQUALITY to "playlists",
        MusicApiMethod.PERSONALIZED to "result",
        MusicApiMethod.PERSONALIZED_NEWSONG to "result",
        MusicApiMethod.BANNER to "banners",
        MusicApiMethod.SIMI_SONG to "songs",
        MusicApiMethod.SIMI_ARTIST to "artists",
        MusicApiMethod.SIMI_PLAYLIST to "playlists",
        MusicApiMethod.MV_DETAIL to "data",
        MusicApiMethod.MV_URL to "data",
        MusicApiMethod.MV_ALL to "data",
        MusicApiMethod.MV_FIRST to "data",
        MusicApiMethod.MV_SUBLIST to "data",
        MusicApiMethod.VIDEO_DETAIL to "data",
        MusicApiMethod.VIDEO_URL to "urls",
        MusicApiMethod.VIDEO_GROUP to "data",
        MusicApiMethod.VIDEO_TIMELINE_ALL to "datas",
        MusicApiMethod.DJ_DETAIL to "data",
        MusicApiMethod.DJ_PROGRAM to "programs",
        MusicApiMethod.DJ_HOT to "djRadios",
        MusicApiMethod.DJ_TOPLIST to "toplist",
        MusicApiMethod.DJ_RECOMMEND to "djRadios",
        MusicApiMethod.DJ_SUBLIST to "djRadios",
        MusicApiMethod.PROGRAM_RECOMMEND to "programs",
        MusicApiMethod.ALBUM_LIST to "products",
        MusicApiMethod.ALBUM_NEW to "albums",
        MusicApiMethod.ALBUM_NEWEST to "albums",
        MusicApiMethod.ALBUM_SUBLIST to "data",
        MusicApiMethod.ARTIST_TOP_SONG to "songs",
        MusicApiMethod.ARTIST_SUBLIST to "data",
        MusicApiMethod.ARTIST_MV to "mvs",
        MusicApiMethod.ARTIST_LIST to "artists",
        MusicApiMethod.USER_RECORD to "allData",
        MusicApiMethod.USER_FOLLOWS to "follow",
        MusicApiMethod.USER_FOLLOWEDS to "followeds",
        MusicApiMethod.USER_EVENT to "events",
        MusicApiMethod.USER_ACCOUNT to "profile",
        MusicApiMethod.USER_DJ to "data",
        MusicApiMethod.PLAYLIST_CATLIST to "categories",
        MusicApiMethod.PLAYLIST_HOT to "tags",
        MusicApiMethod.PLAYLIST_SUBSCRIBERS to "subscribers",
        MusicApiMethod.PLAYLIST_HIGHQUALITY_TAGS to "tags",
        MusicApiMethod.CALENDAR to "data",
        MusicApiMethod.EVENT to "events",
        MusicApiMethod.RECORD_RECENT_SONG to "data"
    )

    /**
     * 通用回退候选字段：主字段不在根层时，只要其中**任意一个**存在就不再报缺字段。
     *
     * 它是一张全局表，只放「包一层/套一层」这类**容器**键名（`data` / `result` / `playlist` …），
     * 不要放 `profile`、`account` 这种具体业务字段。
     */
    val FALLBACK_FIELDS: List<String> =
        listOf("data", "result", "playlist", "songs", "albums", "artists", "comments", "msgs", "hotData", "list")

    /**
     * 端点的**等价主字段**：形状随 Provider 而异的那些端点在这里补第二种写法。
     *
     * 键必须同时出现在 [EXPECTED_FIELDS] 里（否则主字段判空那一关走不到这儿）。
     * 目前只有 `login/status` —— 见文件头的形状对照表。
     */
    val ALIASES: Map<String, List<String>> = mapOf(
        MusicApiMethod.AUTH_LOGIN_STATUS to listOf("profile", "account")
    )

    /** 该方法声明的主字段；未声明（不校验数据字段）时返回 null。 */
    fun expectedFieldOf(method: String): String? = EXPECTED_FIELDS[method]

    /**
     * 响应是否满足 [method] 的期望字段。
     *
     * - 未声明期望字段的方法一律返回 true（例如 `logout` 这类只管 code 的）——
     *   调用方**必须**先看 [expectedFieldOf]，本函数的 true 不代表「有字段」。
     * - 命中顺序：主字段 → [ALIASES] → [FALLBACK_FIELDS]，任一存在即 true。
     * - **主字段 / [ALIASES]**：键存在但值是 JSON `null`（未登录的 `login/status` 就是这样）
     *   同样算命中 —— 那是**合法业务响应**，不是缺字段。
     * - **[FALLBACK_FIELDS]**：值必须不是 JSON `null`。`JsonNull` 是非空对象，
     *   不收紧就会有 `{"code":200,"data":null}` 把**任何**端点洗白（详见函数内注释）。
     */
    fun isExpectedFieldSatisfied(method: String, json: JsonObject): Boolean {
        val expected = EXPECTED_FIELDS[method] ?: return true
        // 主字段 / ALIASES：**键存在即可**，值允许是 JSON `null` —— 未登录的
        // `login/status` 返回 `{"code":200,"account":null,"profile":null}` 是合法业务响应
        // （见 ApiFieldContractTest 的同名用例），判成缺字段就是误报。
        if (json[expected] != null) return true
        if (ALIASES[method].orEmpty().any { json[it] != null }) return true
        // 全局回退表：**要求值非 JSON null**。kotlinx.serialization 的 JsonNull 是**非空对象**，
        // `json["data"] != null` 对 `"data": null` 同样为真 —— 不收紧的话，
        // `{"code":200,"data":null}` 会满足 FALLBACK 表里的每一个键，把**任何**端点的
        // 「缺字段」告警都洗白，诊断页就此失去意义。
        return FALLBACK_FIELDS.any { it != expected && isPresentNonNull(json, it) }
    }

    /** 键存在，且值不是 JSON `null`。 */
    private fun isPresentNonNull(json: JsonObject, key: String): Boolean {
        val element = json[key] ?: return false
        return element !is JsonNull
    }
}
