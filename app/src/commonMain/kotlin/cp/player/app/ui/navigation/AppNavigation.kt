package cp.player.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import cp.player.app.ui.navigation.RoutePage
import cp.player.app.presentation.navigation.AppNavigator
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.screen.AccountScreen
import cp.player.app.ui.screen.AlbumDetailScreen
import cp.player.app.ui.screen.AlbumWallScreen
import cp.player.app.ui.screen.AudioEffectSettingsScreen
import cp.player.app.ui.screen.AudioEngineSettingsScreen
import cp.player.app.ui.screen.ChatScreen
import cp.player.app.ui.screen.CloudDriveScreen
import cp.player.app.ui.screen.CommentScreen
import cp.player.app.ui.screen.DownloadsScreen
import cp.player.app.ui.screen.DynamicScreen
import cp.player.app.ui.screen.HomeGeneratedPlaylistScreen
import cp.player.app.ui.screen.ImportPlaylistScreen
import cp.player.app.ui.screen.InsightsScreen
import cp.player.app.ui.screen.LibraryScreen
import cp.player.app.ui.screen.LibrarySearchScreen
import cp.player.app.ui.screen.ListenTogetherScreen
import cp.player.app.ui.screen.LocalLibraryScreen
import cp.player.app.ui.screen.LyricsPluginSettingsScreen
import cp.player.app.ui.screen.MessagesScreen
import cp.player.app.ui.screen.MobilePlayerSettingsScreen
import cp.player.app.ui.screen.PlayerScreen
import cp.player.app.ui.screen.PersonalFmScreen
import cp.player.app.ui.screen.PlaylistDetailScreen
import cp.player.app.ui.screen.PluginHubScreen
import cp.player.app.ui.screen.PluginRepositoryScreen
import cp.player.app.ui.screen.ProviderCustomSettingsScreen
import cp.player.app.ui.screen.ProviderManagementScreen
import cp.player.app.ui.screen.RecentPlaysScreen
import cp.player.app.ui.screen.ScrobbleSettingsScreen
import cp.player.app.ui.screen.SearchScreen
import cp.player.app.ui.screen.SettingsScreen
import cp.player.app.ui.screen.SongCacheScreen
import cp.player.app.ui.screen.StorageSettingsScreen
import cp.player.app.ui.screen.UserProfileScreen
import cp.player.app.ui.screen.VideoPlayerScreen
import cp.player.app.ui.util.UiEvents

/**
 * UI 树中的通用导航控制器。
 *
 * 页面与子组件通过本 Local 和 [AppRoute] 发出跳转。缺少 Provider 时给出操作提示。
 */
val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> { UnavailableAppNavigator }

private object UnavailableAppNavigator : AppNavigator {
    override val depth: Int = 0
    override val currentKey: String? = null

    override fun push(route: AppRoute) {
        UiEvents.notify("暂时无法打开这个页面")
    }

    override fun pushRoot(route: AppRoute) {
        UiEvents.notify("暂时无法打开这个页面")
    }

    override fun replace(route: AppRoute) {
        UiEvents.notify("暂时无法打开这个页面")
    }

    override fun replaceAll(route: AppRoute) {
        UiEvents.notify("暂时无法打开这个页面")
    }

    override fun pop(): Boolean {
        UiEvents.notify("导航尚未就绪")
        return false
    }

    override fun popToRoot() {
        UiEvents.notify("导航尚未就绪")
    }
}

/**
 * 将通用的 [AppRoute] 转换为 应用的 [RoutePage]。仅本适配层允许引用具体页面类。
 */
