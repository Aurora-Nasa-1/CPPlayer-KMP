package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.wall.AlbumWall
import cp.player.app.ui.wall.WallCrown
import cp.player.app.ui.wall.WallHud
import cp.player.app.ui.wall.WallImmersive
import cp.player.app.ui.wall.WallItem
import cp.player.app.ui.wall.WallKind
import cp.player.app.ui.wall.WallLevel
import cp.player.app.ui.wall.WallOverlaySlot
import cp.player.app.ui.wall.WallChrome
import cp.player.app.ui.wall.WallSort
import cp.player.app.ui.wall.WallState
import cp.player.app.ui.wall.WallZoomLadder
import cp.player.core.BackendResult
import cp.player.core.media.LocalMediaItem
import cp.player.core.music.AlbumSummary
import cp.player.core.music.TrackSummary
import cp.player.core.util.localDateTimeOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WallUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val albums: List<AlbumSummary> = emptyList(),
    val liked: List<TrackSummary> = emptyList(),
    val recent: List<TrackSummary> = emptyList(),
    val local: List<LocalMediaItem> = emptyList(),
    val sort: WallSort = WallSort.RECENT,
)

/**
 * 专辑墙的模式数据。
 *
 * ## 四个源，各司其职
 *
 * | 源 | 取法 | 上墙理由 |
 * |---|---|---|
 * | 收藏专辑 | `album/sublist` | 方形瓦片的主体，一屏能放很多张 |
 * | 我喜欢的音乐 | 「我喜欢的音乐」歌单的曲目 | **用户明确表达过偏好的歌曲**，优先级最高 |
 * | 最近播放 | `AppModel.recentTracksFlow` | 本地历史，零网络请求，永远可用 |
 * | 本地歌曲 | `AppModel.localMedia` | 离线也完整，且是唯一"没有封面"的一类（走竖条） |
 *
 * ⚠️ **不用歌单**：歌单数量少（十几到几十个），铺不出"墙"的密度，而且歌单封面
 * 与专辑封面视觉上无从区分 —— 上墙只会是一堆长得一样的方块。
 */
class AlbumWallScreenModel : ScreenModel {

    private val _state = MutableStateFlow(WallUiState())
    val state: StateFlow<WallUiState> = _state.asStateFlow()

    init {
        observeRecent()
        refresh()
    }

    /**
     * 最近播放是本地状态、随时会变（用户在别的页面听一首歌就变了），
     * 所以用 collect 持续跟，而不是刷新时读一次快照。
     */
    private fun observeRecent() {
        screenModelScope.launch {
            AppModel.recentTracksFlow.collect { tracks ->
                _state.update { it.copy(recent = tracks) }
            }
        }
    }

