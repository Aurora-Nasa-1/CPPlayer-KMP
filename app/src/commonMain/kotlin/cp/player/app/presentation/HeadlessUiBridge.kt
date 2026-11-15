package cp.player.app.presentation

import cp.player.app.AppModel
import cp.player.app.presentation.search.SearchPresenter
import cp.player.app.repository.MusicRepository
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * 面向纯前端 / 非 Compose 客户端的强类型呈现网关（Headless UI Bridge）。
 *
 * 核心设计原则：
 * 1. **零 JSON 解析**：所有输入输出均为强类型数据类与方法调用；
 * 2. **极薄前端**：复杂的状态机、缓存策略（SWR）、切歌算法由中台持有，前端只管订阅渲染；
 * 3. **UI 框架无关**：不依赖 Compose 或 Voyager，可直接用于 CLI、WinUI 3、Flutter 绑定、原生窗口等。
 */
class HeadlessUiBridge(
    private val playbackControllerProvider: () -> PlaybackController = { AppModel.playback },
    private val repositoryProvider: () -> MusicRepository = { AppModel.musicRepository },
) {

    /** 当前播放器的全量 UI 渲染状态流（曲目、封面、进度、播放状态等）。 */
    val playerState: StateFlow<PlaybackUiState>
        get() = playbackControllerProvider().state

    /** 当前已收藏歌曲的 ID 集合流。 */
    val likedSongIds: StateFlow<Set<String>>
        get() = playbackControllerProvider().likedIds

    // ================= 播放控制指令 =================

    fun togglePlayPause() {
        playbackControllerProvider().togglePlayPause()
    }

    fun pause() {
        playbackControllerProvider().pause()
    }

    fun resume() {
        playbackControllerProvider().resume()
    }

    fun skipNext() {
        playbackControllerProvider().skipNext()
    }

    fun skipPrevious() {
        playbackControllerProvider().skipPrevious()
    }

    fun seekTo(positionMs: Long) {
        playbackControllerProvider().seekTo(positionMs)
    }

    suspend fun play(mediaId: String) {
        playbackControllerProvider().play(mediaId)
    }

    suspend fun playQueue(mediaIds: List<String>, startIndex: Int = 0) {
        playbackControllerProvider().playQueue(mediaIds, startIndex)
    }

    suspend fun toggleFavorite() {
        playbackControllerProvider().toggleFavorite()
    }

    suspend fun toggleFavoriteFor(mediaId: String) {
        playbackControllerProvider().toggleFavoriteFor(mediaId)
    }

    // ================= 呈现器工厂 =================

    /**
     * 为新页面创建框架无关的搜索呈现器。
     *
     * @param initialQuery 初始搜索词
     * @param scope 页面的生命周期协程作用域（页面关闭时自动 cancel）
     */
    fun createSearchPresenter(
        initialQuery: String = "",
        scope: CoroutineScope,
        settingsProvider: () -> cp.player.core.util.SettingsStorage = { AppModel.settings },
        activeProviderProvider: () -> cp.player.core.provider.BackendProvider? = { AppModel.activeProvider() },
        localItemsProvider: () -> List<cp.player.core.media.LocalMediaItem> =
            { AppModel.localMedia.items().value },
        suggestionsLoader: suspend (String) -> List<String>? =
            { query -> cp.player.app.presentation.search.loadSearchSuggestions(query) },
    ): SearchPresenter {
        return SearchPresenter(
            initialQuery = initialQuery,
            scope = scope,
            settingsProvider = settingsProvider,
            repositoryProvider = repositoryProvider,
            activeProviderProvider = activeProviderProvider,
            localItemsProvider = localItemsProvider,
            suggestionsLoader = suggestionsLoader,
        )
    }

    /**
     * 为新页面创建框架无关的媒体库呈现器。
     */
    fun createLibraryPresenter(
        initialPlaylistId: Long? = null,
        scope: CoroutineScope,
        notify: (String) -> Unit = {},
    ): cp.player.app.presentation.library.LibraryPresenter {
        return cp.player.app.presentation.library.LibraryPresenter(
            initialPlaylistId = initialPlaylistId,
            scope = scope,
            notify = notify,
        )
    }

    /** 为新页面创建框架无关的歌单详情呈现器。 */
    fun createPlaylistDetailPresenter(
        scope: CoroutineScope,
        notify: (String) -> Unit = {},
        playCoverFlight: (cp.player.core.music.TrackSummary) -> Unit = {},
    ): cp.player.app.presentation.playlistdetail.PlaylistDetailPresenter {
        return cp.player.app.presentation.playlistdetail.PlaylistDetailPresenter(
            scope = scope,
            notify = notify,
            playCoverFlight = playCoverFlight,
        )
    }

    /** 为新页面创建框架无关的首页呈现器。 */
    fun createHomePresenter(
        scope: CoroutineScope,
        loadDiscovery: Boolean = true,
        notify: (String) -> Unit = {},
    ): cp.player.app.presentation.home.HomePresenter {
        return cp.player.app.presentation.home.HomePresenter(
            scope = scope,
            loadDiscovery = loadDiscovery,
            notify = notify,
        )
    }

    /** 为新页面创建框架无关的评论呈现器。 */
    fun createCommentPresenter(
        id: String,
        type: String,
        scope: CoroutineScope,
    ): cp.player.app.presentation.comment.CommentPresenter {
        return cp.player.app.presentation.comment.CommentPresenter(
            id = id,
            type = type,
            scope = scope,
        )
    }

    /** 为新页面创建框架无关的动态流呈现器。 */
    fun createDynamicPresenter(
        scope: CoroutineScope,
        notify: (String) -> Unit = {},
    ): cp.player.app.presentation.dynamic.DynamicPresenter {
        return cp.player.app.presentation.dynamic.DynamicPresenter(
            scope = scope,
            notify = notify,
        )
    }

    /** 为新页面创建框架无关的下载管理呈现器。 */
    fun createDownloadsPresenter(
        scope: CoroutineScope,
        notify: (String) -> Unit = {},
    ): cp.player.app.presentation.downloads.DownloadsPresenter {
        return cp.player.app.presentation.downloads.DownloadsPresenter(
            scope = scope,
            notify = notify,
        )
    }

    /** 为新页面创建框架无关的外部歌单导入呈现器。 */
    fun createImportPlaylistPresenter(
        scope: CoroutineScope,
        initialState: cp.player.app.presentation.importplaylist.ImportPlaylistUiState =
            cp.player.app.presentation.importplaylist.ImportPlaylistUiState(),
        notify: (String) -> Unit = {},
    ): cp.player.app.presentation.importplaylist.ImportPlaylistPresenter {
        return cp.player.app.presentation.importplaylist.ImportPlaylistPresenter(
            scope = scope,
            initialState = initialState,
            notify = notify,
        )
    }

    /** 为新页面创建框架无关的歌单歌曲搜索呈现器。 */
    fun createLibrarySearchPresenter(
        scope: CoroutineScope,
    ): cp.player.app.presentation.librarysearch.LibrarySearchPresenter {
        return cp.player.app.presentation.librarysearch.LibrarySearchPresenter(scope = scope)
    }

    /** 为新页面创建框架无关的歌曲缓存呈现器。 */
    fun createSongCachePresenter(
        scope: CoroutineScope,
        notify: (String) -> Unit = {},
    ): cp.player.app.presentation.songcache.SongCachePresenter {
        return cp.player.app.presentation.songcache.SongCachePresenter(
            scope = scope,
            notify = notify,
        )
    }

    /** 为新页面创建框架无关的存储管理呈现器。 */
    fun createStorageSettingsPresenter(
        scope: CoroutineScope,
        notify: (String) -> Unit = {},
    ): cp.player.app.presentation.storage.StorageSettingsPresenter {
        return cp.player.app.presentation.storage.StorageSettingsPresenter(
            scope = scope,
            notify = notify,
        )
    }

    /** 为新页面创建框架无关的专辑详情呈现器。 */
    fun createAlbumDetailPresenter(
        scope: CoroutineScope,
        notify: (String) -> Unit = {},
    ): cp.player.app.presentation.albumdetail.AlbumDetailPresenter {
        return cp.player.app.presentation.albumdetail.AlbumDetailPresenter(
            scope = scope,
            notify = notify,
        )
    }

    /** 为新页面创建框架无关的云盘呈现器。 */
    fun createCloudDrivePresenter(
        scope: CoroutineScope,
        notify: (String) -> Unit = {},
        playCoverFlight: (cp.player.core.music.TrackSummary) -> Unit = {},
    ): cp.player.app.presentation.clouddrive.CloudDrivePresenter {
        return cp.player.app.presentation.clouddrive.CloudDrivePresenter(
            scope = scope,
            notify = notify,
            playCoverFlight = playCoverFlight,
        )
    }

    /** 为新页面创建框架无关的用户 / 歌手主页呈现器。 */
    fun createUserProfilePresenter(
        scope: CoroutineScope,
    ): cp.player.app.presentation.userprofile.UserProfilePresenter {
        return cp.player.app.presentation.userprofile.UserProfilePresenter(scope = scope)
    }

    /** 为新页面创建框架无关的音源管理呈现器。 */
    fun createProviderManagementPresenter(
        scope: CoroutineScope,
    ): cp.player.app.presentation.providermanagement.ProviderManagementPresenter {
        return cp.player.app.presentation.providermanagement.ProviderManagementPresenter(scope = scope)
    }

    /** 为新页面创建框架无关的账号 / 登录呈现器。 */
    fun createAccountPresenter(
        account: cp.player.app.i18n.AccountStrings,
        scope: CoroutineScope,
    ): cp.player.app.presentation.account.AccountPresenter {
        return cp.player.app.presentation.account.AccountPresenter(
            account = account,
            scope = scope,
        )
    }

    /** 为新页面创建框架无关的私信呈现器。 */
    fun createChatPresenter(
        scope: CoroutineScope,
    ): cp.player.app.presentation.chat.ChatPresenter {
        return cp.player.app.presentation.chat.ChatPresenter(scope = scope)
    }

    /** 为新页面创建框架无关的消息列表呈现器。 */
    fun createMessagesPresenter(
        scope: CoroutineScope,
    ): cp.player.app.presentation.messages.MessagesPresenter {
        return cp.player.app.presentation.messages.MessagesPresenter(scope = scope)
    }

    /** 为新页面创建框架无关的插件仓库呈现器。 */
    fun createPluginRepositoryPresenter(
        scope: CoroutineScope,
    ): cp.player.app.presentation.pluginrepository.PluginRepositoryPresenter {
        return cp.player.app.presentation.pluginrepository.PluginRepositoryPresenter(scope = scope)
    }

    /** 为新页面创建框架无关的消息通知设置呈现器。 */
    fun createMessageNotifyPresenter(
        scope: CoroutineScope,
    ): cp.player.app.presentation.messagenotify.MessageNotifyPresenter {
        return cp.player.app.presentation.messagenotify.MessageNotifyPresenter(scope = scope)
    }

    /** 为新页面创建框架无关的后端错误处理呈现器。 */
    fun createBackendErrorPresenter(
        scope: CoroutineScope,
    ): cp.player.app.presentation.backenderror.BackendErrorPresenter {
        return cp.player.app.presentation.backenderror.BackendErrorPresenter(scope = scope)
    }
}
