package cp.player.core.lyrics

import cp.player.core.api.AmllTtmlClient
import cp.player.core.api.MusicApiService
import cp.player.core.lyricsplugin.LyricsPluginService
import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformSupport
import cp.player.core.util.SettingsStorage
import java.io.File

/**
 * 创建歌词来源注册表（jvmMain actual：Android 与桌面共用）。
 *
 * 目录约定（见 `docs/design/UNIFIED_SOURCE_PLUGIN_V3.md` §8）：
 * ```
 * <dataDir>/lyrics-sources/
 *   ├── sources.json     ← 顺序 + 停用表 + 配置（唯一事实源）
 *   └── ...              ← 第三方插件文件仍由 [LyricsPluginService] 管理自己的目录
 * ```
 *
 * @param plugins 第三方插件服务（适配成 [LyricsSource]）；必传
 * @param amllClient AMLL 客户端；null 表示不提供 AMLL 来源（该来源不会出现在列表里）
 * @param api 音源 API（「音源自带歌词」来源用）
 * @param legacySettings 旧设置读取器，**仅用于一次性迁移** `lyrics_source_mode`
 */
actual fun createLyricsSourceRegistry(
    context: PlatformContext,
    plugins: LyricsPluginService,
    amllClient: AmllTtmlClient?,
    api: MusicApiService,
    legacySettings: SettingsStorage?,
): LyricsSourceRegistry = FileLyricsSourceRegistry(
    rootDir = File(PlatformSupport.dataDir(context), "lyrics-sources"),
    plugins = plugins,
    amllClient = amllClient,
    api = api,
    legacySettings = legacySettings,
)