fun AppRoute.toRoutePage(): RoutePage = when (this) {
    is AppRoute.Search -> SearchScreen(initialQuery)
    is AppRoute.PlaylistDetail -> PlaylistDetailScreen(playlist)
    is AppRoute.AlbumDetail -> AlbumDetailScreen(albumId, fallback)
    is AppRoute.UserProfile -> UserProfileScreen(uid, displayName, resourceId)
    is AppRoute.VideoPlayer -> VideoPlayerScreen(mediaId, title)
    is AppRoute.Chat -> ChatScreen(peerUid, peerName)
    is AppRoute.HomeGeneratedPlaylist -> HomeGeneratedPlaylistScreen(
        playlist = playlist,
        initialTracks = tracks,
        startIndex = startIndex,
        kind = kind,
        seedTrackId = seedTrackId,
        seedPlaylistId = seedPlaylistId,
        instanceToken = instanceToken,
    )
    is AppRoute.Library -> LibraryScreen(initialPlaylistId)
    is AppRoute.LibrarySearch -> LibrarySearchScreen(initialQuery)
    AppRoute.ImportPlaylist -> ImportPlaylistScreen()
    is AppRoute.Insights -> InsightsScreen(
        if (recent) InsightsScreen.TAB_RECENT else InsightsScreen.TAB_OVERVIEW
    )
    is AppRoute.Downloads -> DownloadsScreen(embedded)
    is AppRoute.LocalLibrary -> LocalLibraryScreen(embedded, initialTab)
    is AppRoute.CloudDrive -> CloudDriveScreen(embedded)
    is AppRoute.AlbumWall -> AlbumWallScreen(initialSource)
    is AppRoute.RecentPlays -> RecentPlaysScreen(embedded)
    AppRoute.ScrobbleSettings -> ScrobbleSettingsScreen()
    AppRoute.AudioEngineSettings -> AudioEngineSettingsScreen()
    AppRoute.AudioEffectSettings -> AudioEffectSettingsScreen()
    AppRoute.StorageSettings -> StorageSettingsScreen()
    AppRoute.SongCache -> SongCacheScreen()
    is AppRoute.PluginHub -> PluginHubScreen(
        when (section) {
            AppRoute.PluginHubSection.Installed -> cp.player.app.ui.screen.PluginHubView.INSTALLED
            AppRoute.PluginHubSection.Discover -> cp.player.app.ui.screen.PluginHubView.DISCOVER
            AppRoute.PluginHubSection.Updates -> cp.player.app.ui.screen.PluginHubView.UPDATES
            AppRoute.PluginHubSection.Repositories -> cp.player.app.ui.screen.PluginHubView.REPOSITORIES
        }
    )
    AppRoute.PluginRepository -> PluginRepositoryScreen()
    is AppRoute.ProviderManagement -> ProviderManagementScreen(initialTab)
    is AppRoute.ProviderCustomSettings -> ProviderCustomSettingsScreen(providerId, providerName, schemas)
    AppRoute.LyricsPluginSettings -> LyricsPluginSettingsScreen()
    is AppRoute.MobilePlayerSettings -> MobilePlayerSettingsScreen(targetSettingId)
    AppRoute.ListenTogether -> ListenTogetherScreen()
    is AppRoute.Dynamic -> DynamicScreen(userId, userName)
    AppRoute.Settings -> SettingsScreen()
    is AppRoute.SettingsEntry -> cp.player.app.ui.screen.settingsEntries()
        .firstOrNull { it.id == entryId }
        ?.screen(targetSettingId)
        ?: throw IllegalStateException("未知设置入口: $entryId")
    is AppRoute.AppearanceSettings -> cp.player.app.ui.screen.AppearanceSettingsScreen(targetSettingId)
    is AppRoute.PlaybackSettings -> cp.player.app.ui.screen.PlaybackSettingsScreen(targetSettingId)
    is AppRoute.NavigationSettings -> cp.player.app.ui.screen.NavigationSettingsScreen(targetSettingId)
    AppRoute.AiSettings -> cp.player.app.ui.screen.AiSettingsScreen()
    AppRoute.ExternalAccessSettings -> cp.player.app.ui.screen.ExternalAccessSettingsScreen()
    AppRoute.LyricPushSettings -> cp.player.app.ui.screen.LyricPushSettingsScreen()
    AppRoute.StandbySettings -> cp.player.app.ui.screen.StandbySettingsScreen()
    AppRoute.MessageNotifySettings -> cp.player.app.ui.screen.MessageNotifySettingsScreen()
    AppRoute.About -> cp.player.app.ui.screen.AboutScreen()
    AppRoute.Health -> cp.player.app.ui.screen.HealthScreen()
    AppRoute.RenderTuningSettings -> cp.player.app.ui.screen.RenderTuningSettingsScreen()
    AppRoute.ShortcutSettings -> cp.player.app.ui.screen.ShortcutSettingsScreen()
    is AppRoute.Onboarding -> cp.player.app.ui.screen.OnboardingScreen(replay)
    AppRoute.Main -> cp.player.app.ui.screen.MainScreen()
    AppRoute.Account -> AccountScreen()
    AppRoute.Messages -> MessagesScreen()
    AppRoute.Player -> PlayerScreen()
    AppRoute.PersonalFm -> PersonalFmScreen()
    is AppRoute.Comment -> CommentScreen(resourceId, resourceType)
}

