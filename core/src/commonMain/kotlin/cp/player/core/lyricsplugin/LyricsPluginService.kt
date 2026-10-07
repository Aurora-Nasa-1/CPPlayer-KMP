package cp.player.core.lyricsplugin

import cp.player.core.playback.SyncedLyricLine
import cp.player.core.util.PlatformContext

/**
 * 歌词源插件的**宿主服务**（commonMain 契约）。
 *
 * 实现位于 jvmMain（Android 与桌面共用），基于 Lyrico Plugin API 运行 JS 插件。
 * 前端只依赖这个接口，不碰 JS 运行时细节。
 *
 * 设计上对齐本仓库既有的 `LocalMediaSource`：接口在 commonMain、实现由 `expect` 工厂创建。
 */
interface LyricsPluginService {

    /** 当前已导入 / 内置的全部插件（含启用状态），供管理页展示。 */
    suspend fun listSources(): List<LyricsPluginSourceInfo>

    /** 启用 / 停用某个插件。 */
    suspend fun setEnabled(id: String, enabled: Boolean)

    /**
     * 从 zip 文件导入插件（可含多个插件目录）。
     *
     * @param zipPath 本地 zip 绝对路径
     * @return 导入成功的插件 id 列表
     */
    suspend fun importPluginZip(zipPath: String): List<String>

    /** 删除某个导入的插件（内置插件不可删）。 */
    suspend fun deletePlugin(id: String): Boolean

    /** 读取某个插件的自定义配置（键值对）。 */
    suspend fun pluginConfig(id: String): Map<String, String>

    /** 写入某个插件的一个配置项。 */
    suspend fun setPluginConfigValue(id: String, key: String, value: String)

    /**
     * 用已启用的插件检索歌词。
     *
     * 多插件并发检索，返回**第一个命中**的结果（按插件顺序）。
     * 无启用插件 / 全部未命中时返回 null。
     */
    suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String,
        durationMs: Long = 0L,
    ): PluginLyricsOutcome?

    /** 释放 JS 运行时与 HTTP 客户端。 */
    fun close()
}

/** 一个插件源的可展示信息。 */
data class LyricsPluginSourceInfo(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val description: String,
    val capabilities: Set<PluginCapability>,
    val enabled: Boolean,
    val bundled: Boolean,
)

/**
 * 一次插件取词的结果。
 *
 * @param lines 已解析为宿主统一模型的歌词行
 * @param hasWordLevel 是否含逐字时间戳（UI 据此决定是否用卡拉 OK 样式）
 */
data class PluginLyricsOutcome(
    val sourceId: String,
    val sourceName: String,
    val lines: List<SyncedLyricLine>,
    val hasWordLevel: Boolean,
)

/**
 * 创建歌词源插件服务。
 *
 * - Android（androidMain actual）：插件目录落在应用私有目录
 * - Desktop（desktopMain actual）：插件目录落在 `~/.cpplayer/lyrics-plugins/`
 *
 * @param cacheDir 插件 `Platform.cache.*` 的落盘根目录；为 null 时只用内存缓存
 */
expect fun createLyricsPluginService(context: PlatformContext, cacheDir: String? = null): LyricsPluginService
