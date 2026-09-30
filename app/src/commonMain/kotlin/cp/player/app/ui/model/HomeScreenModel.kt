package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.core.BackendResult
import cp.player.core.music.AlbumSummary
import cp.player.core.music.ArtistSummary
import cp.player.core.music.BannerItem
import cp.player.core.music.MusicResult
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.RankingSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「发现歌单」区的三种来源。
 *
 * 首页原先把「推荐歌单」和「热门歌单」做成**上下两个内容同构的栅格** ——
 * 同样的卡片、同样的排布，只是数据源不同，在桌面上一眼就是两块重复的版面。
 * 合并成一个区块 + 分段切换后，版面少一半、信息量不变。
 *
 * 只有 [Recommended] 在首屏就拉取，另两个等用户真的切过去再拉（见 [HomeScreenModel.selectPlaylistSource]）。
 */
enum class PlaylistSource(val label: String) {
    Recommended("推荐"),
    Hot("热门"),
    Premium("精品"),
}

/**
 * 「新歌速递」的地区筛选，取值与上游 `top/song` 的 `type` 一一对应。
 */
enum class NewSongRegion(val type: Int, val label: String) {
    All(0, "全部"),
    Chinese(7, "华语"),
    Western(96, "欧美"),
    Japanese(8, "日本"),
    Korean(16, "韩国"),
}

data class HomeUiState(
    val dailySongs: List<TrackSummary> = emptyList(),
    val banners: List<BannerItem> = emptyList(),
    val rankings: List<RankingSummary> = emptyList(),
    val newAlbums: List<AlbumSummary> = emptyList(),
    val hotArtists: List<ArtistSummary> = emptyList(),
    val newSongs: List<TrackSummary> = emptyList(),
    val recommendedPlaylists: List<PlaylistSummary> = emptyList(),
    val hotPlaylists: List<PlaylistSummary> = emptyList(),
    val premiumPlaylists: List<PlaylistSummary> = emptyList(),
    val userPlaylists: List<PlaylistSummary> = emptyList(),
    val likedPlaylist: PlaylistSummary? = null,
    val playlistSource: PlaylistSource = PlaylistSource.Recommended,
    val playlistSourceLoading: Boolean = false,
    val newSongRegion: NewSongRegion = NewSongRegion.All,
    val newSongsLoading: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
) {
    /** 当前「发现歌单」区块该展示的列表。 */
    val visiblePlaylists: List<PlaylistSummary>
        get() = when (playlistSource) {
            PlaylistSource.Recommended -> recommendedPlaylists
            PlaylistSource.Hot -> hotPlaylists
            PlaylistSource.Premium -> premiumPlaylists
        }

    /**
     * 侧栏歌单列表：**剔除收藏夹**（`xx喜欢的音乐`）。
     *
     * 侧栏里「我喜欢的音乐」是一个独立入口，收藏夹再以歌单身份出现一次就是同一个目的地
     * 有两个条目。两条判据并用：先按名字（平台命名固定），再按 [likedPlaylist] 的 id
     * 兜底 —— 后者能覆盖名字不落在该后缀上的小众平台。
     */
    val sidebarPlaylists: List<PlaylistSummary>
        get() {
            val likedId = likedPlaylist?.id
            return userPlaylists.filter { it.id != likedId && !isLikedPlaylistName(it.name) }
        }

    /**
     * 所有内容源都空 —— 用来区分「加载完成但确实没有内容」与「有内容」。
     * [error] 非空时不算空：那种情况下页面已经有一条明确的失败提示，再叠一条空态是噪音。
     */
    val isEmptyHome: Boolean
        get() = error == null &&
            dailySongs.isEmpty() &&
            banners.isEmpty() &&
            rankings.isEmpty() &&
            newAlbums.isEmpty() &&
            hotArtists.isEmpty() &&
            newSongs.isEmpty() &&
            visiblePlaylists.isEmpty()
}

/**
 * 是否「收藏夹」歌单 —— 即各音乐平台自动生成的那个「xx喜欢的音乐」。
 *
 * 判据刻意**只看名字后缀**：它是平台固定命名（网易云是「{昵称}喜欢的音乐」），
 * 而 `name.contains("喜欢")` 会把用户自己建的「喜欢的翻唱」也误判进来 ——
 * 那种歌单不是收藏夹，不该被当成「我喜欢的音乐」。
 *
 * 用于两处：① [HomeScreenModel.pickLikedPlaylist] 挑出收藏夹；
 * ② [HomeUiState.sidebarPlaylists] 把它从侧栏歌单列表里剔除（已有独立入口，不重复出现）。
 */
internal fun isLikedPlaylistName(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.endsWith("喜欢的音乐") || trimmed.endsWith("喜欢的歌曲")) return true
    val lower = trimmed.lowercase()
    return lower.endsWith("liked songs") ||
        lower.endsWith("favorite songs") ||
        lower.endsWith("favourite songs") ||
        lower == "likes"
}