    fun refresh() {
        screenModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val repo = AppModel.musicRepository

            // 四个源并发拉，**每个都自己兜异常** —— 一个源挂了不该把别的已经拿到的也丢掉
            // （并发里任何一个抛出都会取消整个 scope）。
            val albumsTask = async { safe { repo.getUserAlbums(limit = 300) } }
            val likedTask = async { loadLikedSongs() }
            val localTask = async {
                runCatching { AppModel.localMedia.items().first() }.getOrElse { emptyList() }
            }

            val albumsResult = albumsTask.await()
            val albums = albumsResult.dataOrEmpty()
            val liked = likedTask.await()
            val local = localTask.await()

            _state.update {
                it.copy(
                    loading = false,
                    albums = albums,
                    liked = liked,
                    local = local,
                    // ⚠️ 空列表**不是错误**：未登录 / 未收藏就是这个状态，
                    // 把它显示成"加载失败"会让用户以为坏了。
                    error = (albumsResult as? BackendResult.Error)?.message
                        ?.takeIf { albums.isEmpty() && liked.isEmpty() && local.isEmpty() },
                )
            }
        }
    }

    /** 拉「我喜欢的音乐」歌单的曲目。找不到该歌单（未登录 / 平台改名）时返回空。 */
    private suspend fun loadLikedSongs(): List<TrackSummary> {
        val playlists = safe { AppModel.musicRepository.getCurrentUserPlaylists() }.dataOrEmpty()
        val liked = playlists.firstOrNull { isLikedPlaylistName(it.name) } ?: return emptyList()
        return safe { AppModel.musicRepository.getPlaylistTracks(liked.id, limit = 300) }
            .let { (it as? BackendResult.Success)?.data?.tracks.orEmpty() }
    }

    fun selectSort(sort: WallSort) = _state.update { it.copy(sort = sort) }

    /**
     * 从墙上播一项。
     *
     * ## 三种类型的队列语义不同，这是有意的
     *
     * | 类型 | 队列 | 理由 |
     * |---|---|---|
     * | 歌曲 | **墙上所有歌曲**（按当前构图顺序），从点中的那首起 | 于是"队列 = 墙上的邻居"成立：缩小一点就能看到接下来听什么（方案 §7.3） |
     * | 本地 | 墙上所有本地文件 | 同上，离线也自洽 |
     * | 专辑 | **那一张专辑的曲目** | 点专辑的语义是"听这张专辑"，不是"从这一首开始往下听全库" |
     *
     * ⚠️ 歌曲分支走 [AppModel.playTrackClicked] 而不是直接 `playQueue`：一起听进行中时
     * 点歌的语义是「下一首播放」，这个拦截只在这一处生效，绕过它会让一起听不同步。
     */
    fun play(item: WallItem, wallItems: List<WallItem>, onStarted: (() -> Unit)? = null) {
        val sourceId = item.sourceId ?: return
        screenModelScope.launch {
            val started = when (item.kind) {
                WallKind.ALBUM -> playAlbum(sourceId)
                WallKind.SONG -> playSongs(item, wallItems)
                WallKind.LOCAL -> playLocal(item, wallItems)
            }
            // ⚠️ 只在**真的起播了**之后才允许调用方推进焦距。
            // 乐观推进的话，专辑为空 / 未登录时会进到沉浸层而那里没有曲目
            // （`WallImmersive` 直接 return），用户看到的是一张巨大的网格，比不动更糟。
            if (started) onStarted?.invoke()
        }
    }

    private suspend fun playAlbum(albumId: String): Boolean {
        val id = albumId.toLongOrNull() ?: return false.also { notifyPlayFailed() }
        val detail = safe { AppModel.musicRepository.getAlbumDetail(id) }
            .let { (it as? BackendResult.Success)?.data }
        val ids = detail?.tracks?.map { it.id }.orEmpty()
        if (ids.isEmpty()) return false.also { notifyPlayFailed() }
        AppModel.playback.playQueue(ids, startIndex = 0, sourceId = albumId)
        return true
    }

    private suspend fun playSongs(clicked: WallItem, wallItems: List<WallItem>): Boolean {
        val songs = wallItems.filter { it.kind == WallKind.SONG }.mapNotNull { it.sourceId }
        val index = songs.indexOf(clicked.sourceId).coerceAtLeast(0)
        if (songs.isEmpty()) return false.also { notifyPlayFailed() }
        val mediaId = songs[index]
        AppModel.playTrackClicked(mediaId) {
            AppModel.playback.playQueue(songs, startIndex = index)
        }
        return true
    }

    private suspend fun playLocal(clicked: WallItem, wallItems: List<WallItem>): Boolean {
        val locals = wallItems.filter { it.kind == WallKind.LOCAL }
        if (locals.isEmpty()) return false.also { notifyPlayFailed() }
        val index = locals.indexOfFirst { it.id == clicked.id }.coerceAtLeast(0)
        AppModel.playback.playQueue(
            locals.map { "local://audio/${it.sourceId}" },
            startIndex = index,
        )
        return true
    }

    /** 拿不到可播曲目时**必须出声** —— 静默什么都不发生，用户只会以为界面坏了。 */
    private fun notifyPlayFailed() {
        UiEvents.notify(AppModel.strings().wall.playFailed)
    }

    /**
     * 把会抛异常的仓库调用收敛成 [MusicResult]。
     *
     * 并发拉取时任何一个源抛异常都会取消整个 scope，连带把已经拿到的数据一起丢掉 ——
     * 所以每个分支都必须自己兜住异常。
     */
    private suspend fun <T> safe(block: suspend () -> BackendResult<T>): BackendResult<T> =
        runCatching { block() }.getOrElse { BackendResult.Error(it.message ?: "wall source failed") }
}

/**
 * 只对 `BackendResult<List<T>>` 生效。
 *
 * ⚠️ 写成 `BackendResult<T>.dataOrEmpty(): List<T>` 是错的：那样 `T` 会被推断成
 * `List<AlbumSummary>`，返回值就成了 `List<List<AlbumSummary>>`，
 * 调用点报"期望 List<AlbumSummary>，实际是 List<List<...>>"，
 * 并连带把后面所有用到该列表的 lambda 全标成 Unresolved（看起来像整段都坏了）。
 */