/**
 * 将 [PageStack] 适配为框架无关的 [AppNavigator]。
 *
 * @param navigator 当前内容栈（桌面宽屏为侧栏旁的内嵌栈）
 * @param rootNavigator 根栈；[pushRoot] 用它承载播放页等全屏路由
 */
class StackAppNavigator(
    private val navigator: PageStack,
    private val rootNavigator: PageStack = navigator,
    private val playerOverlay: PlayerOverlayController? = null,
) : AppNavigator {
    override val depth: Int
        get() = runCatching { navigator.size }.getOrDefault(0)

    override val currentKey: String?
        get() = runCatching { navigator.lastItem.key }.getOrNull()

    override fun push(route: AppRoute) {
        if (route is AppRoute.Player && playerOverlay != null) {
            playerOverlay.open()
            return
        }
        playerOverlay?.closeImmediately()
        runCatching { navigator.push(route.toRoutePage()) }
            .onFailure { UiEvents.notify("暂时无法打开这个页面") }
    }

    override fun pushRoot(route: AppRoute) {
        if (route is AppRoute.Player && playerOverlay != null) {
            playerOverlay.open()
            return
        }
        playerOverlay?.closeImmediately()
        runCatching { rootNavigator.push(route.toRoutePage()) }
            .onFailure { UiEvents.notify("暂时无法打开这个页面") }
    }

    override fun replace(route: AppRoute) {
        if (route is AppRoute.Player && playerOverlay != null) {
            playerOverlay.open()
            return
        }
        playerOverlay?.closeImmediately()
        runCatching { navigator.replace(route.toRoutePage()) }
            .onFailure { UiEvents.notify("暂时无法打开这个页面") }
    }

    override fun replaceAll(route: AppRoute) {
        if (route is AppRoute.Player && playerOverlay != null) {
            playerOverlay.open()
            return
        }
        playerOverlay?.closeImmediately()
        runCatching { navigator.replaceAll(route.toRoutePage()) }
            .onFailure { UiEvents.notify("暂时无法打开这个页面") }
    }

    override fun pop(): Boolean {
        if (navigator.size <= 1) {
            UiEvents.notify("已经在最外层了")
            return false
        }
        return runCatching {
            navigator.pop()
            true
        }.getOrElse {
            UiEvents.notify("已经在最外层了")
            false
        }
    }

    override fun popToRoot() {
        playerOverlay?.closeImmediately()
        runCatching { navigator.popUntilRoot() }
            .onFailure { UiEvents.notify("暂时无法返回主界面") }
    }
}

/**
 * 在内容栈下提供导航入口，保留覆盖整窗的根栈入口。
 */
@Composable
fun ProvideAppNavigator(
    navigator: PageStack,
    rootNavigator: PageStack = navigator,
    content: @Composable () -> Unit,
) {
    val playerOverlay = LocalPlayerOverlayController.current
    val appNavigator = remember(navigator, rootNavigator, playerOverlay) {
        StackAppNavigator(navigator, rootNavigator, playerOverlay)
    }
    CompositionLocalProvider(
        LocalAppNavigator provides appNavigator,
        content = content,
    )
}
