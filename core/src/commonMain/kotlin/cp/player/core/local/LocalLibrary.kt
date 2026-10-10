package cp.player.core.local

import cp.player.core.media.LocalMediaItem
import cp.player.core.media.MediaType

/**
 * 本地曲库聚合出来的专辑。
 *
 * @param id 稳定身份哈希（见 [albumIdentityId]）——UI 用它做列表 key，
 *   也保证刷新后滚动位置与选中态不串
 * @param name 专辑名
 * @param artist 专辑艺人（合辑时为 `Various Artists` 一类；缺失取曲目的众数艺人）
 * @param year 发行年（取专辑内出现最多的非空年份）
 * @param songCount 曲目数
 * @param durationMs 总时长
 * @param coverUri 代表封面（取第一首有封面的曲目）
 * @param paths 专辑内曲目路径，已按「碟号 → 轨号 → 标题」排好序
 */
data class LocalAlbum(
    val id: Long,
    val name: String,
    val artist: String?,
    val year: Int?,
    val songCount: Int,
    val durationMs: Long,
    val coverUri: String?,
    val paths: List<String>,
)

/**
 * 本地曲库聚合出来的艺术家。
 *
 * @param name 艺术家名
 * @param songCount 曲目数
 * @param albumCount 专辑数
 * @param coverUri 代表封面
 */
data class LocalArtist(
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    val coverUri: String?,
)

/** 本地库排序方式。 */
enum class LocalLibrarySort(val label: String) {
    /** 按 A→Z（中文按 code point，与系统排序观感一致） */
    TITLE("标题"),

    /** 按艺术家 */
    ARTIST("艺术家"),

    /** 按专辑 */
    ALBUM("专辑"),

    /** 最近修改在前 */
    RECENT("最近修改"),

    /** 时长从长到短 */
    DURATION("时长"),

    /** 文件体积从大到小 */
    SIZE("文件大小"),
}

/** 专辑 / 艺术家网格的排序方式。 */
enum class LocalGridSort(val label: String) {
    NAME("名称"),
    COUNT("数量"),
    RECENT("最近修改"),
}

/**
 * 浏览期筛选开关（只影响当前视图，不改变索引）。
 *
 * 与 [LocalScanSettings] 的分工：那个决定「什么能进曲库」，这个决定
 * 「现在想看曲库里的哪一部分」。所以这里可以随意开关，切换不需要重扫。
 */
data class LocalLibraryFilter(
    /** 只看重复曲目（按标题 + 专辑判定） */
    val duplicatesOnly: Boolean = false,
    /** 只看无损（有位深信息即视为无损） */
    val losslessOnly: Boolean = false,
    /** 只看缺少封面的曲目 */
    val missingCoverOnly: Boolean = false,
    /** 只看标签残缺的曲目（缺艺术家 / 专辑 / 流派之一） */
    val missingTagsOnly: Boolean = false,
) {
    val isDefault: Boolean
        get() = !duplicatesOnly && !losslessOnly && !missingCoverOnly && !missingTagsOnly

    /** 生效中的筛选条数（UI 角标）。 */
    val activeCount: Int
        get() = (if (duplicatesOnly) 1 else 0) + (if (losslessOnly) 1 else 0) +
            (if (missingCoverOnly) 1 else 0) + (if (missingTagsOnly) 1 else 0)
}

/**
 * 本地曲库聚合器（commonMain 纯函数，无平台依赖）。
 *
 * 从扁平的 [LocalMediaItem] 列表派生出专辑 / 艺术家 / 流派视图 —— 这是把
 * 「一个文件列表」升级成「一个音乐库」的关键一步：没有聚合，本地页就只能
 * 平铺几千行曲目，用户根本没法浏览。
 */
object LocalLibraryAggregator {

    /** 无专辑名的曲目归到的兜底专辑名。 */
    const val UNKNOWN_ALBUM = "未知专辑"

    /** 无艺人的曲目归到的兜底艺术家名。 */
    const val UNKNOWN_ARTIST = "未知艺术家"

