package cp.player.core.provider

import cp.player.core.api.MusicApiMethod
import cp.player.core.util.PlatformContext
import cp.player.core.util.createHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 咪咕音乐内置音源（`type = "internal"`）。
 *
 * 直接调用咪咕官方公开 HTTP 接口（与 [migu-api-enhanced](https://github.com/Domdkw/miguMusic-api-enhanced)
 * 同源的一套端点），并把响应重塑成 CPPlayer 的网易云风格标准形状，
 * 因此**不需要任何本地服务进程 / native 库 / 二进制文件**。
 *
 * ### 为什么是内置而不是 binary/jni 模块
 * 本类位于 `commonMain`，最终以 JVM 字节码随 APK / 桌面包分发：
 * - Android：`armeabi-v7a`(armv7) / `arm64-v8a`(armv8)
 * - Linux / Windows：`amd64`(x86_64) / arm64 / armv7
 * 一条实现覆盖全部目标，不存在「按 ABI 挑二进制」的加载失败面。
 *
 * ### 字段兼容口径
 * 曲目对象按 [cp.player.core.music.TrackJsonMapper] 的口径输出
 * （`id` / `name` / `ar[]` / `al{picUrl}` / `dt`），解析侧无需为本音源开特例。
 */
class MiguProvider(
    override val id: String = DEFAULT_ID,
    override val name: String = DEFAULT_NAME,
    override val version: String = DEFAULT_VERSION,
    override val apiMap: Map<String, String>? = null,
    override val updateUrl: String? = null,
    override val targetAppPackage: String? = DEFAULT_TARGET_APP_PACKAGE,
) : BackendProvider {

    override val type: ProviderType = ProviderType.INTERNAL

    private val client = createHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun startServer(context: PlatformContext, port: Int) = Unit

    override fun stopServer() = Unit

    override fun isReady(): Boolean = true

    /**
     * [BackendProvider.callApi] 是同步契约；真正 IO 在 [runBlocking] 内跑，
     * 调用方（[ProviderManager]）已把它切到 `Dispatchers.IO`。
     */
    override fun callApi(method: String, params: Map<String, String>): String = runBlocking {
        runCatching { dispatch(method, params).toString() }
            .getOrElse { errorJson("咪咕请求失败: ${it.message ?: "未知错误"}").toString() }
    }

    override fun analyzeAudio(path: String): String =
        """{"code":-1,"msg":"咪咕音源不支持音频分析"}"""

    // ======================== 方法分发 ========================

    private suspend fun dispatch(method: String, params: Map<String, String>): JsonElement = when (method) {
        MusicApiMethod.SEARCH_CLOUD -> search(params)
        MusicApiMethod.SEARCH_SUGGEST -> searchSuggest(params)
        MusicApiMethod.SEARCH_HOT_DETAIL -> hotSearch()
        MusicApiMethod.SONG_DETAIL -> songDetail(params)
        MusicApiMethod.SONG_URL_V1,
        MusicApiMethod.SONG_URL_V1_302,
        MusicApiMethod.SONG_DOWNLOAD_URL -> songUrl(params)
        MusicApiMethod.LYRIC_NEW -> lyric(params)
        MusicApiMethod.PLAYLIST_DETAIL -> playlistDetail(params)
        MusicApiMethod.PLAYLIST_TRACK_ALL -> playlistTracks(params)
        MusicApiMethod.ALBUM_DETAIL -> albumDetail(params)
        MusicApiMethod.ARTIST_DETAIL -> artistDetail(params)
        MusicApiMethod.ARTIST_SONGS -> artistSongs(params)
        MusicApiMethod.ARTIST_ALBUM -> artistAlbums(params)
        MusicApiMethod.TOPLIST -> toplist()
        MusicApiMethod.TOPLIST_DETAIL -> toplistDetail()
        MusicApiMethod.USER_RECOMMEND_RESOURCE -> recommendPlaylists()
        MusicApiMethod.USER_RECOMMEND_SONGS -> recommendSongs()
        MusicApiMethod.PERSONAL_FM -> personalFm()
        else -> unsupported(method)
    }

    // ======================== 搜索 ========================

    private suspend fun search(params: Map<String, String>): JsonElement {
        val keywords = params["keywords"]?.trim().orEmpty()
        if (keywords.isEmpty()) return emptySearch()
        val type = params["type"]?.toIntOrNull() ?: MusicApiMethod.SEARCH_TYPE_SONG
        return when (type) {
            MusicApiMethod.SEARCH_TYPE_ALBUM -> albumSearch(keywords)
            MusicApiMethod.SEARCH_TYPE_ARTIST -> artistSearch(keywords)
            MusicApiMethod.SEARCH_TYPE_PLAYLIST -> playlistSearch(keywords)
            else -> songSearch(keywords)
        }
    }

    private suspend fun songSearch(keywords: String): JsonElement {
        val root = getJson("$BASE_APP/bmw/search/song/v1.0?pageNo=1&text=${enc(keywords)}")
        val songs = root.itemsOf("song").mapNotNull { it.obj("song") }.map { songToTrack(it) }
        return buildSearchResult(songs = songs)
    }

    private suspend fun albumSearch(keywords: String): JsonElement {
        val root = getJson("$BASE_APP/bmw/search/album/v1.0?pageNo=1&text=${enc(keywords)}&typeOrder=0")
        val albums = root.items().mapNotNull { item ->
            // 搜索结果混排普通专辑（`album`，带 albumId）与数字专辑（`dalbum`，只有 contentId）
            val src = item.obj("album") ?: item.obj("dalbum") ?: return@mapNotNull null
            val id = src.str("albumId") ?: src.str("contentId") ?: return@mapNotNull null
            albumSummary(
                id = id,
                name = src.str("title") ?: return@mapNotNull null,
                cover = normalizeImg(src.imgFromItems() ?: src.str("img")),
                artistName = src.str("singer"),
                artistId = src.str("singerId"),
                trackCount = src.str("totalCount")?.toIntOrNull(),
            )
        }
        return buildSearchResult(albums = albums)
    }

    private suspend fun artistSearch(keywords: String): JsonElement {
        val root = getJson("$BASE_APP/bmw/search/singer/v2.0?pageNo=1&text=${enc(keywords)}")
        val artists = root.itemsOf("singer").mapNotNull { item ->
            val src = item.obj("singer") ?: return@mapNotNull null
            val id = src.str("singerId") ?: src.str("id") ?: return@mapNotNull null
            buildJsonObject {
                put("id", id.toLongOrNull() ?: 0L)
                put("name", src.str("singer") ?: src.str("name") ?: "")
                // 解析侧按 img1v1Url → picUrl → avatarUrl 取头像
                put("picUrl", normalizeImg(src.imgFromItems() ?: src.str("img")) ?: "")
                put("albumSize", src.str("albumNum")?.toIntOrNull() ?: 0)
                put("musicSize", src.str("songNum")?.toIntOrNull() ?: 0)
            }
        }
        return buildSearchResult(artists = artists)
    }

    private suspend fun playlistSearch(keywords: String): JsonElement {
        val root = getJson("$BASE_APP/bmw/search/music-list/v1.0?pageNo=1&text=${enc(keywords)}&typeOrder=0")
        val playlists = root.itemsOf("musicList").mapNotNull { item ->
            val src = item.obj("musicList") ?: return@mapNotNull null
            val id = src.str("musicListId") ?: return@mapNotNull null
            playlistSummary(
                id = id,
                name = src.str("title") ?: "",
                cover = normalizeImg(src.obj("imgItem")?.str("img") ?: src.str("originalImgUrl")),
                trackCount = src.str("musicNum")?.toIntOrNull() ?: 0,
                creatorName = src.str("ownerName"),
            )
        }
        return buildSearchResult(playlists = playlists)
    }

    private suspend fun searchSuggest(params: Map<String, String>): JsonElement {
        val keywords = params["keywords"]?.trim().orEmpty()
        if (keywords.isEmpty()) return buildJsonObject { put("code", 200); put("result", buildJsonObject {}) }
        val root = getJson("$BASE_APP/bmw/search/suggest/v1.0?text=${enc(keywords)}")
        val words = root.items().mapNotNull { item ->
            val text = item.obj("text")
            (text?.str("text") ?: item.str("keyword") ?: item.str("word") ?: item.str("name"))
                ?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct().take(MAX_SUGGESTIONS)
        return buildJsonObject {
            put("code", 200)
            put("result", buildJsonObject {
                put("allMatch", buildJsonArray {
                    words.forEach { add(buildJsonObject { put("keyword", it) }) }
                })
            })
        }
    }

    private suspend fun hotSearch(): JsonElement {
        val root = getJson("$BASE_U/bmw/hot-search/search-rank-list/v1.0")
        val words = (root?.get("data") as? JsonArray).orEmpty()
            .flatMap { tab -> (tab.asObject()?.get("searchRankList") as? JsonArray).orEmpty() }
            .mapNotNull { entry ->
                val obj = entry.asObject() ?: return@mapNotNull null
                val word = obj.str("word")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                buildJsonObject {
                    put("searchWord", word)
                    put("content", obj.str("note") ?: "")
                    put("score", obj.str("note") ?: "")
                }
            }.distinctBy { it.str("searchWord").orEmpty() }.take(MAX_HOT_SEARCHES)
        return buildJsonObject {
            put("code", 200)
            put("data", buildJsonArray { words.forEach { add(it) } })
        }
    }

    // ======================== 歌曲 ========================

    private suspend fun songDetail(params: Map<String, String>): JsonElement {
        val ids = params["ids"].orEmpty().split(',', '|').map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return errorJson("song/detail: ids 为空")
        // 咪咕用 `|` 分隔多个 contentId
        val root = getJson("$BASE_APP/resource/song/by-contentids/v2.0?contentId=${enc(ids.joinToString("|"))}")
        val songs = (root?.get("data") as? JsonArray).orEmpty()
            .mapNotNull { it.asObject()?.let { obj -> songToTrack(obj) } }
            .filter { it.str("id").orEmpty().isNotEmpty() }
        return buildJsonObject {
            put("code", 200)
            put("songs", buildJsonArray { songs.forEach { add(it) } })
        }
    }

    private suspend fun songUrl(params: Map<String, String>): JsonElement {
        val id = params["id"].orEmpty().trim()
        if (id.isEmpty()) return errorJson("song/url: id 为空")
        val wanted = toneFlagOf(params["level"])
        var data = fetchListen(id, wanted)
        if (data?.str("url").isNullOrEmpty() && wanted != TONE_PQ) {
            // 高品质（HQ/SQ…）多数需要登录；拿不到就回落到标清，宁可低码率也不要播不了
            data = fetchListen(id, TONE_PQ) ?: data
        }
        val url = data?.str("url")?.takeIf { it.startsWith("http") }
            ?: return errorJson("咪咕未返回播放地址（版权受限或需登录）")
        val format = data.str("audioFormatType") ?: wanted
        val size = data.obj("song")?.arr("audioFormats")
            ?.firstOrNull { it.asObject()?.str("formatType") == format }
            ?.asObject()?.str("asize")?.toLongOrNull()
        val sizeValue = size ?: 0L
        return buildJsonObject {
            put("code", 200)
            put("level", format)
            put("size", JsonPrimitive(sizeValue))
            put("data", buildJsonArray {
                add(buildJsonObject {
                    put("id", id)
                    put("url", url)
                    put("br", JsonPrimitive(brOf(format)))
                    put("size", JsonPrimitive(sizeValue))
                    put("type", formatExt(format))
                    put("level", format)
                    put("md5", "")
                })
            })
        }
    }

    /** 拉取播放地址。`data` 缺失 / `cannotCode` 时返回 null，交由调用方回落。 */
    private suspend fun fetchListen(contentId: String, toneFlag: String): JsonObject? {
        val url = "$BASE_APP/MIGUM3.0/strategy/pc/listen/v1.0" +
            "?contentId=${enc(contentId)}&copyrightId=&resourceType=2&toneFlag=$toneFlag"
        val root = getJson(url, mapOf("Channel" to CHANNEL))
        if (!root.isOk()) return null
        val data = root?.obj("data")
        if (data?.str("url").isNullOrEmpty()) return null
        return data
    }

    private suspend fun lyric(params: Map<String, String>): JsonElement {
        val id = params["id"].orEmpty().trim()
        if (id.isEmpty()) return errorJson("lyric/new: id 为空")
        val root = getJson("$BASE_U/MIGUM2.0/v1.0/content/resourceinfo.do?resourceId=${enc(id)}&resourceType=2")
        val res = (root?.get("resource") as? JsonArray)?.firstOrNull()?.asObject()
        val lrc = res?.str("lrcUrl")?.takeIf { it.startsWith("http") }?.let { getText(it) }.orEmpty()
        val trc = res?.str("trcUrl")?.takeIf { it.startsWith("http") }?.let { getText(it) }.orEmpty()
        return buildJsonObject {
            put("code", 200)
            put("lrc", buildJsonObject { put("lyric", lrc) })
            put("tlyric", buildJsonObject { put("lyric", trc) })
            put("yrc", buildJsonObject { put("lyric", "") })
            put("sgc", false)
            put("sfy", false)
            put("qfy", false)
        }
    }

    // ======================== 歌单 ========================

    private suspend fun playlistDetail(params: Map<String, String>): JsonElement {
        val id = params["id"].orEmpty().trim()
        if (id.isEmpty()) return errorJson("playlist/detail: id 为空")
        val data = getJson("$BASE_APP/resource/playlist/v2.0?playlistId=${enc(id)}")?.obj("data")
        val tracks = fetchPlaylistSongs(id, page = 1, size = DEFAULT_PAGE_SIZE)
        val summary = playlistSummary(
            id = id,
            name = data?.str("title") ?: "",
            cover = normalizeImg(data?.obj("imgItem")?.str("img") ?: data?.str("originalImgUrl")),
            trackCount = data?.str("musicNum")?.toIntOrNull() ?: tracks.size,
            creatorName = data?.str("ownerName"),
        )
        return buildJsonObject {
            put("code", 200)
            put("playlist", JsonObject(summary + mapOf(
                "description" to JsonPrimitive(data?.str("summary") ?: ""),
                "tracks" to JsonArray(tracks),
            )))
        }
    }

    private suspend fun playlistTracks(params: Map<String, String>): JsonElement {
        val id = params["id"].orEmpty().trim()
        if (id.isEmpty()) return errorJson("playlist/track/all: id 为空")
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
        val offset = params["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = offset / limit + 1
        val root = getJson(
            "$BASE_APP/MIGUM3.0/resource/playlist/song/v2.0?pageNo=$page&pageSize=$limit&playlistId=${enc(id)}"
        )
        val data = root?.obj("data")
        val songs = data?.arr("songList").orEmpty()
            .mapNotNull { it.asObject()?.let { obj -> songToTrack(obj) } }
            .filter { it.str("id").orEmpty().isNotEmpty() }
        val total = data?.str("totalCount")?.toIntOrNull() ?: 0
        val more = data?.str("hasNext")?.toBoolean() ?: (total > offset + songs.size)
        return buildJsonObject {
            put("code", 200)
            put("songs", buildJsonArray { songs.forEach { add(it) } })
            put("more", more)
            put("hasMore", more)
        }
    }

    private suspend fun fetchPlaylistSongs(id: String, page: Int, size: Int): List<JsonObject> {
        val root = getJson(
            "$BASE_APP/MIGUM3.0/resource/playlist/song/v2.0?pageNo=$page&pageSize=$size&playlistId=${enc(id)}"
        )
        return root?.obj("data")?.arr("songList").orEmpty()
            .mapNotNull { it.asObject()?.let { obj -> songToTrack(obj) } }
            .filter { it.str("id").orEmpty().isNotEmpty() }
    }

    // ======================== 专辑 ========================

    private suspend fun albumDetail(params: Map<String, String>): JsonElement {
        val id = params["id"].orEmpty().trim()
        if (id.isEmpty()) return errorJson("album: id 为空")
        val data = getJson("$BASE_APP/MIGUM3.0/resource/album/v2.0?albumId=${enc(id)}")?.obj("data")
        val songs = getJson("$BASE_APP/MIGUM3.0/resource/album/song/v2.0?albumId=${enc(id)}&pageNo=1")
            ?.obj("data")?.arr("songList").orEmpty()
            .mapNotNull { it.asObject()?.let { obj -> songToTrack(obj) } }
            .filter { it.str("id").orEmpty().isNotEmpty() }
        val album = buildJsonObject {
            put("id", id.toLongOrNull() ?: 0L)
            put("name", data?.str("title") ?: "")
            put("picUrl", normalizeImg(data?.imgFromItems()) ?: "")
            put("artist", buildJsonObject {
                put("id", data?.str("singerId")?.toLongOrNull() ?: 0L)
                put("name", data?.str("singer") ?: "")
            })
            put("size", data?.str("totalCount")?.toIntOrNull() ?: songs.size)
            put("company", data?.str("publishCorp") ?: "")
            put("description", data?.str("summary") ?: "")
            put("songs", buildJsonArray { songs.forEach { add(it) } })
        }
        return buildJsonObject {
            put("code", 200)
            put("album", album)
            put("songs", buildJsonArray { songs.forEach { add(it) } })
        }
    }

    // ======================== 歌手 ========================

    private suspend fun artistDetail(params: Map<String, String>): JsonElement {
        val id = params["id"].orEmpty().trim()
        if (id.isEmpty()) return errorJson("artist/detail: id 为空")
        // 歌手主页接口只给简介与相似歌手，名字/头像从「歌手歌曲」首条反查
        val firstSong = getJson("$BASE_APP/bmw/singer/song/v1.0?pageNo=1&singerId=${enc(id)}&type=1")
            .walkItems().firstOrNull()
        val summary = getJson("$BASE_APP/bmw/singer/index/v1.0?singerId=${enc(id)}")
            .walkItems()
            .firstOrNull { it.str("view") == "ZJ-Singer-Intro-Item" }
            ?.str("txt2")
        val artist = buildJsonObject {
            put("id", id.toLongOrNull() ?: 0L)
            put("name", firstSong?.str("txt2") ?: "")
            put("cover", normalizeImg(firstSong?.str("img")) ?: "")
            put("picUrl", normalizeImg(firstSong?.str("img")) ?: "")
            put("briefDesc", summary ?: "")
            put("alias", buildJsonArray {})
            put("albumSize", 0)
            put("musicSize", 0)
        }
        return buildJsonObject {
            put("code", 200)
            put("data", buildJsonObject {
                put("artist", artist)
                put("user", buildJsonObject { put("followeds", 0) })
            })
        }
    }

    private suspend fun artistSongs(params: Map<String, String>): JsonElement {
        val id = params["id"].orEmpty().trim()
        if (id.isEmpty()) return errorJson("artist/songs: id 为空")
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, MAX_ARTIST_SONGS) ?: DEFAULT_PAGE_SIZE
        val songs = mutableListOf<JsonObject>()
        for (page in 1..MAX_ARTIST_PAGES) {
            val items = getJson("$BASE_APP/bmw/singer/song/v1.0?pageNo=$page&singerId=${enc(id)}&type=1")
                .walkItems()
                .mapNotNull { item ->
                    when {
                        item.obj("songItem") != null -> songToTrack(item.obj("songItem")!!)
                        item.obj("song") != null -> songToTrack(item.obj("song")!!)
                        else -> genericToTrack(item)
                    }
                }
            if (items.isEmpty()) break
            songs += items
            if (songs.size >= limit) break
        }
        val result = songs.distinctBy { it.str("id").orEmpty() }.take(limit)
        return buildJsonObject {
            put("code", 200)
            put("songs", buildJsonArray { result.forEach { add(it) } })
            put("more", false)
        }
    }

    private suspend fun artistAlbums(params: Map<String, String>): JsonElement {
        val id = params["id"].orEmpty().trim()
        if (id.isEmpty()) return errorJson("artist/album: id 为空")
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
        val albums = getJson("$BASE_APP/bmw/singer/album/v1.0?pageNo=1&singerId=${enc(id)}")
            .walkItems()
            .mapNotNull { item ->
                val albumId = item.str("resId") ?: return@mapNotNull null
                albumSummary(
                    id = albumId,
                    name = item.str("txt") ?: return@mapNotNull null,
                    cover = normalizeImg(item.str("img")),
                    artistName = item.str("txt2"),
                    artistId = id,
                )
            }.take(limit)
        return buildJsonObject {
            put("code", 200)
            put("hotAlbums", buildJsonArray { albums.forEach { add(it) } })
        }
    }

    // ======================== 排行榜 ========================

    private suspend fun toplist(): JsonElement {
        val items = getJson("$BASE_APP/pc/bmw/rank/rank-index/v1.0")
            ?.obj("data")?.arr("contents").orEmpty()
            .flatMap { group ->
                (group.asObject()?.arr("contents")).orEmpty()
                    .mapNotNull { it.asObject() }
                    .flatMap { rank -> rankItemToRanking(rank) }
            }
        return buildJsonObject {
            put("code", 200)
            put("list", buildJsonArray { items.forEach { add(it) } })
        }
    }

    /**
     * 榜单详情：取排行榜首页的第一个榜单展开（`MusicApiServiceImpl.getToplistDetail`
     * 不带 id 参数，这里按「首个榜单」语义返回一页曲目）。
     */
    private suspend fun toplistDetail(): JsonElement {
        val first = getJson("$BASE_APP/pc/bmw/rank/rank-index/v1.0")
            ?.obj("data")?.arr("contents").orEmpty()
            .asSequence()
            .flatMap { (it.asObject()?.arr("contents")).orEmpty().asSequence() }
            .mapNotNull { it.asObject() }
            .firstOrNull { it.str("rankId") != null } ?: return toplist()
        val rankId = first.str("rankId") ?: return toplist()
        val info = getJson("$BASE_APP/bmw/rank/rank-info/v1.0?pageNo=1&rankId=${enc(rankId)}")?.obj("data")
        val tracks = info?.arr("contents").orEmpty().mapNotNull { genericToTrack(it.asObject() ?: return@mapNotNull null) }
        val entry = rankingSummary(
            id = rankId,
            name = info?.str("title") ?: first.str("rankName") ?: "",
            cover = normalizeImg(info?.str("titlePic") ?: first.str("imageUrl")),
            trackCount = info?.str("totalCount")?.toIntOrNull() ?: tracks.size,
        )
        return buildJsonObject {
            put("code", 200)
            put("list", buildJsonArray {
                add(JsonObject(entry + mapOf("tracks" to JsonArray(tracks))))
            })
        }
    }

    // ======================== 推荐 / FM ========================

    private suspend fun recommendPlaylists(): JsonElement {
        val items = getJson("$BASE_APP/bmw/index-show/recommend-playlist/v3.0")
            ?.obj("data")?.arr("playLists").orEmpty()
            .mapNotNull { it.asObject() }
            .filter { it.str("resType") == RES_TYPE_PLAYLIST }
            .mapNotNull { item ->
                val id = item.str("resId") ?: return@mapNotNull null
                playlistSummary(
                    id = id,
                    name = item.str("txt") ?: "",
                    cover = normalizeImg(item.str("img")),
                    trackCount = 0,
                    creatorName = null,
                )
            }
        return buildJsonObject {
            put("code", 200)
            put("recommend", buildJsonArray { items.forEach { add(it) } })
            put("result", buildJsonArray { items.forEach { add(it) } })
        }
    }

    private suspend fun recommendSongs(): JsonElement {
        val songs = fetchSceneSongs(scene = "TODAY_RECOMMEND", size = DEFAULT_PAGE_SIZE)
        return buildJsonObject {
            put("code", 200)
            put("data", buildJsonObject {
                put("dailySongs", buildJsonArray { songs.forEach { add(it) } })
                put("songs", buildJsonArray { songs.forEach { add(it) } })
            })
        }
    }

    private suspend fun personalFm(): JsonElement {
        // PRIVATE_FM 固定返回 5 条且不接受 size 参数
        val songs = fetchSceneSongs(scene = "PRIVATE_FM", size = null)
        return buildJsonObject {
            put("code", 200)
            put("data", buildJsonArray { songs.forEach { add(it) } })
        }
    }

    private suspend fun fetchSceneSongs(scene: String, size: Int?): List<JsonObject> {
        val query = if (size == null) "scene=$scene&algorithm=v1&action=1"
        else "scene=$scene&action=1&size=$size"
        val root = getJson(
            "$BASE_APP/pc/resource-dataloader/recommend-song/v1.0?$query",
            mapOf("deviceId" to randomDeviceId()),
        )
        return root?.obj("data")?.arr("songItemList").orEmpty()
            .mapNotNull { it.asObject()?.let { obj -> songToTrack(obj) } }
            .filter { it.str("id").orEmpty().isNotEmpty() }
    }

    // ======================== 模型构造 ========================

    /** 咪咕歌曲对象 → CPPlayer 曲目形状（`id/name/ar[]/al{picUrl}/dt`）。 */
    private fun songToTrack(src: JsonObject): JsonObject {
        val id = src.str("contentId") ?: src.str("songId") ?: src.str("id") ?: ""
        val name = src.str("songName") ?: src.str("name") ?: ""
        val durationSec = src.str("duration")?.toLongOrNull() ?: 0L
        val artists = src.arr("singerList").orEmpty().mapNotNull { singer ->
            val obj = singer.asObject() ?: return@mapNotNull null
            val singerName = obj.str("name") ?: return@mapNotNull null
            buildJsonObject {
                put("id", obj.str("id")?.toLongOrNull() ?: 0L)
                put("name", singerName)
            }
        }.ifEmpty {
            val single = src.str("singer") ?: src.str("artist") ?: ""
            if (single.isEmpty()) emptyList() else listOf(buildJsonObject {
                put("id", src.str("singerId")?.toLongOrNull() ?: 0L)
                put("name", single)
            })
        }
        return buildJsonObject {
            put("id", id)
            put("name", name)
            put("dt", JsonPrimitive(durationSec * 1000L))
            put("ar", JsonArray(artists))
            put("al", buildJsonObject {
                put("id", src.str("albumId")?.toLongOrNull() ?: 0L)
                put("name", src.str("album") ?: src.str("albumName") ?: "")
                put("picUrl", normalizeImg(src.str("img1") ?: src.str("img2") ?: src.str("img3")
                    ?: src.imgFromItems() ?: src.str("img")) ?: "")
            })
        }
    }

    /** 栏目型条目（`txt/txt2/resId/img`，见于歌手歌曲、榜单、推荐）→ 曲目形状。 */
    private fun genericToTrack(item: JsonObject): JsonObject? {
        val id = item.str("resId") ?: item.str("contentId") ?: return null
        val name = item.str("txt") ?: item.str("songName") ?: return null
        // songData 是内嵌的 JSON 字符串，里面才有专辑名/时长
        val embedded = item.str("songData")?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }?.asObject()
        val durationSec = embedded?.str("duration")?.toLongOrNull()
            ?: item.str("duration")?.toLongOrNull() ?: 0L
        val artistName = item.str("txt2") ?: embedded?.str("singer") ?: ""
        return buildJsonObject {
            put("id", id)
            put("name", name)
            put("dt", JsonPrimitive(durationSec * 1000L))
            put("ar", buildJsonArray {
                add(buildJsonObject { put("id", 0L); put("name", artistName) })
            })
            put("al", buildJsonObject {
                put("id", embedded?.str("albumId")?.toLongOrNull() ?: 0L)
                put("name", item.str("txt3") ?: embedded?.str("album") ?: "")
                put("picUrl", normalizeImg(item.str("img") ?: embedded?.str("img1")) ?: "")
            })
        }
    }

    private fun playlistSummary(
        id: String,
        name: String,
        cover: String?,
        trackCount: Int,
        creatorName: String?,
    ): JsonObject = buildJsonObject {
        put("id", id.toLongOrNull() ?: 0L)
        put("name", name)
        put("picUrl", cover ?: "")
        put("coverImgUrl", cover ?: "")
        put("trackCount", trackCount)
        put("creator", buildJsonObject { put("nickname", creatorName ?: "") })
    }

    private fun albumSummary(
        id: String,
        name: String,
        cover: String?,
        artistName: String?,
        artistId: String? = null,
        trackCount: Int? = null,
    ): JsonObject = buildJsonObject {
        put("id", id.toLongOrNull() ?: 0L)
        put("name", name)
        put("picUrl", cover ?: "")
        put("size", trackCount ?: 0)
        put("artist", buildJsonObject {
            put("id", artistId?.toLongOrNull() ?: 0L)
            put("name", artistName ?: "")
        })
        put("artists", buildJsonArray {
            add(buildJsonObject {
                put("id", artistId?.toLongOrNull() ?: 0L)
                put("name", artistName ?: "")
            })
        })
    }

    private fun rankingSummary(id: String, name: String, cover: String?, trackCount: Int): JsonObject =
        buildJsonObject {
            put("id", id.toLongOrNull() ?: 0L)
            put("name", name)
            put("coverImgUrl", cover ?: "")
            put("picUrl", cover ?: "")
            put("trackCount", trackCount)
            put("updateFrequency", "")
        }

    /** 榜单组里既有纯榜单条目，也有「榜单 + 前几首」的复合条目，两种都要展开。 */
    private fun rankItemToRanking(rank: JsonObject): List<JsonObject> {
        val rankId = rank.str("rankId") ?: return emptyList()
        val tracks = rank.arr("contents").orEmpty()
        return listOf(rankingSummary(
            id = rankId,
            name = rank.str("rankName") ?: "",
            cover = normalizeImg(rank.str("imageUrl")),
            trackCount = tracks.size,
        ))
    }

    private fun buildSearchResult(
        songs: List<JsonObject> = emptyList(),
        albums: List<JsonObject> = emptyList(),
        artists: List<JsonObject> = emptyList(),
        playlists: List<JsonObject> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("code", 200)
        put("result", buildJsonObject {
            put("songs", JsonArray(songs))
            put("albums", JsonArray(albums))
            put("artists", JsonArray(artists))
            put("playlists", JsonArray(playlists))
            put("songCount", songs.size)
        })
    }

    private fun emptySearch(): JsonObject = buildSearchResult()

    private fun unsupported(method: String): JsonObject = buildJsonObject {
        put("code", -1)
        put("msg", "咪咕音源不支持该接口: $method")
    }

    private fun errorJson(message: String): JsonObject = buildJsonObject {
        put("code", 500)
        put("msg", message)
    }

    // ======================== 网络 ========================

    private suspend fun getJson(url: String, extraHeaders: Map<String, String> = emptyMap()): JsonObject? {
        val text = runCatching {
            client.get(url) {
                header("User-Agent", USER_AGENT)
                header("Channel", CHANNEL)
                header("Referer", "https://music.migu.cn/")
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }.bodyAsText()
        }.getOrElse { return null }
        return runCatching { json.parseToJsonElement(text) }.getOrNull()?.asObject()
    }

    private suspend fun getText(url: String): String? = runCatching {
        client.get(url) { header("User-Agent", USER_AGENT) }.bodyAsText()
    }.getOrNull()

    companion object {
        /** manifest 里声明内置咪咕音源所用的 id。 */
        const val DEFAULT_ID = "migu"
        const val DEFAULT_NAME = "咪咕音乐"
        const val DEFAULT_VERSION = "1.0.0"
        const val DEFAULT_TARGET_APP_PACKAGE = "cmccwm.mobilemusic"

        const val BASE_APP = "https://app.c.nf.migu.cn"
        const val BASE_U = "https://app.u.nf.migu.cn"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val CHANNEL = "014X031"
        const val RES_TYPE_PLAYLIST = "2021"

        const val DEFAULT_PAGE_SIZE = 30
        const val MAX_PAGE_SIZE = 100
        const val MAX_ARTIST_SONGS = 150
        const val MAX_ARTIST_PAGES = 3
        const val MAX_SUGGESTIONS = 10
        const val MAX_HOT_SEARCHES = 20
    }
}

// ======================== 常量（文件级，供扩展函数共用） ========================

private const val IMG_BASE = "https://d.musicapp.migu.cn"

/** 默认音质：咪咕未登录时只有标清可用，高品质拿不到地址时回落到这里。 */
private const val TONE_PQ = "PQ"

// ======================== JsonElement 取值扩展（本文件私有） ========================

private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** 咪咕把「图集」放在 `imgItems` / `imgs` / `albumImgs` 三种键里，取第一张。 */
private fun JsonObject.imgFromItems(): String? {
    for (key in listOf("imgItems", "imgs", "albumImgs", "singerImgs")) {
        val first = arr(key)?.firstOrNull()?.asObject()
        val img = first?.str("img")
        if (!img.isNullOrEmpty()) return img
    }
    return null
}

/** 搜索响应统一是 `data.items[]`，每项包一层类型键（song/album/singer/...）。 */
private fun JsonObject?.items(): List<JsonObject> =
    (this?.obj("data")?.arr("items")).orEmpty().mapNotNull { it.asObject() }

/** 取 `data.items[]` 中包裹键为 [type] 的项（保留外层以便回退）。 */
private fun JsonObject?.itemsOf(type: String): List<JsonObject> =
    this.items().filter { it[type] != null }

/**
 * 咪咕栏目型接口（`singer/song`、`singer/index`、`singer/album`）统一是
 * `data.contents[].contents[]` 的两层结构，这里拍平成一维。
 */
private fun JsonObject?.walkItems(): List<JsonObject> {
    val groups = (this?.obj("data")?.arr("contents")).orEmpty()
    return groups.flatMap { group ->
        (group.asObject()?.arr("contents")).orEmpty().mapNotNull { it.asObject() }
    }
}

private fun JsonObject?.isOk(): Boolean {
    val code = this?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull }
    return code == "000000" || code == "200"
}

