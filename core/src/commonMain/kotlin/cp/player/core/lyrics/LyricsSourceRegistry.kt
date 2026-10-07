package cp.player.core.lyrics

/**
 * 歌词来源注册表 —— 「有哪些来源」+「按什么顺序找」+「哪些开着」的**唯一事实源**。
 *
 * ### 为什么要独立于 `LyricsSource` 列表
 * 改造前「顺序」有两个隐式来源：`LyricsSourceMode`（三档枚举）与插件的目录名字典序。
 * 两者都不可见、不可控。这里把顺序提升为显式数据（`sources.json` 的 `order` 数组），
 * UI 只需读写它，取词链路只需读它。
 *
 * ### 契约
 * - [orderedSources] **只返回已启用的来源**，顺序即优先级（靠前先试）。
 * - 内置来源永远在册（用户只能启用/停用与排序，不能删除）。
 * - 第三方来源由 [reload] 后出现在列表里。
 */
interface LyricsSourceRegistry {

    /** 当前**已启用**的来源，按用户排序。 */
    suspend fun orderedSources(): List<LyricsSource>

    /** 当前**全部**来源（含停用），按用户排序 —— 管理页要用它把停用的也显示出来。 */
    suspend fun allSources(): List<LyricsSourceEntry>

    /** 重排（参数是**全部**来源的 id，含停用项；未列出的追加在末尾，保持原相对顺序）。 */
    suspend fun setOrder(orderedIds: List<String>)

    /** 启用 / 停用。内置来源只改状态、不可移除。 */
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** 重新扫描来源（导入 / 删除插件、换音源后调用）。 */
    suspend fun reload()

    /** 展示名（内置源走文案层本地化，第三方插件用 manifest 里的名字）。 */
    fun displayNameOf(source: LyricsSource): String
}

/**
 * 管理页看到的一条来源。
 *
 * @param localizeName true = `name` 只是键，界面要查文案层取本地化名
 * @param removable 能否删除（内置来源 false）
 */
data class LyricsSourceEntry(
    val id: String,
    val name: String,
    val localizeName: Boolean,
    val bundled: Boolean,
    val removable: Boolean,
    val capabilities: Set<LyricsCapability>,
    val enabled: Boolean,
    val description: String = "",
    val author: String = "",
    val version: String = "",
    /** 该来源支持的自定义配置项（第三方插件独有；内置来源为空）。 */
    val configFields: List<LyricsSourceConfigField> = emptyList(),
)

/** 配置项的类型（与插件 manifest 的 `type` 对齐，但**不依赖** lyricsplugin 包）。 */
enum class LyricsSourceConfigFieldType { TEXT, PASSWORD, NUMBER, SWITCH, DROPDOWN, TEXTAREA, MARKDOWN }

/** 一条来源的自定义配置项（宿主渲染成表单）。 */
data class LyricsSourceConfigField(
    val key: String,
    val title: String,
    val summary: String? = null,
    val group: String = "",
    val type: LyricsSourceConfigFieldType = LyricsSourceConfigFieldType.TEXT,
    val required: Boolean = false,
    val defaultValue: String = "",
    val options: List<LyricsSourceConfigOption> = emptyList(),
)

data class LyricsSourceConfigOption(
    val value: String,
    val label: String,
    val summary: String = "",
)

/**
 * 创建歌词来源注册表（`expect` 工厂，实现见各平台源集）。
 *
 * 目录约定（见 `docs/design/UNIFIED_SOURCE_PLUGIN_V3.md` §8）：
 * ```
 * <dataDir>/lyrics-sources/
 *   ├── sources.json     ← 顺序 + 停用表 + 配置（唯一事实源）
 *   └── ...              ← 第三方插件文件仍由 [LyricsPluginService] 管理自己的目录
 * ```
 *
 * @param plugins 第三方插件服务（适配成 [LyricsSource]）
 * @param amllClient AMLL 客户端；null 表示不提供 AMLL 来源（该来源不会出现在列表里）
 * @param api 音源 API（「音源自带歌词」来源用）
 * @param legacySettings 旧设置读取器，**仅用于一次性迁移** `lyrics_source_mode`
 */
expect fun createLyricsSourceRegistry(
    context: cp.player.core.util.PlatformContext,
    plugins: cp.player.core.lyricsplugin.LyricsPluginService,
    amllClient: cp.player.core.api.AmllTtmlClient?,
    api: cp.player.core.api.MusicApiService,
    legacySettings: cp.player.core.util.SettingsStorage? = null,
): LyricsSourceRegistry