/**
 * 首页数据模型。
 *
 * ### 与后端 API 的关系
 * 首页展示的每一块都必须有真实数据支撑，因此这里覆盖了日推、焦点图、榜单、新碟、
 * 热门歌手、新歌速递、推荐歌单、用户歌单共 8 条读路径。此前只有「日推 + 两个歌单栅格」
 * 是真数据，其余位置靠纯入口卡片填空，看起来内容很多、点下去没有东西。
 *
 * @param loadDiscovery 是否加载「发现」内容。桌面侧栏只需要 [HomeUiState.likedPlaylist]
 *   与 [HomeUiState.userPlaylists]，却也会构造一个 HomeScreenModel；不关掉这条支路的话，
 *   首页那一屏七八个并发请求会被整份重打一遍（侧栏跟着首页一起刷）。侧栏传 `false`，
 *   它只会拉一次用户歌单。
 */
class HomeScreenModel(
    private val loadDiscovery: Boolean = true,
) : ScreenModel {
    private val _state = MutableStateFlow(HomeUiState(loading = loadDiscovery))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /**
     * 地区 → 已拉取的新歌速递。来回切地区时直接复用，避免每切一次都闪一遍加载态。
     * 只在主线程读写（[screenModelScope] 与 `MusicBackend.backendScope` 同为 Main），
     * 因此不需要额外同步。
     */
    private val newSongsByRegion = mutableMapOf<NewSongRegion, List<TrackSummary>>()

    init {
        refresh()
    }

    /** 播放私人 FM：按批次连续拉取，补足一组可听队列。 */
    fun playPersonalFm() {
        screenModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                safe { AppModel.musicRepository.getPersonalFmBatch(targetSize = 18) }
            }
            val songs = (result as? BackendResult.Success)?.data.orEmpty()
            if (songs.isEmpty()) {
                cp.player.app.ui.util.UiEvents.notify("私人FM暂不可用，请稍后再试")
                return@launch
            }
            val provider = AppModel.activeProviderId()
            AppModel.playback.playQueue(songs.map { "$provider://song/${it.id}" }, startIndex = 0)
        }
    }

    fun refresh() {
        // 已有内容时不做「刷新即清空」：切回首页不该先闪一屏骨架。
        if (_state.value.loading && _state.value.dailySongs.isNotEmpty()) return
        screenModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val next = withContext(Dispatchers.IO) {
                if (loadDiscovery) loadDiscoveryHome() else loadUserLibraryOnly()
            }
            // 首屏拿到的「全部」新歌速递顺手进缓存：这样用户点开地区筛选再切回「全部」
            // 时不会重新请求、也不会闪一遍加载态。写在这里而不是 loadDiscoveryHome 内部，
            // 是因为那边跑在 IO 线程，而这张表只在主线程读写。
            if (next.newSongs.isNotEmpty()) newSongsByRegion[NewSongRegion.All] = next.newSongs
            _state.value = next.copy(loading = false)
        }
    }

    /**
     * 切换「发现歌单」来源。首次切到某个来源时才真正发请求，之后走内存缓存。
     */
    fun selectPlaylistSource(source: PlaylistSource) {
        if (_state.value.playlistSource == source) return
        _state.value = _state.value.copy(playlistSource = source)
        val alreadyLoaded = when (source) {
            PlaylistSource.Recommended -> _state.value.recommendedPlaylists.isNotEmpty()
            PlaylistSource.Hot -> _state.value.hotPlaylists.isNotEmpty()
            PlaylistSource.Premium -> _state.value.premiumPlaylists.isNotEmpty()
        }
        if (alreadyLoaded) return

        screenModelScope.launch {
            _state.value = _state.value.copy(playlistSourceLoading = true)
            val result = withContext(Dispatchers.IO) {
                safe {
                    when (source) {
                        PlaylistSource.Hot -> AppModel.musicRepository.getTopPlaylists(limit = 30)
                        PlaylistSource.Premium -> AppModel.musicRepository.getHighQualityPlaylists(limit = 30)
                        // Recommended 在首屏已拉过，走到这里说明上游返回了空列表 —— 不再重试。
                        PlaylistSource.Recommended -> BackendResult.Success(emptyList())
                    }
                }
            }
            val items = (result as? BackendResult.Success)?.data.orEmpty()
            // 切换可能发生在请求在途期间：按当前 state 合并，别用发起时的快照覆盖回去。
            _state.value = _state.value.copy(
                playlistSourceLoading = false,
                hotPlaylists = if (source == PlaylistSource.Hot) items else _state.value.hotPlaylists,
                premiumPlaylists = if (source == PlaylistSource.Premium) items else _state.value.premiumPlaylists,
            )
        }
    }

    /** 切换新歌速递的地区。已拉过的地区直接复用。 */
    fun selectNewSongRegion(region: NewSongRegion) {
        if (_state.value.newSongRegion == region) return
        _state.value = _state.value.copy(newSongRegion = region)
        newSongsByRegion[region]?.let { cached ->
            _state.value = _state.value.copy(newSongs = cached, newSongsLoading = false)
            return
        }
        screenModelScope.launch {
            _state.value = _state.value.copy(newSongsLoading = true)
            val result = withContext(Dispatchers.IO) {
                safe { AppModel.musicRepository.getNewSongsByRegion(region.type, limit = 20) }
            }
            val items = (result as? BackendResult.Success)?.data.orEmpty()
            if (items.isNotEmpty()) newSongsByRegion[region] = items
            // 用户在请求在途期间又切了地区：结果只写回对应的那个地区，且仅在仍选中它时上屏。
            _state.value = if (_state.value.newSongRegion == region) {
                _state.value.copy(newSongs = items, newSongsLoading = false)
            } else {
                _state.value.copy(newSongsLoading = false)
            }
        }
    }

    // ======================== 加载 ========================

    private suspend fun loadUserLibraryOnly(): HomeUiState {
        val userResult = safe { AppModel.musicRepository.getCurrentUserPlaylists() }
        val user = userResult.dataOrEmpty()
        return HomeUiState(
            userPlaylists = user,
            likedPlaylist = pickLikedPlaylist(user),
            loading = false,
            error = userResult.errorOrNull(),
        )
    }

    private suspend fun loadDiscoveryHome(): HomeUiState = coroutineScope {
        // 首页一屏有 8 个互相独立的数据源。串行拉的话总延迟是八者之和，
        // 而它们之间没有任何依赖关系 —— 全部并发，总延迟取决于最慢的那个。
        val daily = async { safe { AppModel.musicRepository.getRecommendedSongs() } }
        val recommended = async { safe { AppModel.musicRepository.getRecommendedPlaylists() } }
        val personalized = async { safe { AppModel.musicRepository.getPersonalizedPlaylists(30) } }
        val banners = async { safe { AppModel.musicRepository.getBanners() } }
        val rankings = async { safe { AppModel.musicRepository.getRankings() } }
        val albums = async { safe { AppModel.musicRepository.getNewAlbums(limit = 24) } }
        val artists = async { safe { AppModel.musicRepository.getHotArtists(limit = 18) } }
        val newSongs = async {
            safe { AppModel.musicRepository.getNewSongsByRegion(NewSongRegion.All.type, limit = 20) }
        }
        val user = async { safe { AppModel.musicRepository.getCurrentUserPlaylists() } }

        val dailyResult = daily.await()
        val userResult = user.await()
        val mergedRecommended = (
            recommended.await().dataOrEmpty() + personalized.await().dataOrEmpty()
            ).distinctBy { it.id }

        // 只有「日推」和「用户歌单」失败才升级成页面级错误：它们是登录态与个性化能力的
        // 直接体现。焦点图 / 榜单 / 新碟 / 歌手 / 新歌速递都是公共内容，某个 Provider 不提供
        // 属于正常情况，静默留白比甩一条红字更合适。
        val error = dailyResult.errorOrNull() ?: userResult.errorOrNull()

        val allRegionSongs = newSongs.await().dataOrEmpty()

        HomeUiState(
            dailySongs = dailyResult.dataOrEmpty(),
            banners = banners.await().dataOrEmpty(),
            rankings = rankings.await().dataOrEmpty(),
            newAlbums = albums.await().dataOrEmpty(),
            hotArtists = artists.await().dataOrEmpty(),
            newSongs = allRegionSongs,
            recommendedPlaylists = mergedRecommended,
            userPlaylists = userResult.dataOrEmpty(),
            likedPlaylist = pickLikedPlaylist(userResult.dataOrEmpty()),
            loading = false,
            error = error,
        )
    }

    /**
     * 挑出收藏夹歌单：先按 [isLikedPlaylistName] 精确匹配，匹配不到再退回宽松的
     * `contains("喜欢")` —— 有些平台把它叫「我的收藏」之类，宽松匹配是保底，
     * 顺序不能反（宽松在前会把「喜欢的翻唱」误当成收藏夹）。
     */
    private fun pickLikedPlaylist(playlists: List<PlaylistSummary>): PlaylistSummary? =
        playlists.firstOrNull { isLikedPlaylistName(it.name) }
            ?: playlists.firstOrNull {
                it.name.contains("喜欢", ignoreCase = true) || it.name.contains("Like", ignoreCase = true)
            }

    /**
     * 把会抛异常的仓库调用收敛成 [MusicResult]。
     *
     * 并发拉取时任何一个源抛异常都会取消整个 `coroutineScope`，连带把已经拿到的数据一起丢掉 ——
     * 所以每个分支都必须自己兜住异常。
     */
    private suspend fun <T> safe(block: suspend () -> MusicResult<T>): MusicResult<T> =
        runCatching { block() }.getOrElse { BackendResult.Error(it.message ?: "加载失败", cause = it) }

    private fun <T> MusicResult<List<T>>.dataOrEmpty(): List<T> =
        (this as? BackendResult.Success)?.data.orEmpty()

    private fun MusicResult<*>.errorOrNull(): String? = when (this) {
        is BackendResult.Error -> message
        is BackendResult.Unsupported -> message
        is BackendResult.Success -> null
    }
}
