package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.wall.AlbumWall
import cp.player.app.ui.wall.WallCrown
import cp.player.app.ui.wall.WallHud
import cp.player.app.ui.wall.WallItem
import cp.player.app.ui.wall.WallKind
import cp.player.app.ui.wall.WallState
import cp.player.app.ui.wall.WallZoomLadder
import cp.player.app.ui.wall.WallLevel
import cp.player.app.ui.wall.WallZoomMath
import cp.player.core.BackendResult
import cp.player.core.music.AlbumSummary
import cp.player.core.music.PlaylistSummary
import cp.player.core.util.localDateTimeOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 墙的**构图方式**（排序）。
 *
 * 排序在墙模式里不是设置项，而是构图手段 —— 换一次排序整面墙重新排布，
 * 但**谁是大瓦片不变**（大瓦片由"最近"决定，见 [buildWallItems]）。
 * 于是换排序是一次纯粹的"洗牌"，而不是"重新评价一遍谁重要"。
 *
 * ⚠️ 「色彩」排序（按封面主色相排成彩虹）需要 `ui/theme/CoverColor.kt` 的
 * `extractSeedColor` 走一遍全库取色，而那个缓存是"无锁、靠调用方串行"的
 * （见方案 §9.4）—— 属于 P3，这里先不做，避免在墙里随手调它。
 */
enum class WallSort { RECENT, ARTIST, TRACKS }

data class WallUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val albums: List<AlbumSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val sort: WallSort = WallSort.RECENT,
)

/**
 * 专辑墙的模式数据。
 *
 * 数据源是「收藏专辑 + 我的歌单」：两者一起才能让马赛克有**比例差异**
 * （专辑 `1:1`、歌单 `2:1`），只有专辑时整面墙会退化成均匀网格，
 * 那正是这个模式要避免的。
 */
class AlbumWallScreenModel : ScreenModel {

    private val _state = MutableStateFlow(WallUiState())
    val state: StateFlow<WallUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        screenModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val repo = AppModel.musicRepository
            val albumsResult = repo.getUserAlbums(limit = 200)
            val playlistsResult = runCatching { repo.getCurrentUserPlaylists() }
                .getOrElse { BackendResult.Error(it.message ?: "playlists") }
            val albums = (albumsResult as? BackendResult.Success)?.data.orEmpty()
            val playlists = (playlistsResult as? BackendResult.Success)?.data.orEmpty()
            _state.update {
                it.copy(
                    loading = false,
                    albums = albums,
                    playlists = playlists,
                    // ⚠️ 空列表**不是错误**：未登录 / 未收藏就是这个状态，
                    // 把它显示成"加载失败"会让用户以为坏了。
                    error = (albumsResult as? BackendResult.Error)?.message?.takeIf { albums.isEmpty() },
                )
            }
        }
    }

    fun selectSort(sort: WallSort) = _state.update { it.copy(sort = sort) }
}

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

    val items = remember(state.albums, state.playlists, state.sort, s) {
        buildWallItems(state, s)
    }
    val albumById = remember(state.albums) { state.albums.associateBy { "album:${it.id}" } }

    // 缩回 Z2 以下就收起海报 —— 海报是"墙在某个焦距上的样子"，焦距走了它就该走。
    LaunchedEffect(wall.zoom) {
        if (wall.zoom < WallLevel.COVER.zoom) wall.posterId = null
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
                        onClosePoster = { wall.posterId = null },
                        onPlay = { item ->
                            item.sourceId?.let { id ->
                                navigator.push(AlbumDetailScreen(id, albumById[item.id]))
                            }
                        },
                    )

                    // 缩放谱：右缘。给"无极"一个可发现、可点击的入口。
                    WallZoomLadder(
                        zoom = wall.zoom,
                        onZoom = { wall.zoom = it },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 18.dp),
                    )

                    // 表冠：模式的签名控件。拖动 = 无极缩放，双击 = 回默认层。
                    WallCrown(
                        zoom = wall.zoom,
                        onZoomDelta = { delta ->
                            wall.zoom = (wall.zoom + delta).coerceIn(0f, 1f)
                        },
                        onReset = { wall.zoom = WallLevel.MOSAIC.zoom },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 78.dp, bottom = 30.dp),
                    )

                    WallSortChips(
                        selected = state.sort,
                        onSelect = model::selectSort,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 20.dp, top = 20.dp),
                    )

                    // HUD 读画布回填的布局，而不是自己再算一份 —— 否则读数和画面
                    // 可能因为视口宽 / pad 取值不同而对不上。
                    wall.layout?.let { layout ->
                        WallHud(
                            zoom = wall.zoom,
                            layout = layout,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 26.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WallSortChips(
    selected: WallSort,
    onSelect: (WallSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val options = listOf(
        WallSort.RECENT to s.wall.sortRecent,
        WallSort.ARTIST to s.wall.sortArtist,
        WallSort.TRACKS to s.wall.sortTracks,
    )
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (key, label) ->
                val on = key == selected
                Surface(
                    onClick = { onSelect(key) },
                    shape = CircleShape,
                    color = if (on) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface,
                    contentColor = if (on) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

/**
 * 领域模型 → 墙上的条目。
 *
 * ## 两条规则
 *
 * 1. **`weight` 只由"最近"决定**，与当前排序无关。这样换排序是一次纯粹的洗牌：
 *    谁长成 `2:2` 不变，变的是它们排在哪 —— 用户不会因为切了个排序就"东西全变了"。
 * 2. **内容类型决定基础比例**：专辑 `1:1`、歌单 `2:1`。这不是装饰，
 *    `2:1` 多出来的那一格真的用来放标题与歌手（见 `AlbumWall` 的 `WallTile`）。
 */
private fun buildWallItems(state: WallUiState, s: CpStrings): List<WallItem> {
    // 先把"最近"算出来，作为全局的大瓦片依据
    val orderedByRecency = (
        state.albums.map { "album:${it.id}" to (it.publishTimeMs ?: 0L) } +
            state.playlists.mapIndexed { index, pl ->
                // 歌单没有发布时间；按拉取顺序折算（越靠前越"新"）。
                "playlist:${pl.id}" to (1_000_000L - index)
            }
        ).sortedByDescending { it.second }
    val total = orderedByRecency.size
    val weightOf: Map<String, Int> = orderedByRecency
        .mapIndexed { index, pair -> pair.first to (total - index) }
        .toMap()

    val albums = state.albums.map { al ->
        WallItem(
            id = "album:${al.id}",
            title = al.name,
            subtitle = s.wall.subtitle(al.artistName, albumYear(al), s.wall.kindAlbum),
            coverUrl = al.coverUrl,
            kind = WallKind.ALBUM,
            weight = weightOf["album:${al.id}"] ?: 0,
            sourceId = al.id,
        )
    }
    val playlists = state.playlists.map { pl ->
        WallItem(
            id = "playlist:${pl.id}",
            title = pl.name,
            subtitle = s.wall.subtitle(pl.creatorName, null, s.wall.kindPlaylist),
            coverUrl = pl.coverUrl,
            kind = WallKind.PLAYLIST,
            weight = weightOf["playlist:${pl.id}"] ?: 0,
            // 歌单的"播放"落到专辑详情是错的，这里先不给落点（P1 接歌单详情页）。
            sourceId = null,
        )
    }

    val merged = albums + playlists
    return when (state.sort) {
        WallSort.RECENT -> merged.sortedByDescending { it.weight }
        WallSort.ARTIST -> merged.sortedBy { it.subtitle ?: it.title }
        WallSort.TRACKS -> merged.sortedByDescending { it.title.length }
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