private fun <T> BackendResult<List<T>>.dataOrEmpty(): List<T> = when (this) {
    is BackendResult.Success -> data
    else -> emptyList()
}

/** 「我喜欢的音乐」在不同平台叫法不同：先按平台固定名，再退回关键词。 */
private fun isLikedPlaylistName(name: String): Boolean =
    name.contains("喜欢的音乐") || name.contains("我喜欢的") ||
        name.contains("喜欢", ignoreCase = true) || name.contains("Like", ignoreCase = true)

class AlbumWallScreen : Screen {

    @Composable
    override fun Content() {
        AlbumWallContent(rememberScreenModel { AlbumWallScreenModel() })
    }
}

@Composable
private fun AlbumWallContent(model: AlbumWallScreenModel) {
    val s = cpStrings()
    val state by model.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val wall = remember { WallState() }
    val playback by AppModel.playback.state.collectAsState()

    val items = remember(state.albums, state.liked, state.recent, state.local, state.sort, s) {
        buildWallItems(state, s)
    }

    // 缩回封面层以下就收起海报 —— 海报是"墙在某个焦距上的样子"，焦距走了它就该走。
    LaunchedEffect(wall.zoom) {
        if (wall.zoom < WallLevel.COVER.zoom) {
            wall.posterId = null
            wall.posterFrom = null
        }
    }

    CpRouteScaffold(
        title = s.wall.screenTitle,
        onBack = { navigator.popOrNotify() },
    ) { modifier ->
        Box(modifier.fillMaxSize()) {
            when {
                state.loading && items.isEmpty() -> ContentState(
                    title = s.wall.loading,
                    message = s.wall.loadingNote,
                    loading = true,
                    modifier = Modifier.align(Alignment.Center),
                )

                items.isEmpty() -> ContentState(
                    title = state.error?.let { s.wall.failed } ?: s.wall.empty,
                    message = state.error ?: s.wall.emptyNote,
                    error = state.error != null,
                    actionLabel = s.wall.retry,
                    onAction = model::refresh,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> {
                    AlbumWall(
                        items = items,
                        state = wall,
                        onOpenPoster = { wall.posterId = it.id },
                        onClosePoster = {
                            wall.posterId = null
                            wall.posterFrom = null
                        },
                        // 播放成功后**继续推进焦距**到沉浸层 —— 这是方案 §7 说的
                        // "点海报的播放键 = 进 Z4 + 开始播放"，也是墙与播放器之间
                        // 唯一一次由动作（而不是手势）驱动的转场。
                        onPlay = { item ->
                            model.play(item, items) { wall.zoomToCentre(WallLevel.IMMERSIVE.zoom) }
                        },
                    )

                    // 浮层走**共享**的 WallChrome（页面与出图夹具同一份代码）。
                    // 第一版两边各写一份，夹具漏了"海报打开时收起缩放谱"，出图就骗过了自己。
                    WallChrome(
                        state = wall,
                        sort = state.sort,
                        onSortChange = model::selectSort,
                    )

                    // Z4 沉浸播放器。它排在 WallChrome 之后（更晚绘制 = 更上层），
                    // 且 `WallChrome` 在 zoom ≥ ImmersiveFrom 时整体让位。
                    //
                    // 「队列 = 墙上的邻居」：从当前曲目在墙上的位置往后取 ——
                    // 缩小一点就能看到接下来听什么。
                    WallImmersive(
                        playback = playback,
                        zoom = wall.zoom,
                        upNext = remember(items, playback.currentTrack?.id) {
                            val currentId = playback.currentTrack?.id
                            val start = items.indexOfFirst { it.sourceId == currentId }
                            if (start >= 0) items.drop(start + 1).take(8) else items.take(8)
                        },
                        onCollapse = { wall.zoomToCentre(WallLevel.COVER.zoom) },
                        onPlayPause = { AppModel.playback.togglePlayPause() },
                        onSkipNext = { AppModel.playback.skipNext() },
                        onSkipPrevious = { AppModel.playback.skipPrevious() },
                        onSeek = { AppModel.playback.seekTo(it) },
                        onPlayUpNext = { item -> model.play(item, items) },
                    )
                }
            }
        }
    }
}

/**
 * 领域模型 → 墙上的条目。
 *
 * ## 权重怎么定（决定谁长成 `2:2`）
 *
 * 1. 四个源先**轮转交错**成一条规范顺序（喜欢 / 最近 / 专辑 / 本地 依次各取一个），
 *    权重 = 总条数 − 名次。
 *    ⚠️ 交错是必要的：如果按源顺序拼接，前 30% 会全部落在同一个源上，
 *    大瓦片会变成"清一色的喜欢歌曲"，马赛克的比例多样性就没了。
 * 2. **权重与当前排序无关** —— 换排序是一次纯粹的洗牌：谁大不变，变的是它们排在哪。
 *    否则用户切一下排序会觉得"东西全变了"。
 *
 * ## 去重
 *
 * 同一首歌可能既在「我喜欢」又在「最近播放」；本地文件也可能与云端同一首。
 * 按 `id` 去重，保留**先出现的那一个**（顺序即优先级：喜欢 > 最近 > 专辑 > 本地）。
 */
private fun buildWallItems(state: WallUiState, s: CpStrings): List<WallItem> {
    val seen = HashSet<String>()

    fun albumItems(): List<WallItem> = state.albums
        .sortedByDescending { it.publishTimeMs ?: 0L }
        .mapNotNull { al ->
            val id = "album:${al.id}"
            if (!seen.add(id)) return@mapNotNull null
            WallItem(
                id = id,
                title = al.name,
                subtitle = s.wall.subtitle(al.artistName, albumYear(al), s.wall.kindAlbum),
                coverUrl = al.coverUrl,
                kind = WallKind.ALBUM,
                weight = 0,
                sourceId = al.id.toString(),
            )
        }

    fun songItems(source: List<TrackSummary>, prefix: String, kind: WallKind): List<WallItem> =
        source.mapNotNull { tr ->
            val id = "$prefix:${tr.id}"
            if (!seen.add(id)) return@mapNotNull null
            WallItem(
                id = id,
                title = tr.name,
                subtitle = s.wall.subtitle(
                    tr.artist.takeIf { it.isNotBlank() },
                    null,
                    if (kind == WallKind.SONG) s.wall.kindSong else s.wall.kindLocal,
                ),
                coverUrl = tr.coverUrl,
                kind = kind,
                weight = 0,
                sourceId = tr.id,
            )
        }

    fun localItems(): List<WallItem> = state.local
        .filter { it.mediaType == cp.player.core.media.MediaType.AUDIO }
        .sortedByDescending { it.lastModified }
        .mapNotNull { lm ->
            val id = "local:${lm.path}"
            if (!seen.add(id)) return@mapNotNull null
            WallItem(
                id = id,
                title = lm.title,
                subtitle = s.wall.subtitle(lm.artist, null, s.wall.kindLocal),
                coverUrl = lm.coverUri,
                kind = WallKind.LOCAL,
                weight = 0,
                sourceId = lm.path,
            )
        }

    // 各源内部已按"最近"排好；这里做轮转交错，让大瓦片分散在四个源上
    val perSource = listOf(
        songItems(state.liked, "song", WallKind.SONG),
        songItems(state.recent, "recent", WallKind.SONG),
        albumItems(),
        localItems(),
    )
    val canonical = ArrayList<WallItem>(perSource.sumOf { it.size })
    val maxLen = perSource.maxOfOrNull { it.size } ?: 0
    for (i in 0 until maxLen) {
        perSource.forEach { list -> list.getOrNull(i)?.let(canonical::add) }
    }

    val total = canonical.size
    val weighted = canonical.mapIndexed { index, item -> item.copy(weight = total - index) }

    return when (state.sort) {
        WallSort.RECENT -> weighted
        WallSort.TITLE -> weighted.sortedBy { it.title.lowercase() }
        WallSort.TYPE -> weighted.sortedWith(compareBy({ it.kind.ordinal }, { it.title.lowercase() }))
    }
}

/**
 * 发行年份。
 *
 * ⚠️ 走 `cp.player.core.util.localDateTimeOf`（expect/actual）而**不是** `kotlinx-datetime`：
 * 后者在本工程运行时解析到 0.7.x、编译期是 0.6.x，`kotlinx.datetime.Instant` 那个类
 * 在 0.7 里已经不存在（变成 typealias），调用即 `NoClassDefFoundError`，
 * 被 `runCatching` 吞掉后表现为"年份永远不显示"（AGENTS §5 有完整记录）。
 */
private fun albumYear(album: AlbumSummary): String? {
    val ms = album.publishTimeMs ?: return null
    if (ms <= 0L) return null
    return runCatching { localDateTimeOf(ms).year.toString() }.getOrNull()
}