// ======================== 音质 ========================

private fun toneFlagOf(level: String?): String = when (level?.lowercase()) {
    "lq", "standard" -> "PQ"
    "higher", "exhigh" -> "HQ"
    "lossless" -> "SQ"
    "hires", "jymaster", "sky" -> "ZQ24"
    "dolby" -> "Z3D"
    else -> TONE_PQ
}

private fun brOf(format: String): Int = when (format) {
    "LQ" -> 64_000
    "PQ" -> 128_000
    "HQ" -> 320_000
    "SQ" -> 999_000
    "ZQ24" -> 2_304_000
    "ZQ32" -> 4_608_000
    else -> 128_000
}

private fun formatExt(format: String): String = when (format) {
    "SQ", "ZQ24" -> "flac"
    "ZQ32", "Z3D", "3D60" -> "wav"
    "I3D" -> "m4a"
    else -> "mp3"
}

// ======================== 编码 / 杂项 ========================

private const val HEX = "0123456789ABCDEF"

/** 咪咕的图片字段有时只给 `/data/oss/...` 相对路径，补上 CDN 域名。 */
private fun normalizeImg(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    return if (value.startsWith("http")) value else IMG_BASE + value
}

private fun urlEncode(value: String): String {
    val out = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val ch = code.toChar()
        if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
            out.append(ch)
        } else {
            out.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
        }
    }
    return out.toString()
}

private fun enc(value: String): String = urlEncode(value)

/** 咪咕 `recommend-song` 要求 deviceId；格式就是随机 UUID 字符串。 */
private fun randomDeviceId(): String = buildString(36) {
    val chars = "0123456789abcdef"
    repeat(36) { index ->
        when (index) {
            8, 13, 18, 23 -> append('-')
            14 -> append('4')
            else -> append(chars[kotlin.random.Random.nextInt(16)])
        }
    }
}
