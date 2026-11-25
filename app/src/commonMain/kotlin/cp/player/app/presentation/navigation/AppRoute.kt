package cp.player.app.presentation.navigation

import cp.player.core.music.AlbumSummary
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import cp.player.core.provider.ProviderSettingItemSchema
import cp.player.core.util.currentTimeMillis

/**
 * 框架无关的强类型应用路由定义（Type-safe Navigation Routes）。
 *
 * 将页面跳转契约与具体的 UI 导航库（如 Voyager / Jetpack Navigation / 其它桌面 UI）彻底解耦。
 * 业务组件和页面只声明跳转意图，由导航桥接层负责映射到具体呈现。
 *
 * 本文件必须保持**纯 Kotlin**：不得 import Compose、Voyager 或任何 `ui.*` 类型。
 */
sealed interface AppRoute {
    /** 搜索页 */
    data class Search(val initialQuery: String = "") : AppRoute

    /** 歌单详情页 */
    data class PlaylistDetail(val playlist: PlaylistSummary) : AppRoute

    /** 专辑详情页 */
    data class AlbumDetail(
        val albumId: Long,
        val fallback: AlbumSummary? = null,
    ) : AppRoute

    /** 用户主页 */
    data class UserProfile(
        val uid: Long,
        val displayName: String? = null,
        val resourceId: String = uid.toString(),
    ) : AppRoute

    /** 视频 / MV 播放页 */
    data class VideoPlayer(val mediaId: String, val title: String? = null) : AppRoute

    /** 私信会话 */
    data class Chat(val peerUid: Long, val peerName: String? = null) : AppRoute

    /** 首页生成的歌单（日推 / 心动模式等） */
    data class HomeGeneratedPlaylist(
        val playlist: PlaylistSummary,
        val tracks: List<TrackSummary>,
        val startIndex: Int? = null,
        val kind: HomeGeneratedKind = HomeGeneratedKind.Static,
        val seedTrackId: String? = null,
        val seedPlaylistId: Long? = null,
        val instanceToken: Long = currentTimeMillis(),
    ) : AppRoute

    /** 歌单生成来源。 */
    enum class HomeGeneratedKind { Static, IntelligenceFromDaily }

    /** 媒体库主页 */
    data class Library(val initialPlaylistId: Long? = null) : AppRoute

    /** 媒体库搜索 */
    data class LibrarySearch(val initialQuery: String = "") : AppRoute

    /** 导入外部歌单 */
    data object ImportPlaylist : AppRoute

    /** 统计 / 最近播放总览 */
    data class Insights(val recent: Boolean = false) : AppRoute

    /** 下载管理 */
    data class Downloads(val embedded: Boolean = false) : AppRoute

    /** 本地曲库 */
    data class LocalLibrary(
        val embedded: Boolean = false,
        val initialTab: Int = 0,
    ) : AppRoute

    /** 云盘 */
    data class CloudDrive(val embedded: Boolean = false) : AppRoute

    /** 专辑墙 */
    data class AlbumWall(val initialSource: String = "queue") : AppRoute

    /** 最近播放 */
    data class RecentPlays(val embedded: Boolean = false) : AppRoute

    /** 播放记录上报设置 */
    data object ScrobbleSettings : AppRoute

    /** 音频引擎设置 */
    data object AudioEngineSettings : AppRoute

    /** 音效设置 */
    data object AudioEffectSettings : AppRoute

    /** 存储设置 */
    data object StorageSettings : AppRoute

    /** 歌曲缓存 */
    data object SongCache : AppRoute

    /** 插件中心 */
    data class PluginHub(val section: PluginHubSection = PluginHubSection.Installed) : AppRoute

    /** 插件中心页签。 */
    enum class PluginHubSection { Installed, Discover, Updates, Repositories }

    /** 插件仓库 */
    data object PluginRepository : AppRoute

    /** 音源管理 */
    data class ProviderManagement(val initialTab: Int = 0) : AppRoute

    /** 音源自定义设置 */
    data class ProviderCustomSettings(
        val providerId: String,
        val providerName: String,
        val schemas: List<ProviderSettingItemSchema>,
    ) : AppRoute

    /** 歌词插件设置 */
    data object LyricsPluginSettings : AppRoute

    /** 移动端播放器设置 */
    data class MobilePlayerSettings(val targetSettingId: String? = null) : AppRoute

    /** 一起听 */
    data object ListenTogether : AppRoute

    /** 动态 */
    data class Dynamic(val userId: String? = null, val userName: String? = null) : AppRoute

    /** 设置页 */
    data object Settings : AppRoute

    /** 设置注册表中的入口（由入口 ID 与子项 ID 定位，避免路由层认识具体页面类）。 */
    data class SettingsEntry(
        val entryId: String,
        val targetSettingId: String? = null,
    ) : AppRoute

    /** 外观设置 */
    data class AppearanceSettings(val targetSettingId: String? = null) : AppRoute

    /** 播放设置 */
    data class PlaybackSettings(val targetSettingId: String? = null) : AppRoute

    /** 导航设置 */
    data class NavigationSettings(val targetSettingId: String? = null) : AppRoute

    /** AI 设置 */
    data object AiSettings : AppRoute

    /** 外部访问设置 */
    data object ExternalAccessSettings : AppRoute

    /** 歌词投放设置 */
    data object LyricPushSettings : AppRoute

    /** 后台保活与设备同步 */
    data object StandbySettings : AppRoute

    /** 消息通知设置 */
    data object MessageNotifySettings : AppRoute

    /** 关于 */
    data object About : AppRoute

    /** 诊断 */
    data object Health : AppRoute

    /** 渲染调优 */
    data object RenderTuningSettings : AppRoute

    /** 快捷键设置 */
    data object ShortcutSettings : AppRoute

    /** 新手引导 */
    data class Onboarding(val replay: Boolean = false) : AppRoute

    /** 主壳层 */
    data object Main : AppRoute

    /** 账号页 */
    data object Account : AppRoute

    /** 消息页 */
    data object Messages : AppRoute

    /** 全屏播放页 */
    data object Player : AppRoute

    /** 私人 FM */
    data object PersonalFm : AppRoute

    /** 评论弹窗/页 */
    data class Comment(val resourceId: String, val resourceType: String) : AppRoute
}

/**
 * 框架无关的导航控制器接口。
 */
interface AppNavigator {
    /** 当前内容栈深度（根页为 1）。 */
    val depth: Int

    /** 当前栈顶页面的稳定标识；栈为空时为 null。 */
    val currentKey: String?

    /** 在当前内容栈压入新路由。 */
    fun push(route: AppRoute)

    /** 压入整窗路由（会盖住桌面侧栏；播放页等全屏页面使用）。 */
    fun pushRoot(route: AppRoute)

    /** 替换当前路由。 */
    fun replace(route: AppRoute)

    /** 清空当前栈并替换为指定路由。 */
    fun replaceAll(route: AppRoute)

    /** 弹出当前路由，返回是否成功弹出。 */
    fun pop(): Boolean

    /** 弹出到根路由。 */
    fun popToRoot()
}
