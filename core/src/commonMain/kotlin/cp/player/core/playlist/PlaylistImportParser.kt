package cp.player.core.playlist

/**
 * 外部歌单导入的一条待解析条目。
 *
 * 解析层**只认文本形状，不碰网络**：链接里的 id 直接抽出来，纯文本行拆成
 * 「歌名 + 歌手」留给上层搜索。这样这一层可以纯函数测试，也不会把「识别」和
 * 「网络请求」耦在一个失败点上（链接失效 / 搜索无结果要分开报）。
 */
sealed interface PlaylistImportEntry {
    /** 网易云歌单链接里的歌单 id（`playlist?id=` / `/playlist/{id}`）。 */
    data class PlaylistLink(val playlistId: Long) : PlaylistImportEntry

    /** 网易云单曲链接里的歌曲 id（`song?id=` / `/song/{id}`）。 */
    data class SongLink(val songId: String) : PlaylistImportEntry

    /**
     * 纯文本行：`歌名 - 歌手` / `歌名\t歌手` / `歌名，歌手`。
     *
     * [artist] 为 null 表示这一行只有歌名 —— 搜索时只按歌名匹配，取第一条结果。
     */
    data class TextQuery(val title: String, val artist: String?) : PlaylistImportEntry
}

/**
 * [PlaylistImportParser.parse] 的结果。
 *
 * @param entries 去重后的条目（歌单链接也作为一条留在里面，方便上层计数；
 *   真正的曲目由 [playlistLinkId] 指向的歌单详情提供）。
 * @param playlistLinkId 第一个歌单链接的 id；没有则为 null。
 * @param nameHint 文本里显式写出的歌单名（`歌单：xxx`），可作新建歌单的默认名。
 * @param ignoredLines 空行以外的无效行数（标题、分隔线、无法识别的链接等）。
 * @param unsupportedLinks 非网易云 / 暂不支持的链接条数（短链 `163cn.tv` 也算）。
 */
data class ParsedPlaylistImport(
    val entries: List<PlaylistImportEntry> = emptyList(),
    val playlistLinkId: Long? = null,
    val nameHint: String? = null,
    val ignoredLines: Int = 0,
    val unsupportedLinks: Int = 0,
) {
    val songLinkCount: Int get() = entries.count { it is PlaylistImportEntry.SongLink }
    val queryCount: Int get() = entries.count { it is PlaylistImportEntry.TextQuery }
    val isEmpty: Boolean get() = entries.isEmpty() && playlistLinkId == null
}

/**
 * 外部歌单文本 / 链接解析器。
 *
 * 支持三种输入混排（一段分享文案里通常同时出现「歌单名 + 链接」）：
 *
 * 1. **网易云歌单链接**：`music.163.com/playlist?id=123`、`music.163.com/#/playlist?id=123`、
 *    `y.music.163.com/m/playlist?id=123`、`music.163.com/playlist/123`；
 * 2. **网易云单曲链接**：`music.163.com/song?id=123`、`music.163.com/#/song?id=123`；
 * 3. **纯文本**：每行一首，`歌名 - 歌手`（也认 `–` / `—` / `|` / 制表符 / 中英文逗号），
 *    允许行首有 `1.` / `1、` / `-` / `•` 之类的列表符号。
 *
 * 明确**不处理**的：`163cn.tv` 短链（需要发一次 HTTP 跟随跳转，解析层不做网络）、
 * 非网易云链接（QQ / Spotify 等）。它们只计入 [ParsedPlaylistImport.unsupportedLinks]，
 * 上层据此提示用户换成完整链接。
 */
object PlaylistImportParser {

    /** 一行里可能出现的网易云 URL（允许省略协议头，分享文案里常见）。 */
    private val NETEASE_URL = Regex(
        """(?i)\b(?:https?://)?(?:y\.)?music\.163\.com/[^\s，,、；;）)】\]>]*""",
    )

    /** 短链：能认出是网易云，但这里不解析。 */
    private val SHORT_LINK = Regex("""(?i)\b(?:https?://)?163cn\.tv/[^\s，,、；;）)】\]>]*""")

    /** 任意 URL（用来区分「不支持的链接」与「纯文本」）。 */
    private val ANY_URL = Regex("""(?i)\bhttps?://[^\s]+""")

    /** `?id=123` / `&id=123` / `#id=123`；`userid=` 不会被匹配（前面不是 `?&#`）。 */
    private val ID_PARAM = Regex("""[?&#]id=(\d+)""")

    /** 路径式：`/playlist/123`、`/song/123`。 */
    private val PATH_ID = Regex("""/(playlist|song)/(\d+)""", RegexOption.IGNORE_CASE)

    private val LIST_PREFIX = Regex("""^\s*(?:\d{1,4}\s*[.)、．]\s*|[-*•·]\s+)""")

