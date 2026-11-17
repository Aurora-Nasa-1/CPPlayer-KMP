package cp.player.app.presentation.albumdetail

import cp.player.app.AppModel
import cp.player.app.presentation.UiPresenter
import cp.player.core.BackendResult
import cp.player.core.music.AlbumDetail
import cp.player.core.music.AlbumSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: AlbumDetail? = null,
    /** 当前专辑是否已收藏（由 `album/sublist` 全集判定）。 */
    val isFavorite: Boolean = false,
    /** 收藏请求在途：按钮置灰，避免连点叠出互相取消的请求。 */
    val favoritePending: Boolean = false,
)

/**
 * 专辑详情状态机（框架无关）。
 *
 * 收藏全集按账号 / 音源世代失效；收藏切换保持「乐观更新 → 请求 → 失败回滚」。
 */
class AlbumDetailPresenter(
    override val scope: CoroutineScope,
    private val notify: (String) -> Unit = {},
) : UiPresenter<AlbumUiState> {

    private val _state = MutableStateFlow(AlbumUiState())
    override val state: StateFlow<AlbumUiState> = _state.asStateFlow()

    private var loadedId: String? = null

    /**
     * 收藏集合是否已拉到。
     *
     * 上游没有「这张专辑是否已收藏」的单条查询 —— 只能拉 `album/sublist` 全集本地比对。
     * 详情页可能被反复进入 / 退出大播放器后重进组合，所以拉到的结果缓存在模型里，
     * 只有 [AppModel.albumFavoriteGeneration] 真的变了才重新拉。
     */
    private var favoriteIds: Set<String>? = null

    init {
        // 任何地方收藏 / 取消收藏专辑后，本页的收藏态都要跟着变（典型场景：
        // 详情页在双栏右栏、用户在左栏列表里收藏了同一张专辑）。
        // `drop(1)` 跳过订阅时的当前值：首屏的收藏态由 [load] 自己拉，
        // 不跳过的话这里会先白拉一次（且与 load 的那次并发，结果可能乱序覆盖）。
        scope.launch {
            AppModel.albumFavoriteGeneration.drop(1).collect {
                // 自己发的那一次不重拉：本地已经乐观更新过，再拉一遍只会
                // 因网络慢而闪回旧状态（见 AppModel.isLastChangedBySelf）。
                if (AppModel.isLastChangedByResource(loadedId)) return@collect
                favoriteIds = null
                syncFavoriteState()
            }
        }
        // 换账号 ⇒ 收藏集合整份换掉（「已收藏」是账号数据，登录 / 登出 / 切号后
        // 上一账号的判定没有任何意义）。订阅 accountGeneration 而不是 userProfileFlow：
        // 后者启动时恢复资料也会发一次（见 AppModel.accountGeneration 的 KDoc）。
        scope.launch {
            AppModel.accountGeneration.drop(1).collect {
                favoriteIds = null
                syncFavoriteState()
            }
        }
        // 切音源 ⇒ 收藏集合属于旧音源，同样作废。
        scope.launch {
            AppModel.sourceGeneration.drop(1).collect {
                favoriteIds = null
                syncFavoriteState()
            }
        }
    }

    fun load(albumId: String, fallback: AlbumSummary?) {
        if (loadedId == albumId && _state.value.detail != null) return
        loadedId = albumId
        scope.launch {
            _state.value = AlbumUiState(loading = true)
            val result = runCatching { AppModel.musicRepository.getAlbumDetail(albumId) }
                .getOrElse { BackendResult.Error(it.message.orEmpty()) }
            _state.value = when (result) {
                is BackendResult.Success -> AlbumUiState(loading = false, detail = result.data)
                is BackendResult.Error -> AlbumUiState(
                    loading = false,
                    error = result.message,
                    // 拿不到详情时**保留列表页传来的封面**：让用户至少知道自己点开了哪一张。
                    detail = fallback?.let {
                        AlbumDetail(
                            id = it.id,
                            resourceId = it.resourceId,
                            artistResourceId = it.artistResourceId,
                            name = it.name,
                            coverUrl = it.coverUrl,
                            artistName = it.artistName,
                            artistId = it.artistId,
                            publishTimeMs = it.publishTimeMs,
                            company = null,
                            description = null,
                            tracks = emptyList(),
                        )
                    },
                )
                is BackendResult.Unsupported -> AlbumUiState(
                    loading = false,
                    error = result.message,
                )
            }
            // 详情落定后再问收藏态：两个请求互不依赖，但收藏态错了顶多是按钮显示不对，
            // 而详情拿不到整页就是错误态 —— 先保证主要信息。
            syncFavoriteState()
        }
    }

    /**
     * 拉收藏全集并把结果套到当前专辑上。**已拉过就直接用缓存**（[favoriteIds] 非空）。
     *
     * 要点：**空结果同样要缓存**（`emptySet()` 不为 null），否则未登录时每次进详情页
     * 都会重打一次取数 —— 用 `?:` 而不是判 `isNotEmpty` 正是为此。
     *
     * **未登录 / 请求失败一律当作「未收藏」**而不是报错：详情页本身是可看的，
     * 为了一个装饰性开关把整页变成错误态得不偿失（点下去会走 [toggleFavorite]
     * 的登录提示分支，用户仍然知道为什么点了没反应）。
     */
    private suspend fun syncFavoriteState() {
        val albumId = loadedId ?: return
        val ids = favoriteIds ?: runCatching {
            val result = AppModel.musicRepository.getUserAlbums(limit = 300)
            when (result) {
                is BackendResult.Success -> result.data.map { it.resourceId }.toSet()
                else -> emptySet()
            }
        }.getOrElse { emptySet() }.also { favoriteIds = it }
        _state.update { it.copy(isFavorite = albumId in ids) }
    }

    /**
     * 切换当前专辑的收藏态。
     *
     * 顺序是**乐观更新 → 请求 → 失败回滚**（与 `PlaybackControllerImpl.toggleFavoriteFor`
     * 同一套）：收藏是「带结果的动作」，等网络回来才变色会让点击像没生效。
     *
     * 失败时回滚并出声 —— 静默回滚的结果是「按钮弹回去但没有任何解释」。
     */
    fun toggleFavorite() {
        val albumId = loadedId ?: return
        if (_state.value.favoritePending) return
        if (AppModel.userProfileFlow.value == null) {
            notify(AppModel.strings().album.favoriteAlbumNeedLogin)
            return
        }
        val target = !_state.value.isFavorite
        // 乐观更新 + 在途标记：连点由 [AlbumUiState.favoritePending] 挡住，
        // 不挡的话两次点击会各自按「点击那一刻的本地状态」算出同一个目标，
        // 上一次的响应回来又把按钮弹回去。
        _state.update { it.copy(isFavorite = target, favoritePending = true) }
        scope.launch {
            val ok = runCatching { AppModel.musicRepository.subscribeAlbum(albumId, target) }
                .getOrDefault(false)
            val strings = AppModel.strings()
            if (ok) {
                // 收藏全集已经变了：本模型的缓存跟着改，**不必**立刻重拉
                // （subscribeAlbum 已发代际信号，本页自己不会收到重拉）。
                favoriteIds = if (target) favoriteIds?.plus(albumId) else favoriteIds?.minus(albumId)
                _state.update { it.copy(favoritePending = false) }
                notify(strings.album.favoriteAlbumDone(target))
            } else {
                _state.update { it.copy(isFavorite = !target, favoritePending = false) }
                notify(strings.album.favoriteAlbumFailed)
            }
        }
    }
}
