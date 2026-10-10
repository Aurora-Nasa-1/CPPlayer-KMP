package cp.player.core.local

import cp.player.core.media.LocalMediaItem

/**
 * 文件夹树上的一个节点（目录）。
 *
 * @param path 规范化后的完整目录路径（`/` 分隔）
 * @param name 末级目录名（UI 显示用）
 * @param parentPath 上级目录；顶层节点为 null
 * @param depth 相对可见根层级的深度，从 1 开始（缩进用）
 * @param directSongCount 直接位于本目录的曲目数（不含子目录）
 * @param totalSongCount 含所有子目录的曲目总数
 * @param totalDurationMs 含子目录的总时长
 * @param coverUri 代表封面（本目录或子目录里第一首有封面的曲目）
 * @param hasChildren 是否有下级目录（决定是否画展开箭头）
 */
data class LocalFolderNode(
    val path: String,
    val name: String,
    val parentPath: String?,
    val depth: Int,
    val directSongCount: Int,
    val totalSongCount: Int,
    val totalDurationMs: Long,
    val coverUri: String?,
    val hasChildren: Boolean = false,
)

/**
 * 从曲目列表推导文件夹树。
 *
 * ## 为什么需要「公共前缀」这一步
 *
 * 直接按路径分段展开的话，Android 上会得到
 * `/ → storage → emulated → 0 → Music → 专辑` 五层空壳目录，
 * 用户得连点五次才能看到音乐。桌面端同理会有 `C: → Users → xxx`。
 * 这些层级里一首歌都没有，纯属噪音。
 *
 * 因此先算出所有含曲目录的公共前缀，从它的**下一级**开始建树，
 * 于是树根就直接是 `Music` / `音乐` 这一级。
 */
object LocalFolderTree {

    /** 构建文件夹节点列表（已按层级顺序排好，可直接顺序渲染）。 */
    fun build(items: List<LocalMediaItem>): List<LocalFolderNode> {
        val songs = LocalLibraryAggregator.audioOnly(items)
        if (songs.isEmpty()) return emptyList()

        // 每个目录下直接包含的曲目
        val directByDir = LinkedHashMap<String, MutableList<LocalMediaItem>>()
        for (song in songs) {
            val dir = parentOf(normalize(song.path))
            if (dir.isEmpty()) continue
            directByDir.getOrPut(dir) { ArrayList() } += song
        }
        if (directByDir.isEmpty()) return emptyList()

        val commonPrefix = commonPrefixOf(directByDir.keys)
        val prefixDepth = if (commonPrefix.isEmpty()) 0 else commonPrefix.split('/').size

        // 展开出所有需要展示的层级（公共前缀之下）
        val visiblePaths = LinkedHashSet<String>()
        for (dir in directByDir.keys) {
            val segments = dir.split('/')
            for (i in (prefixDepth + 1)..segments.size) {
                visiblePaths += segments.take(i).joinToString("/")
            }
        }

        // 按层级 + 名称排序：保证父节点永远排在自己的子节点之前
        val ordered = visiblePaths.sortedWith(
            compareBy<String> { it.count { ch -> ch == '/' } }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it },
        )

        val childPrefixes = HashMap<String, Boolean>(visiblePaths.size)
        for (path in visiblePaths) {
            val prefix = "$path/"
            childPrefixes[path] = visiblePaths.any { it != path && it.startsWith(prefix) }
        }

        return ordered.map { path ->
            val segments = path.split('/')
            val depth = segments.size - prefixDepth
            val direct = directByDir[path].orEmpty()
            val subtree = directByDir.filterKeys { it == path || it.startsWith("$path/") }
            val subtreeSongs = subtree.values.flatten()
            LocalFolderNode(
                path = path,
                name = segments.last().ifEmpty { path },
                parentPath = if (depth <= 1) null else segments.dropLast(1).joinToString("/"),
                depth = depth,
                directSongCount = direct.size,
                totalSongCount = subtreeSongs.size,
                totalDurationMs = subtreeSongs.sumOf { it.durationMs },
                coverUri = direct.firstNotNullOfOrNull { it.coverUri }
                    ?: subtreeSongs.firstNotNullOfOrNull { it.coverUri },
                hasChildren = childPrefixes[path] == true,
            )
        }
    }

    /**
     * 按当前展开状态筛出应显示的节点。
     *
     * 逐条顺序判定（节点已按层级排序）：只有「父节点可见**且**父节点已展开」
     * 的节点才出现。少判一层就会出现「父目录折叠着，孙子目录却露在外面」。
     */
    fun visibleNodes(nodes: List<LocalFolderNode>, expanded: Set<String>): List<LocalFolderNode> {
        val out = ArrayList<LocalFolderNode>(nodes.size)
        val shown = HashSet<String>()
        for (node in nodes) {
            val topLevel = node.parentPath == null
            val parentVisible = topLevel || node.parentPath in shown
            val parentExpanded = topLevel || node.parentPath in expanded
            if (parentVisible && parentExpanded) {
                out += node
                shown += node.path
            }
        }
        return out
    }

    /** 某目录**直接**包含的曲目。 */
    fun directSongs(items: List<LocalMediaItem>, node: LocalFolderNode): List<LocalMediaItem> =
        LocalLibraryAggregator.audioOnly(items).filter { parentOf(normalize(it.path)) == node.path }

    /** 某目录**含子目录**的全部曲目（按目录层次 + 轨号排序）。 */
    fun allSongs(items: List<LocalMediaItem>, node: LocalFolderNode): List<LocalMediaItem> =
        LocalLibraryAggregator.audioOnly(items)
            .filter { it.path.replace('\\', '/').startsWith("${node.path}/") }
            .sortedWith(
                compareBy<LocalMediaItem, String>(String.CASE_INSENSITIVE_ORDER) {
                    parentOf(normalize(it.path))
                }.thenBy { it.metadata?.discNumber ?: Int.MAX_VALUE }
                    .thenBy { it.metadata?.trackNumber ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
            )

    /** 沿路径向上取所有祖先目录（判断「是否位于某目录之下」用）。 */
    fun ancestorsOf(path: String): List<String> {
        val out = ArrayList<String>()
        var current = parentOf(normalize(path))
        while (current.isNotEmpty()) {
            out += current
            current = parentOf(current)
        }
        return out
    }

    // ==================== 路径工具 ====================

    internal fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/')

    /** 上级目录；已到根（`/x` 或 `C:`）时返回空串。`content://` 一律返回空串。 */
    internal fun parentOf(dir: String): String {
        if (dir.startsWith("content://")) return ""
        val index = dir.lastIndexOf('/')
        // index <= 0 覆盖两种情况：没有分隔符（`C:`）、以及 `/x` 这样的单层绝对路径
        return if (index <= 0) "" else dir.substring(0, index)
    }

    /** 取一组目录的公共前缀（按 `/` 分段）。 */
    private fun commonPrefixOf(dirs: Collection<String>): String {
        if (dirs.isEmpty()) return ""
        val sorted = dirs.sorted()
        val first = sorted.first().split('/')
        val last = sorted.last().split('/')
        var i = 0
        while (i < first.size && i < last.size && first[i] == last[i]) i++
        // 公共前缀不能等于其中任一目录本身 —— 那样它的内容会被「展开」到根层，
        // 反而看不到这层目录名。退一级更稳妥。
        val shared = first.take(i)
        return if (shared.size >= first.size) shared.dropLast(1).joinToString("/") else shared.joinToString("/")
    }
}