    /**
     * 专辑身份哈希：**专辑名 + 专辑艺人** 的 FNV-1a 64。
     *
     * 只用专辑名做身份会导致本地库最常见的聚合事故 —— 多张《Greatest Hits》
     * （不同艺人）被合并成一张几十首曲目的怪物专辑。加上专辑艺人后同名不同
     * 艺人的专辑各归各位；专辑艺人缺失时退化为纯专辑名哈希，行为与之前一致。
     */
    fun albumIdentityId(albumName: String, albumArtist: String?): Long {
        val key = "${albumName.trim().lowercase()}|${albumArtist?.trim()?.lowercase().orEmpty()}"
        var hash = 0xcbf29ce484222325uL.toLong() // FNV-1a 64 offset basis
        for (char in key) {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash and Long.MAX_VALUE
    }

    /** 曲库里的音频条目（视频不参与音乐库聚合）。 */
    fun audioOnly(items: List<LocalMediaItem>): List<LocalMediaItem> =
        items.filter { it.mediaType == MediaType.AUDIO }

    /**
     * 聚合专辑视图。
     *
     * @param sort 排序方式
     * @param query 过滤关键词（空串不过滤）；命中专辑名或专辑艺人
     */
    fun albums(
        items: List<LocalMediaItem>,
        sort: LocalGridSort = LocalGridSort.NAME,
        query: String = "",
    ): List<LocalAlbum> {
        val songs = audioOnly(items)
        val groups = LinkedHashMap<Long, MutableList<LocalMediaItem>>()
        for (song in songs) {
            val albumName = song.album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM
            val albumArtist = song.metadata?.albumArtist ?: song.artist
            val id = albumIdentityId(albumName, albumArtist)
            groups.getOrPut(id) { ArrayList() } += song
        }

        val keyword = query.trim().lowercase()
        return groups.mapNotNull { (id, tracks) ->
            val sortedTracks = tracks.sortedWith(trackOrderComparator())
            val first = sortedTracks.first()
            val albumName = first.album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM
            val artist = dominantAlbumArtist(tracks)
            if (keyword.isNotEmpty() &&
                !albumName.lowercase().contains(keyword) &&
                !(artist?.lowercase()?.contains(keyword) ?: false)
            ) return@mapNotNull null
            LocalAlbum(
                id = id,
                name = albumName,
                artist = artist,
                year = tracks.mapNotNull { it.metadata?.year }.groupBy { it }
                    .maxByOrNull { it.value.size }?.key,
                songCount = tracks.size,
                durationMs = tracks.sumOf { it.durationMs },
                coverUri = sortedTracks.firstNotNullOfOrNull { it.coverUri },
                paths = sortedTracks.map { it.path },
            )
        }.let { list ->
            when (sort) {
                LocalGridSort.NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                LocalGridSort.COUNT -> list.sortedByDescending { it.songCount }
                LocalGridSort.RECENT -> list.sortedByDescending { maxLastModified(it, songs) }
            }
        }
    }

    /** 聚合艺术家视图。 */
    fun artists(
        items: List<LocalMediaItem>,
        sort: LocalGridSort = LocalGridSort.NAME,
        query: String = "",
    ): List<LocalArtist> {
        val songs = audioOnly(items)
        val albumIdsByArtist = HashMap<String, MutableSet<Long>>()
        for (song in songs) {
            val artist = song.artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST
            val albumName = song.album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM
            albumIdsByArtist.getOrPut(artist) { HashSet() } +=
                albumIdentityId(albumName, song.metadata?.albumArtist ?: song.artist)
        }

        val keyword = query.trim().lowercase()
        return songs.groupBy { it.artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST }
            .map { (name, tracks) ->
                LocalArtist(
                    name = name,
                    songCount = tracks.size,
                    albumCount = albumIdsByArtist[name]?.size ?: 1,
                    coverUri = tracks.firstNotNullOfOrNull { it.coverUri },
                )
            }
            .filter { keyword.isEmpty() || it.name.lowercase().contains(keyword) }
            .let { list ->
                when (sort) {
                    LocalGridSort.NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    LocalGridSort.COUNT -> list.sortedByDescending { it.songCount }
                    // 艺术家没有自己的时间戳，取其曲目里最近的修改时间
                    LocalGridSort.RECENT -> list.sortedByDescending { artist ->
                        songs.filter { (it.artist?.takeIf { v -> v.isNotBlank() } ?: UNKNOWN_ARTIST) == artist.name }
                            .maxOfOrNull { it.lastModified } ?: 0L
                    }
                }
            }
    }

    /** 全部流派（按曲目数从多到少）。 */
    fun genres(items: List<LocalMediaItem>): List<Pair<String, Int>> =
        audioOnly(items)
            .mapNotNull { it.metadata?.genre?.takeIf { v -> v.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }

    /** 排序曲目列表。 */
    fun sortSongs(
        items: List<LocalMediaItem>,
        sort: LocalLibrarySort,
        descending: Boolean = false,
    ): List<LocalMediaItem> {
        val base: Comparator<LocalMediaItem> = when (sort) {
            LocalLibrarySort.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            LocalLibrarySort.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) {
                it.artist ?: UNKNOWN_ARTIST
            }
            LocalLibrarySort.ALBUM -> compareBy(String.CASE_INSENSITIVE_ORDER) {
                it.album ?: UNKNOWN_ALBUM
            }
            LocalLibrarySort.RECENT -> compareBy { it.lastModified }
            LocalLibrarySort.DURATION -> compareBy { it.durationMs }
            LocalLibrarySort.SIZE -> compareBy { it.sizeBytes }
        }
        // 主序相同时用标题兜底，保证排序稳定（否则每次刷新列表顺序都会跳）
        val comparator = base.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        return if (descending) items.sortedWith(comparator.reversed()) else items.sortedWith(comparator)
    }

    /**
     * 本地库搜索：命中标题 / 艺术家 / 专辑 / 文件名 / 流派 / 年份。
     *
     * 大小写不敏感，多个空格分隔的词需**全部**命中（AND 语义）——
     * 输入「周杰伦 七里香」能收敛到具体曲目，而不是匹配出一整个艺人目录。
     */
    fun search(items: List<LocalMediaItem>, query: String): List<LocalMediaItem> {
        val keywords = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (keywords.isEmpty()) return items
        return items.filter { item ->
            val haystack = buildString {
                append(item.title).append(' ')
                append(item.artist.orEmpty()).append(' ')
                append(item.album.orEmpty()).append(' ')
                append(item.metadata?.albumArtist.orEmpty()).append(' ')
                append(item.metadata?.genre.orEmpty()).append(' ')
                append(item.metadata?.year?.toString().orEmpty()).append(' ')
                append(item.path.substringAfterLast('/')).append(' ')
                append(item.path.substringAfterLast('\\'))
            }.lowercase()
            keywords.all { haystack.contains(it) }
        }
    }

    /**
     * 重复歌曲检测：按「标题 + 专辑」归一化分组，返回**所有落在重复组里**的曲目。
     *
     * 返回的是全部成员而不是「每组留一首」——用户在清理时往往要自己比较码率、
     * 文件大小来决定删哪个，只给一组里的残余项反而看不全上下文。
     *
     * 标题为空时不参与判定：一批解析失败的曲目标题都是空串，会被误判成
     * 一整组「重复」，那是噪音不是信号。
     */
    fun duplicates(items: List<LocalMediaItem>): List<LocalMediaItem> {
        val byKey = LinkedHashMap<String, MutableList<LocalMediaItem>>()
        for (song in audioOnly(items)) {
            val title = song.title.trim()
            if (title.isEmpty()) continue
            val key = "${title.lowercase()}|${song.album?.trim()?.lowercase().orEmpty()}"
            byKey.getOrPut(key) { ArrayList() } += song
        }
        return byKey.values
            .filter { it.size > 1 }
            .flatten()
            .sortedWith(
                compareBy<LocalMediaItem, String>(String.CASE_INSENSITIVE_ORDER) {
                    it.album.orEmpty()
                }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    .thenByDescending { it.sizeBytes },
            )
    }

    /**
     * 应用浏览期筛选（不改变索引，随时可切回）。
     *
     * 这几个开关合起来就是一份「曲库健康度」检查表：哪些是无损、哪些没封面、
     * 哪些标签残缺、哪些重复。本地曲库越大越需要它 —— 几千首里挑出问题曲目，
     * 靠人眼翻列表是不现实的。
     */
    fun applyFilter(items: List<LocalMediaItem>, filter: LocalLibraryFilter): List<LocalMediaItem> {
        if (filter.isDefault) return items
        var result = items
        if (filter.duplicatesOnly) {
            val keys = duplicates(result).mapTo(HashSet()) { it.path }
            result = result.filter { it.path in keys }
        }
        if (filter.losslessOnly) result = result.filter { it.isLossless }
        if (filter.missingCoverOnly) result = result.filter { it.coverUri.isNullOrBlank() }
        if (filter.missingTagsOnly) {
            result = result.filter {
                it.artist.isNullOrBlank() || it.album.isNullOrBlank() ||
                    it.metadata?.genre.isNullOrBlank()
            }
        }
        return result
    }

    /** 曲目在专辑内的自然顺序：碟号 → 轨号 → 标题。 */
    private fun trackOrderComparator(): Comparator<LocalMediaItem> =
        compareBy<LocalMediaItem> { it.metadata?.discNumber ?: Int.MAX_VALUE }
            .thenBy { it.metadata?.trackNumber ?: Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }

    /** 专辑艺人：优先取多数曲目共享的 albumArtist，缺失时退化为出现最多的 artist。 */
    private fun dominantAlbumArtist(tracks: List<LocalMediaItem>): String? {
        val fromTag = tracks.mapNotNull { it.metadata?.albumArtist?.takeIf { v -> v.isNotBlank() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        if (fromTag != null) return fromTag
        return tracks.mapNotNull { it.artist?.takeIf { v -> v.isNotBlank() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    private fun maxLastModified(album: LocalAlbum, songs: List<LocalMediaItem>): Long {
        val paths = album.paths.toSet()
        return songs.filter { it.path in paths }.maxOfOrNull { it.lastModified } ?: 0L
    }
}
