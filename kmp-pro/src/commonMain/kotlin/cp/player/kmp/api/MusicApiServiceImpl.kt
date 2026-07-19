package cp.player.kmp.api

import cp.player.kmp.monitor.HealthMonitor
import cp.player.kmp.provider.ProviderCookieStorage
import cp.player.kmp.provider.ProviderManager
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [MusicApiService] 的默认实现（KMP 版）。
 *
 * - 通过 [ProviderManager] 将请求委托给当前活跃 Provider
 * - 自动按 Provider 注入 cookie
 * - URL 解析等多 Provider 容灾使用 [callWithAllProviders]
 * - 响应兼容性验证 + [HealthMonitor] 三级分类记录
 */
class MusicApiServiceImpl(
    private val providerManager: ProviderManager,
    private val cookieStorage: ProviderCookieStorage
) : MusicApiService {

    // ======================== 通用 ========================

    override suspend fun callApi(method: String, map: Map<String, String>, cookie: String?): JsonElement {
        val isAuth = method.startsWith("login") || method.startsWith("register") || method.startsWith("captcha")
        val providerId = providerManager.getCurrentProviderId()
        val effectiveCookie = if (isAuth && cookie == null) null
            else cookie ?: cookieStorage.getCookie(providerId)

        val finalParams = if (!effectiveCookie.isNullOrEmpty() && !map.containsKey("cookie")) {
            map + ("cookie" to effectiveCookie)
        } else {
            map
        }
        val startTime = now()
        val result = providerManager.callApi(method, finalParams)
        val duration = now() - startTime

        return try {
            val json = parseJsonObject(result)
            val issues = validateResponse(method, json, duration)
            val code = (json["code"] as? JsonPrimitive)?.intOrNull
            val isQrCheck = method == MusicApiMethod.AUTH_QR_CHECK
            // 重定向类端点（song/url/v1/302）按 RFC 7231 形态返回 {cookie, level, redirectUrl}，
            // 无 code/data 字段；只要含有效 http(s) URL 即为成功，不进入 code 判定路径。
            val redirectUrlValid = method == MusicApiMethod.SONG_URL_V1_302 &&
                !extractUrl(json).isNullOrEmpty() && extractUrl(json)!!.startsWith("http")
            val success = redirectUrlValid
                || code == 200 || code == 0 || code == 201 || code == 301
                || (isQrCheck && (code == 801 || code == 802 || code == 803))
            val warnings = issues.map { it.warning }
            val level = classifyLevel(success, warnings)

            HealthMonitor.recordCall(HealthMonitor.ApiCallRecord(
                timestamp = startTime,
                providerId = providerId,
                method = method,
                durationMs = duration,
                success = success,
                level = level,
                errorCode = if (!success) code else null,
                errorMessage = buildErrorMessage(json, issues, success),
                responseWarnings = warnings,
                responseCode = code,
                expectedField = issues.firstOrNull { it.expected != null }?.expected
            ))
            json
        } catch (e: Exception) {
            HealthMonitor.recordCall(HealthMonitor.ApiCallRecord(
                timestamp = startTime,
                providerId = providerId,
                method = method,
                durationMs = duration,
                success = false,
                level = HealthMonitor.HealthLevel.ERROR,
                errorCode = 500,
                errorMessage = "JSON parse error: ${e.message}",
                responseWarnings = listOf(HealthMonitor.ResponseWarning.MALFORMED_RESPONSE)
            ))
            buildJsonObject {
                put("code", 500)
                put("msg", "JSON parse error: ${e.message}")
            }
        }
    }

    // ======================== 认证 Auth ========================

    override suspend fun getQrKey(): JsonElement = callApi(MusicApiMethod.AUTH_QR_KEY)
    override suspend fun createQrCode(key: String): JsonElement =
        callApi(MusicApiMethod.AUTH_QR_CREATE, mapOf("key" to key, "qrimg" to "true"))
    override suspend fun checkQrStatus(key: String): JsonElement =
        callApi(MusicApiMethod.AUTH_QR_CHECK, mapOf("key" to key))
    override suspend fun login(email: String, password: String, md5: Boolean): JsonElement {
        val params = mutableMapOf("email" to email)
        if (md5) params["md5_password"] = password else params["password"] = password
        return callApi(MusicApiMethod.AUTH_LOGIN, params)
    }
    override suspend fun loginWithPhone(phone: String, password: String, captcha: Boolean, md5: Boolean): JsonElement {
        val params = mutableMapOf("phone" to phone)
        when { captcha -> params["captcha"] = password; md5 -> params["md5_password"] = password; else -> params["password"] = password }
        return callApi(MusicApiMethod.AUTH_LOGIN_PHONE, params)
    }
    override suspend fun sendCaptcha(phone: String): JsonElement =
        callApi(MusicApiMethod.AUTH_CAPTCHA_SENT, mapOf("phone" to phone))
    override suspend fun logout(): JsonElement = callApi(MusicApiMethod.AUTH_LOGOUT)
    override suspend fun loginAnonymous(): JsonElement = callApi(MusicApiMethod.AUTH_ANONYMOUS)
    override suspend fun getLoginStatus(cookie: String?): JsonElement =
        callApi(MusicApiMethod.AUTH_LOGIN_STATUS, cookie = cookie)

    // ======================== 用户 User ========================

    override suspend fun getUserPlaylists(uid: Long): JsonElement =
        callApi(MusicApiMethod.USER_PLAYLIST, mapOf("uid" to uid.toString()))
    override suspend fun getUserCreatedPlaylists(uid: Long): JsonElement =
        callApi(MusicApiMethod.USER_PLAYLIST_CREATE, mapOf("uid" to uid.toString()))
    override suspend fun getUserCollectedPlaylists(uid: Long): JsonElement =
        callApi(MusicApiMethod.USER_PLAYLIST_COLLECT, mapOf("uid" to uid.toString()))
    override suspend fun getUserDetail(uid: Long): JsonElement =
        callApi(MusicApiMethod.USER_DETAIL, mapOf("uid" to uid.toString()))
    override suspend fun getUserCloud(limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.USER_CLOUD, mapOf("limit" to limit.toString(), "offset" to offset.toString()))
    override suspend fun getLikeList(uid: Long): JsonElement =
        callApi(MusicApiMethod.USER_LIKE_LIST, mapOf("uid" to uid.toString()))
    override suspend fun likeSong(id: String, like: Boolean): JsonElement =
        callApi(MusicApiMethod.USER_LIKE, mapOf("id" to id, "like" to like.toString()))
    override suspend fun getRecommendedSongs(): JsonElement = callApi(MusicApiMethod.USER_RECOMMEND_SONGS)
    override suspend fun getRecommendedPlaylists(): JsonElement = callApi(MusicApiMethod.USER_RECOMMEND_RESOURCE)
    override suspend fun dislikeSong(id: String): JsonElement =
        callApi(MusicApiMethod.USER_DISLIKE_SONG, mapOf("id" to id))

    // ======================== 歌单 Playlist ========================

    override suspend fun getPlaylistDetail(id: Long): JsonElement =
        callApi(MusicApiMethod.PLAYLIST_DETAIL, mapOf("id" to id.toString()))
    override suspend fun getPlaylistTracks(id: Long, limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.PLAYLIST_TRACK_ALL, mapOf("id" to id.toString(), "limit" to limit.toString(), "offset" to offset.toString()))
    override suspend fun addTracksToPlaylist(pid: Long, trackIds: List<String>): JsonElement =
        callApi(MusicApiMethod.PLAYLIST_TRACKS, mapOf("op" to "add", "pid" to pid.toString(), "tracks" to trackIds.joinToString(",")))
    override suspend fun removeTracksFromPlaylist(pid: Long, trackIds: List<String>): JsonElement =
        callApi(MusicApiMethod.PLAYLIST_TRACKS, mapOf("op" to "del", "pid" to pid.toString(), "tracks" to trackIds.joinToString(",")))
    override suspend fun createPlaylist(name: String, privacy: Int): JsonElement =
        callApi(MusicApiMethod.PLAYLIST_CREATE, mapOf("name" to name, "privacy" to privacy.toString()))
    override suspend fun deletePlaylist(id: Long): JsonElement =
        callApi(MusicApiMethod.PLAYLIST_DELETE, mapOf("id" to id.toString()))
    override suspend fun subscribePlaylist(id: Long, t: Int): JsonElement =
        callApi(MusicApiMethod.PLAYLIST_SUBSCRIBE, mapOf("id" to id.toString(), "t" to t.toString()))

    // ======================== 专辑 Album ========================

    override suspend fun getAlbumDetail(id: Long): JsonElement =
        callApi(MusicApiMethod.ALBUM_DETAIL, mapOf("id" to id.toString()))

    // ======================== 歌手 Artist ========================

    override suspend fun getArtistDetail(id: Long): JsonElement =
        callApi(MusicApiMethod.ARTIST_DETAIL, mapOf("id" to id.toString()))
    override suspend fun getArtistSongs(id: Long, limit: Int): JsonElement =
        callApi(MusicApiMethod.ARTIST_SONGS, mapOf("id" to id.toString(), "limit" to limit.toString()))
    override suspend fun getArtistAlbums(id: Long, limit: Int): JsonElement =
        callApi(MusicApiMethod.ARTIST_ALBUM, mapOf("id" to id.toString(), "limit" to limit.toString()))

    // ======================== 搜索 Search ========================

    override suspend fun search(keywords: String, type: Int): JsonElement =
        callApi(MusicApiMethod.SEARCH_CLOUD, mapOf("keywords" to keywords, "type" to type.toString()))
    override suspend fun getHotSearches(): JsonElement = callApi(MusicApiMethod.SEARCH_HOT_DETAIL)
    override suspend fun getSearchSuggestions(keywords: String): JsonElement =
        callApi(MusicApiMethod.SEARCH_SUGGEST, mapOf("keywords" to keywords, "type" to "mobile"))

    // ======================== 播放 Playback ========================

    override suspend fun getSongUrl(songId: String, level: String): JsonElement {
        val params = mutableMapOf("id" to songId, "level" to level)
        val result = callApi(MusicApiMethod.SONG_URL_V1_302, params)
        val url = extractUrl(result)
        if (!url.isNullOrEmpty() && url.startsWith("http")) {
            if (result is JsonObject && result["redirectUrl"] == null && result["url"] == null) {
                // 注入 redirectUrl（返回新对象以保持不可变）
                return result.mutAdd("redirectUrl", url)
            }
            return result
        }
        HealthMonitor.recordCall(HealthMonitor.ApiCallRecord(
            timestamp = now(), providerId = providerManager.getCurrentProviderId(),
            method = MusicApiMethod.SONG_URL_V1_302, durationMs = 0, success = false,
            wasFallback = true, fallbackFrom = MusicApiMethod.SONG_URL_V1,
            errorMessage = "302 版本无有效 URL，自动回退"
        ))
        return callApi(MusicApiMethod.SONG_URL_V1, params)
    }
    override suspend fun getSongUrlFallback(songId: String, level: String): JsonElement =
        callApi(MusicApiMethod.SONG_URL_V1, mapOf("id" to songId, "level" to level))
    override suspend fun getSongDownloadUrl(songId: String, level: String): JsonElement =
        callApi(MusicApiMethod.SONG_DOWNLOAD_URL, mapOf("id" to songId, "level" to level))
    override suspend fun getSongDetail(ids: List<String>): JsonElement =
        callApi(MusicApiMethod.SONG_DETAIL, mapOf("ids" to ids.joinToString(",")))
    override suspend fun getPersonalFm(): JsonElement =
        callApi(MusicApiMethod.PERSONAL_FM, mapOf("timestamp" to now().toString()))
    override suspend fun getIntelligenceList(songId: String, playlistId: Long): JsonElement =
        callApi(MusicApiMethod.INTELLIGENCE_LIST, mapOf("id" to songId, "pid" to playlistId.toString(), "sid" to songId, "count" to "20"))
    override suspend fun getLyric(songId: String): JsonElement =
        callApi(MusicApiMethod.LYRIC_NEW, mapOf("id" to songId))
    override suspend fun scrobble(songId: String, sourceId: String, playedSeconds: Int): JsonElement =
        callApi(MusicApiMethod.SCROBBLE, mapOf("id" to songId, "sourceid" to sourceId, "time" to playedSeconds.toString()))

    // ======================== 社交 Social ========================

    override suspend fun getComments(id: String, type: String, limit: Int, offset: Int, sortType: Int): JsonElement {
        val pageNo = (offset / limit) + 1
        return callApi(getCommentMethod(type), mapOf(
            "id" to id,
            "type" to typeToCode(type),
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "pageSize" to limit.toString(),
            "pageNo" to pageNo.toString(),
            "sortType" to sortType.toString()
        ))
    }
    override suspend fun getFloorComments(id: String, parentCommentId: Long, type: String, limit: Int, time: Long): JsonElement {
        val t = typeToCode(type)
        val params = mutableMapOf("id" to id, "parentCommentId" to parentCommentId.toString(), "type" to t, "limit" to limit.toString())
        if (time > 0) params["time"] = time.toString()
        return callApi(MusicApiMethod.COMMENT_FLOOR, params)
    }
    override suspend fun likeComment(id: String, cid: Long, type: String, liked: Boolean): JsonElement =
        callApi(MusicApiMethod.COMMENT_LIKE, mapOf("id" to id, "cid" to cid.toString(), "t" to if (liked) "1" else "0", "type" to typeToCode(type)))
    override suspend fun postComment(id: String, type: String, content: String, replyId: Long?): JsonElement {
        val params = mutableMapOf("id" to id, "type" to typeToCode(type), "content" to content, "op" to if (replyId != null) "reply" else "add")
        if (replyId != null) params["commentId"] = replyId.toString()
        return callApi(MusicApiMethod.COMMENT_POST, params)
    }
    override suspend fun getUnreadCount(): JsonElement = callApi(MusicApiMethod.MESSAGE_UNREAD_COUNT)
    override suspend fun getRecentContacts(): JsonElement = callApi(MusicApiMethod.MESSAGE_RECENT_CONTACT)
    override suspend fun getPrivateMessages(): JsonElement =
        callApi(MusicApiMethod.MESSAGE_PRIVATE, mapOf("limit" to "50"))
    override suspend fun getMessageHistory(uid: Long): JsonElement =
        callApi(MusicApiMethod.MESSAGE_PRIVATE_HISTORY, mapOf("uid" to uid.toString()))
    override suspend fun markMessageAsRead(uid: Long): JsonElement =
        callApi(MusicApiMethod.MESSAGE_MARK_READ, mapOf("uid" to uid.toString()))
    override suspend fun sendMessage(uid: Long, text: String): JsonElement =
        callApi(MusicApiMethod.MESSAGE_SEND_TEXT, mapOf("user_ids" to uid.toString(), "msg" to text))

    // ======================== 排行榜 Ranking ========================

    override suspend fun getToplist(): JsonElement = callApi(MusicApiMethod.TOPLIST)
    override suspend fun getToplistDetail(): JsonElement = callApi(MusicApiMethod.TOPLIST_DETAIL)
    override suspend fun getTopSongs(type: Int): JsonElement =
        callApi(MusicApiMethod.TOP_SONG, mapOf("type" to type.toString()))
    override suspend fun getTopAlbums(area: String, limit: Int): JsonElement =
        callApi(MusicApiMethod.TOP_ALBUM, mapOf("area" to area, "limit" to limit.toString()))
    override suspend fun getTopArtists(limit: Int): JsonElement =
        callApi(MusicApiMethod.TOP_ARTISTS, mapOf("limit" to limit.toString()))
    override suspend fun getTopPlaylists(order: String, cat: String, limit: Int): JsonElement =
        callApi(MusicApiMethod.TOP_PLAYLIST, mapOf("order" to order, "cat" to cat, "limit" to limit.toString()))
    override suspend fun getHighqualityPlaylists(cat: String, limit: Int): JsonElement =
        callApi(MusicApiMethod.TOP_PLAYLIST_HIGHQUALITY, mapOf("cat" to cat, "limit" to limit.toString()))

    // ======================== 推荐 Discovery ========================

    override suspend fun getPersonalizedPlaylists(limit: Int): JsonElement =
        callApi(MusicApiMethod.PERSONALIZED, mapOf("limit" to limit.toString()))
    override suspend fun getPersonalizedNewSongs(limit: Int): JsonElement =
        callApi(MusicApiMethod.PERSONALIZED_NEWSONG, mapOf("limit" to limit.toString()))
    override suspend fun getBanner(): JsonElement =
        callApi(MusicApiMethod.BANNER, mapOf("type" to "1"))
    override suspend fun getHistoryRecommendSongs(): JsonElement =
        callApi(MusicApiMethod.HISTORY_RECOMMEND_SONGS)
    override suspend fun getHistoryRecommendSongsDetail(date: String): JsonElement =
        callApi(MusicApiMethod.HISTORY_RECOMMEND_SONGS_DETAIL, mapOf("date" to date))

    // ======================== 相似 Similar ========================

    override suspend fun getSimilarSongs(songId: String): JsonElement =
        callApi(MusicApiMethod.SIMI_SONG, mapOf("id" to songId))
    override suspend fun getSimilarArtists(artistId: Long): JsonElement =
        callApi(MusicApiMethod.SIMI_ARTIST, mapOf("id" to artistId.toString()))
    override suspend fun getSimilarPlaylists(songId: String): JsonElement =
        callApi(MusicApiMethod.SIMI_PLAYLIST, mapOf("id" to songId))

    // ======================== 签到 Signin ========================

    override suspend fun dailySignin(): JsonElement = callApi(MusicApiMethod.DAILY_SIGNIN)

    // ======================== MV ========================

    override suspend fun getMvDetail(mvId: String): JsonElement =
        callApi(MusicApiMethod.MV_DETAIL, mapOf("mvid" to mvId))
    override suspend fun getMvUrl(mvId: String, resolution: Int): JsonElement =
        callApi(MusicApiMethod.MV_URL, mapOf("id" to mvId, "r" to resolution.toString()))
    override suspend fun getAllMv(area: String, limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.MV_ALL, mapOf("area" to area, "limit" to limit.toString(), "offset" to offset.toString()))
    override suspend fun getFirstMv(limit: Int): JsonElement =
        callApi(MusicApiMethod.MV_FIRST, mapOf("limit" to limit.toString()))
    override suspend fun subscribeMv(mvId: String, t: Int): JsonElement =
        callApi(MusicApiMethod.MV_SUB, mapOf("mvid" to mvId, "t" to t.toString()))
    override suspend fun getMvSublist(limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.MV_SUBLIST, mapOf("limit" to limit.toString(), "offset" to offset.toString()))

    // ======================== 视频 Video ========================

    override suspend fun getVideoDetail(videoId: String): JsonElement =
        callApi(MusicApiMethod.VIDEO_DETAIL, mapOf("id" to videoId))
    override suspend fun getVideoUrl(videoId: String, resolution: Int): JsonElement =
        callApi(MusicApiMethod.VIDEO_URL, mapOf("id" to videoId, "res" to resolution.toString()))
    override suspend fun getVideoGroup(): JsonElement = callApi(MusicApiMethod.VIDEO_GROUP)
    override suspend fun getVideoTimelineAll(offset: Int): JsonElement =
        callApi(MusicApiMethod.VIDEO_TIMELINE_ALL, mapOf("offset" to offset.toString()))

    // ======================== 电台 DJ ========================

    override suspend fun getDjDetail(djId: String): JsonElement =
        callApi(MusicApiMethod.DJ_DETAIL, mapOf("rid" to djId))
    override suspend fun getDjProgram(djId: String, limit: Int, offset: Int, asc: Boolean): JsonElement =
        callApi(MusicApiMethod.DJ_PROGRAM, mapOf("rid" to djId, "limit" to limit.toString(), "offset" to offset.toString(), "asc" to asc.toString()))
    override suspend fun getDjHot(limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.DJ_HOT, mapOf("limit" to limit.toString(), "offset" to offset.toString()))
    override suspend fun getDjToplist(limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.DJ_TOPLIST, mapOf("limit" to limit.toString(), "offset" to offset.toString()))
    override suspend fun getDjRecommend(): JsonElement = callApi(MusicApiMethod.DJ_RECOMMEND)
    override suspend fun subscribeDj(djId: String, t: Int): JsonElement =
        callApi(MusicApiMethod.DJ_SUB, mapOf("rid" to djId, "t" to t.toString()))
    override suspend fun getDjSublist(): JsonElement = callApi(MusicApiMethod.DJ_SUBLIST)
    override suspend fun getProgramRecommend(limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.PROGRAM_RECOMMEND, mapOf("limit" to limit.toString(), "offset" to offset.toString()))

    // ======================== 专辑扩展 Album Ext ========================

    override suspend fun getAlbumList(limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.ALBUM_LIST, mapOf("limit" to limit.toString(), "offset" to offset.toString()))
    override suspend fun getAlbumNew(): JsonElement = callApi(MusicApiMethod.ALBUM_NEW)
    override suspend fun getAlbumNewest(limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.ALBUM_NEWEST, mapOf("limit" to limit.toString(), "offset" to offset.toString()))
    override suspend fun subscribeAlbum(id: Long, t: Int): JsonElement =
        callApi(MusicApiMethod.ALBUM_SUB, mapOf("id" to id.toString(), "t" to t.toString()))
    override suspend fun getAlbumSublist(limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.ALBUM_SUBLIST, mapOf("limit" to limit.toString(), "offset" to offset.toString()))

    // ======================== 歌手扩展 Artist Ext ========================

    override suspend fun getArtistTopSong(id: Long): JsonElement =
        callApi(MusicApiMethod.ARTIST_TOP_SONG, mapOf("id" to id.toString()))
    override suspend fun subscribeArtist(id: Long, t: Int): JsonElement =
        callApi(MusicApiMethod.ARTIST_SUB, mapOf("id" to id.toString(), "t" to t.toString()))
    override suspend fun getArtistSublist(): JsonElement = callApi(MusicApiMethod.ARTIST_SUBLIST)
    override suspend fun getArtistMv(id: Long, limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.ARTIST_MV, mapOf("id" to id.toString(), "limit" to limit.toString(), "offset" to offset.toString()))
    override suspend fun getArtistList(type: Int, area: Int, initial: String, limit: Int, offset: Int): JsonElement {
        val params = mutableMapOf("type" to type.toString(), "area" to area.toString(), "limit" to limit.toString(), "offset" to offset.toString())
        if (initial.isNotEmpty()) params["initial"] = initial
        return callApi(MusicApiMethod.ARTIST_LIST, params)
    }
    override suspend fun getArtistFollowCount(id: Long): JsonElement =
        callApi(MusicApiMethod.ARTIST_FOLLOW_COUNT, mapOf("id" to id.toString()))

    // ======================== 用户扩展 User Ext ========================

    override suspend fun getUserRecord(uid: Long, type: Int): JsonElement =
        callApi(MusicApiMethod.USER_RECORD, mapOf("uid" to uid.toString(), "type" to type.toString()))
    override suspend fun getUserFollows(uid: Long, limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.USER_FOLLOWS, mapOf("uid" to uid.toString(), "limit" to limit.toString(), "offset" to offset.toString()))
    override suspend fun getUserFolloweds(uid: Long, limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.USER_FOLLOWEDS, mapOf("uid" to uid.toString(), "limit" to limit.toString(), "offset" to offset.toString()))
    override suspend fun getUserEvent(uid: Long, limit: Int): JsonElement =
        callApi(MusicApiMethod.USER_EVENT, mapOf("uid" to uid.toString(), "limit" to limit.toString()))
    override suspend fun updateUser(params: Map<String, String>): JsonElement =
        callApi(MusicApiMethod.USER_UPDATE, params)
    override suspend fun getUserAccount(): JsonElement = callApi(MusicApiMethod.USER_ACCOUNT)
    override suspend fun getUserDj(uid: Long): JsonElement =
        callApi(MusicApiMethod.USER_DJ, mapOf("uid" to uid.toString()))

    // ======================== 歌单扩展 Playlist Ext ========================

    override suspend fun getPlaylistCatlist(): JsonElement = callApi(MusicApiMethod.PLAYLIST_CATLIST)
    override suspend fun getPlaylistHot(): JsonElement = callApi(MusicApiMethod.PLAYLIST_HOT)
    override suspend fun updatePlaylist(id: Long, name: String?, desc: String?, tags: List<String>?): JsonElement {
        val params = mutableMapOf("id" to id.toString())
        if (name != null) params["name"] = name
        if (desc != null) params["desc"] = desc
        if (tags != null) params["tags"] = tags.joinToString(",")
        return callApi(MusicApiMethod.PLAYLIST_UPDATE, params)
    }
    override suspend fun getPlaylistSubscribers(id: Long, limit: Int, offset: Int): JsonElement =
        callApi(MusicApiMethod.PLAYLIST_SUBSCRIBERS, mapOf("id" to id.toString(), "limit" to limit.toString(), "offset" to offset.toString()))
    override suspend fun getPlaylistHighqualityTags(): JsonElement =
        callApi(MusicApiMethod.PLAYLIST_HIGHQUALITY_TAGS)
    override suspend fun updatePlaylistDesc(id: Long, desc: String): JsonElement =
        callApi(MusicApiMethod.PLAYLIST_DESC_UPDATE, mapOf("id" to id.toString(), "desc" to desc))
    override suspend fun updatePlaylistName(id: Long, name: String): JsonElement =
        callApi(MusicApiMethod.PLAYLIST_NAME_UPDATE, mapOf("id" to id.toString(), "name" to name))

    // ======================== 云盘扩展 Cloud Ext ========================

    override suspend fun deleteUserCloud(songIds: List<String>): JsonElement =
        callApi(MusicApiMethod.USER_CLOUD_DEL, mapOf("id" to songIds.joinToString(",")))
    override suspend fun cloudImport(songId: String, matchSongId: String): JsonElement =
        callApi(MusicApiMethod.CLOUD_IMPORT, mapOf("songId" to songId, "matchSongId" to matchSongId))
    override suspend fun cloudMatch(uid: Long, songId: String, adjustSongId: String): JsonElement =
        callApi(MusicApiMethod.CLOUD_MATCH, mapOf("uid" to uid.toString(), "songId" to songId, "adjustSongId" to adjustSongId))

    // ======================== 其他 Other ========================

    override suspend fun checkMusic(id: String, br: Int): JsonElement =
        callApi(MusicApiMethod.CHECK_MUSIC, mapOf("id" to id, "br" to br.toString()))
    override suspend fun batch(apiRequests: String): JsonElement =
        callApi(MusicApiMethod.BATCH, mapOf("batchData" to apiRequests))
    override suspend fun getCalendar(): JsonElement = callApi(MusicApiMethod.CALENDAR)
    override suspend fun getEvent(limit: Int): JsonElement =
        callApi(MusicApiMethod.EVENT, mapOf("pagesize" to limit.toString()))
    override suspend fun deleteEvent(evId: Long): JsonElement =
        callApi(MusicApiMethod.EVENT_DEL, mapOf("evId" to evId.toString()))
    override suspend fun forwardEvent(evId: Long, uid: Long, forwards: String): JsonElement =
        callApi(MusicApiMethod.EVENT_FORWARD, mapOf("evId" to evId.toString(), "uid" to uid.toString(), "forwards" to forwards))
    override suspend fun getRecentSongs(limit: Int): JsonElement =
        callApi(MusicApiMethod.RECORD_RECENT_SONG, mapOf("limit" to limit.toString()))
    override suspend fun shareResource(id: String, type: String, msg: String): JsonElement {
        val params = mutableMapOf("id" to id, "type" to type)
        if (msg.isNotEmpty()) params["msg"] = msg
        return callApi(MusicApiMethod.SHARE_RESOURCE, params)
    }

    /**
     * 依次尝试所有已加载的 Provider 调用 API（容灾）。
     *
     * @param method API 方法名（内部标准名）
     * @param params 请求参数
     * @param predicate 判断返回是否成功的谓词
     * @return 第一个成功结果，全部失败返回 null
     */
    suspend fun <T> callWithAllProviders(
        method: String,
        params: Map<String, String>,
        providers: List<BackendProviderRef>,
        predicate: (JsonObject) -> T?
    ): T? {
        val ordered = providers.toMutableList()
        val current = providerManager.currentProvider
        if (current != null) { ordered.remove(current); ordered.add(0, current) }
        for (provider in ordered) {
            for (attempt in 1..2) {
                val startTime = now()
                try {
                    val actual = if (attempt == 1) MusicApiMethod.SONG_URL_V1_302 else method
                    val mapped = provider.apiMap?.get(actual) ?: actual
                    if (mapped.isEmpty() || mapped.equals("unsupported", ignoreCase = true)) continue
                    val result = provider.callApi(mapped, params)
                    val body = parseJsonObject(result)
                    val value = predicate(body)
                    if (value != null) {
                        HealthMonitor.recordCall(HealthMonitor.ApiCallRecord(
                            timestamp = startTime, providerId = provider.id, method = method,
                            durationMs = now() - startTime, success = true,
                            wasFallback = provider.id != current?.id,
                            fallbackFrom = if (provider.id != current?.id) current?.id else null
                        ))
                        return value
                    }
                } catch (e: Exception) {
                    HealthMonitor.recordCall(HealthMonitor.ApiCallRecord(
                        timestamp = startTime, providerId = provider.id, method = method,
                        durationMs = now() - startTime, success = false, errorMessage = e.message,
                        wasFallback = provider.id != current?.id,
                        fallbackFrom = if (provider.id != current?.id) current?.id else null
                    ))
                }
                if (attempt < 2) delay(200L)
            }
        }
        return null
    }

    // ======================== 响应兼容性验证 ========================

    private data class ValidationIssue(
        val warning: HealthMonitor.ResponseWarning,
        val expected: String? = null,
        val detail: String = ""
    )

    private val EXPECTED_FIELDS = mapOf(
        MusicApiMethod.SEARCH_CLOUD to "result",
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
        MusicApiMethod.COMMENT_FLOOR to "data",
        MusicApiMethod.COMMENT_NEW to "data",
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

    private val FALLBACK_FIELDS = listOf("data", "result", "playlist", "songs", "albums", "artists", "comments", "msgs", "hotData", "list")

    private fun buildErrorMessage(json: JsonObject, issues: List<ValidationIssue>, success: Boolean): String? {
        if (success && issues.isEmpty()) return null
        val parts = mutableListOf<String>()
        if (!success) {
            val msg = (json["msg"] as? JsonPrimitive)?.contentOrNull ?: (json["message"] as? JsonPrimitive)?.contentOrNull
            parts.add("code=${(json["code"] as? JsonPrimitive)?.contentOrNull ?: "?"}${if (msg != null) ", msg=$msg" else ""}")
        }
        for (issue in issues) {
            val keys = json.keys.take(8).joinToString()
            when (issue.warning) {
                HealthMonitor.ResponseWarning.MISSING_CODE -> parts.add("缺少code字段, 实际keys=$keys")
                HealthMonitor.ResponseWarning.UNEXPECTED_CODE -> parts.add("异常code=${(json["code"] as? JsonPrimitive)?.contentOrNull}")
                HealthMonitor.ResponseWarning.MISSING_DATA_FIELD -> parts.add("期望字段: ${issue.expected ?: "?"}, 实际字段: ${json.keys}")
                HealthMonitor.ResponseWarning.EMPTY_DATA_ARRAY -> parts.add("字段'${issue.expected}'为空数组")
                HealthMonitor.ResponseWarning.EMPTY_DATA_OBJECT -> parts.add("字段'${issue.expected}'为空对象")
                HealthMonitor.ResponseWarning.MALFORMED_RESPONSE -> parts.add("响应格式异常(非JSON)")
                HealthMonitor.ResponseWarning.UNSUPPORTED_BY_PROVIDER -> parts.add("Provider不支持(code=-1)")
                HealthMonitor.ResponseWarning.SLOW_RESPONSE -> parts.add("响应过慢(>${issue.detail}ms)")
            }
        }
        return parts.joinToString("; ").take(500)
    }

    private fun validateResponse(method: String, json: JsonObject, durationMs: Long): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>(4)

        // 重定向类端点（302）：格式为 {cookie, level, redirectUrl}，无 code/data；跳过常规校验。
        // 仅 URL url/url 字段缺失时记 warning。
        if (method == MusicApiMethod.SONG_URL_V1_302) {
            val redir = extractUrl(json)
            if (redir.isNullOrEmpty()) {
                issues.add(ValidationIssue(HealthMonitor.ResponseWarning.MISSING_DATA_FIELD, expected = "redirectUrl"))
            }
            if (durationMs > 5000) {
                issues.add(ValidationIssue(HealthMonitor.ResponseWarning.SLOW_RESPONSE, detail = "5000"))
            }
            return issues
        }

        // 1. code
        val codeEl = json["code"]
        val code = (codeEl as? JsonPrimitive)?.intOrNull
        if (code == null) {
            issues.add(ValidationIssue(HealthMonitor.ResponseWarning.MISSING_CODE))
        } else if (code != 200 && code != 0 && code != 201 && code != 301) {
            val isQrIntermediate = method == MusicApiMethod.AUTH_QR_CHECK && (code == 801 || code == 802 || code == 803)
            if (!isQrIntermediate) {
                issues.add(ValidationIssue(
                    if (code == -1) HealthMonitor.ResponseWarning.UNSUPPORTED_BY_PROVIDER
                    else HealthMonitor.ResponseWarning.UNEXPECTED_CODE
                ))
            }
        }
        
        // 只有当状态码表示成功时，才校验数据字段
        val isSuccess = code == 200 || code == 0 || code == 201
        if (isSuccess) {
            // 2. 数据字段
            val expected = EXPECTED_FIELDS[method]
            if (expected != null && json[expected] == null) {
                val found = FALLBACK_FIELDS.firstOrNull { it != expected && json[it] != null }
                if (found == null) {
                    issues.add(ValidationIssue(HealthMonitor.ResponseWarning.MISSING_DATA_FIELD, expected = expected))
                }
            }
            // 2b. 空数组/对象
            json[expected]?.let { field ->
                if (field is kotlinx.serialization.json.JsonArray && field.isEmpty()) {
                    issues.add(ValidationIssue(HealthMonitor.ResponseWarning.EMPTY_DATA_ARRAY, expected = expected))
                } else if (field is JsonObject && field.isEmpty()) {
                    issues.add(ValidationIssue(HealthMonitor.ResponseWarning.EMPTY_DATA_OBJECT, expected = expected))
                }
            }
        }
        // 3. URL 方法
        if (method in setOf(MusicApiMethod.SONG_URL_V1, MusicApiMethod.SONG_URL_V1_302, MusicApiMethod.SONG_DOWNLOAD_URL)) {
            if (extractUrl(json).isNullOrEmpty()) {
                issues.add(ValidationIssue(HealthMonitor.ResponseWarning.MISSING_DATA_FIELD, expected = "url"))
            }
        }
        // 4. 响应时间
        if (durationMs > 5000) {
            issues.add(ValidationIssue(HealthMonitor.ResponseWarning.SLOW_RESPONSE, detail = "5000"))
        }
        return issues
    }

    private fun classifyLevel(success: Boolean, warnings: List<HealthMonitor.ResponseWarning>): HealthMonitor.HealthLevel {
        if (!success) return HealthMonitor.HealthLevel.ERROR
        if (warnings.isEmpty()) return HealthMonitor.HealthLevel.OK
        return if (warnings.any { HealthMonitor.classify(it) == HealthMonitor.HealthLevel.ERROR })
            HealthMonitor.HealthLevel.ERROR else HealthMonitor.HealthLevel.WARNING
    }

    // ======================== 工具方法 ========================

    private fun extractUrl(body: JsonElement): String? {
        if (body !is JsonObject) return null
        (body["redirectUrl"] as? JsonPrimitive)?.contentOrNull?.let { if (it.startsWith("http")) return it }
        return findUrlRecursive(body)
    }

    private fun findUrlRecursive(element: JsonElement?): String? {
        if (element == null) return null
        when (element) {
            is JsonPrimitive -> {
                val s = element.contentOrNull ?: return null
                if (s.startsWith("http") && s.length > 12 && !s.contains("null")) return s
            }
            is JsonObject -> {
                for (key in listOf("url", "picUrl", "coverImgUrl", "avatarUrl")) {
                    val p = element[key]
                    if (p is JsonPrimitive) {
                        val s = p.contentOrNull
                        if (s != null && s.startsWith("http") && s.length > 12 && !s.contains("null")) return s
                    }
                }
                for ((_, v) in element) { findUrlRecursive(v)?.let { return it } }
            }
            is kotlinx.serialization.json.JsonArray -> {
                for (e in element) { findUrlRecursive(e)?.let { return it } }
            }
        }
        return null
    }

    private fun typeToCode(type: String): String = when (type) {
        "music" -> "0"; "mv" -> "1"; "playlist" -> "2"
        "album" -> "3"; "dj" -> "4"; "video" -> "5"; "event" -> "6"
        else -> "0"
    }

    /** 毫秒时间戳。使用 kotlinx-datetime，无需平台 actual。 */
    private fun now(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

    private val parser = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun parseJsonObject(raw: String): JsonObject = parser.parseToJsonElement(raw).jsonObject
}

/** BackendProvider 轻量引用（用于容灾遍历，避免直接强依赖实现类）。 */
typealias BackendProviderRef = cp.player.kmp.provider.BackendProvider

/** 不可变 JsonObject 添加一个键的便捷扩展。 */
private fun JsonObject.mutAdd(key: String, value: String): JsonObject {
    val map = this.toMutableMap()
    map[key] = JsonPrimitive(value)
    return JsonObject(map)
}