    private val NAME_HINT = Regex(
        """^(?:分享)?\s*(?:歌单|播放列表|歌单名|playlist)\s*[：:]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** 分享文案里常见的噪声行。 */
    private val NOISE = listOf(
        Regex("""^共\s*\d+\s*首.*$"""),
        Regex("""^\d+\s*首(歌曲)?$"""),
        Regex("""^[-=—–_*·\s]{3,}$"""),
        Regex("""^分享自.*$"""),
        Regex("""^来自.*$"""),
        Regex("""^复制.*打开.*$"""),
        Regex("""^点击.*(查看|收听).*$"""),
    )

    private val SEPARATORS = listOf("\t", " - ", " – ", " — ", " － ", " | ", "｜")

    fun parse(raw: String): ParsedPlaylistImport {
        if (raw.isBlank()) return ParsedPlaylistImport()

        val entries = mutableListOf<PlaylistImportEntry>()
        val seen = mutableSetOf<String>()
        var playlistLinkId: Long? = null
        var nameHint: String? = null
        var ignored = 0
        var unsupported = 0

        fun addPlaylist(id: Long) {
            if (seen.add("p:$id")) entries += PlaylistImportEntry.PlaylistLink(id)
            if (playlistLinkId == null) playlistLinkId = id
        }

        fun addSong(id: String) {
            if (seen.add("s:$id")) entries += PlaylistImportEntry.SongLink(id)
        }

        fun addQuery(query: PlaylistImportEntry.TextQuery) {
            val key = "q:${query.title.lowercase()}|${query.artist?.lowercase().orEmpty()}"
            if (seen.add(key)) entries += query
        }

        raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { original ->
            val line = LIST_PREFIX.replace(original.trim(), "").trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach

            val neteaseUrls = NETEASE_URL.findAll(line).toList()
            if (neteaseUrls.isNotEmpty()) {
                var matched = false
                neteaseUrls.forEach { match ->
                    val url = match.value.trimEnd('。', '，', ',', '.', '）', ')', '】', ']', '>', '"', '\'')
                    when (val parsed = parseNeteaseUrl(url)) {
                        is ParsedUrl.Playlist -> { addPlaylist(parsed.id); matched = true }
                        is ParsedUrl.Song -> { addSong(parsed.id); matched = true }
                        ParsedUrl.Unsupported -> Unit
                    }
                }
                if (matched) return@forEach
                unsupported++
                return@forEach
            }

            if (SHORT_LINK.containsMatchIn(line) || ANY_URL.containsMatchIn(line)) {
                unsupported++
                return@forEach
            }

            NAME_HINT.matchEntire(line)?.let { match ->
                val name = match.groupValues[1].trim().trim('"', '“', '”')
                if (name.isNotEmpty() && nameHint == null) nameHint = name
                return@forEach
            }

            if (NOISE.any { it.matches(line) }) {
                ignored++
                return@forEach
            }

            addQuery(parseTextLine(line))
        }

        return ParsedPlaylistImport(
            entries = entries,
            playlistLinkId = playlistLinkId,
            nameHint = nameHint,
            ignoredLines = ignored,
            unsupportedLinks = unsupported,
        )
    }

    /** 抽一行里的歌单 id（多个取第一个）；给只关心链接的调用方用。 */
    fun extractPlaylistId(text: String): Long? =
        NETEASE_URL.findAll(text).mapNotNull { (parseNeteaseUrl(it.value) as? ParsedUrl.Playlist)?.id }.firstOrNull()

    private sealed interface ParsedUrl {
        data class Playlist(val id: Long) : ParsedUrl
        data class Song(val id: String) : ParsedUrl
        data object Unsupported : ParsedUrl
    }

    private fun parseNeteaseUrl(rawUrl: String): ParsedUrl {
        val url = rawUrl.trimEnd('。', '，', ',', '.', '）', ')', '】', ']', '>', '"', '\'')
        val lower = url.lowercase()
        val pathMatch = PATH_ID.find(url)
        // 先看路径再看 query：`song?id=1&playlist=2` 这类链接里也带 playlist 字样，
        // 按关键字先判 playlist 会把单曲链接错当成歌单。
        val isPlaylist = lower.contains("/playlist") || lower.contains("playlist?")
        val isSong = lower.contains("/song") || lower.contains("song?")
        return when {
            isPlaylist -> {
                val id = ID_PARAM.find(url)?.groupValues?.get(1)
                    ?: pathMatch?.takeIf { it.groupValues[1].equals("playlist", true) }?.groupValues?.get(2)
                id?.toLongOrNull()?.let { ParsedUrl.Playlist(it) } ?: ParsedUrl.Unsupported
            }
            isSong -> {
                val id = ID_PARAM.find(url)?.groupValues?.get(1)
                    ?: pathMatch?.takeIf { it.groupValues[1].equals("song", true) }?.groupValues?.get(2)
                id?.takeIf { it.isNotBlank() }?.let { ParsedUrl.Song(it) } ?: ParsedUrl.Unsupported
            }
            else -> ParsedUrl.Unsupported
        }
    }

    private fun parseTextLine(line: String): PlaylistImportEntry.TextQuery {
        val text = line.trim().trim('"', '“', '”', '\'', '「', '」')
        for (sep in SEPARATORS) {
            val index = text.indexOf(sep)
            if (index > 0 && index < text.length - sep.length) {
                val title = text.substring(0, index).trim()
                val artist = text.substring(index + sep.length).trim()
                if (title.isNotEmpty()) {
                    return PlaylistImportEntry.TextQuery(title, artist.ifBlank { null })
                }
            }
        }
        val comma = text.indexOfFirst { it == ',' || it == '，' }
        if (comma in 1 until text.lastIndex) {
            val title = text.substring(0, comma).trim()
            val artist = text.substring(comma + 1).trim()
            // 逗号在歌名里比在「歌名，歌手」里少见，但也不能把半句话当歌手：
            // 右侧过长基本说明这是句子而不是歌手名。
            if (title.isNotEmpty() && artist.isNotEmpty() && artist.length <= 60) {
                return PlaylistImportEntry.TextQuery(title, artist)
            }
        }
        return PlaylistImportEntry.TextQuery(text, null)
    }
}
