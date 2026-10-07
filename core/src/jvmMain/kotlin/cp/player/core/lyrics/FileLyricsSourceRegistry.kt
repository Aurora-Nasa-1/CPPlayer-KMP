package cp.player.core.lyrics

import cp.player.core.api.AmllTtmlClient
import cp.player.core.api.LyricsSourceMode
import cp.player.core.api.MusicApiService
import cp.player.core.lyricsplugin.LyricsPluginService
import cp.player.core.util.SettingsStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 歌词来源注册表的默认实现（jvmMain：Android 与桌面共用）。
 *
 * ### 持久化：`sources.json` 是唯一事实源
 * ```
 * <dataDir>/lyrics-sources/
 *   ├── sources.json        ← 顺序 + 停用表 + 各来源配置
 *   └── <pluginId>/         ← 第三方插件文件（由 [LyricsPluginService] 管理）
 * ```
 *
 * ### 为什么不再用 `LyricsSourceMode`
 * 旧的三档枚举（`provider_only` / `amll_first` / `amll_only`）只能表达 3 种顺序，
 * 且无法表达「插件 X 插在 AMLL 之后、插件 Y 之外」。现在顺序是**一个数组**，
 * 三档只是它的三个特例。旧键在首次启动时被**读取并换算**（见 [migrateIfNeeded]），
 * 之后不再写入 —— 但**不删除**，以支持回滚到旧版本。
 */
class FileLyricsSourceRegistry(
    private val rootDir: File,
    private val plugins: LyricsPluginService,
    private val amllClient: AmllTtmlClient?,
    private val api: MusicApiService,
    /** 旧设置读取器：只在迁移时用（`lyrics_source_mode`）。 */
    private val legacySettings: SettingsStorage? = null,
) : LyricsSourceRegistry {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Serializable
    internal data class SourcesState(
        val version: Int = STATE_VERSION,
        /** **全部**来源的 id，顺序即优先级（含停用的）。 */
        val order: List<String> = emptyList(),
        /** 停用的来源 id（内置也能停用）。 */
        val disabled: List<String> = emptyList(),
        /** 各来源的自定义配置（仅第三方插件会用到）。 */
        val configs: Map<String, Map<String, String>> = emptyMap(),
    )

    private val stateFile: File get() = File(rootDir, "sources.json")

    /** 已载入的第三方来源快照（[reload] 刷新）。 */
    private var pluginSources: List<LyricsSource> = emptyList()

    /** 内置三源：恒定在册。 */
    private val builtins: List<LyricsSource> by lazy {
        buildList {
            add(SidecarLyricsSource())
            amllClient?.let { add(AmllLyricsSource(it)) }
            add(ProviderLyricsSource(api))
        }
    }

    private var state: SourcesState = SourcesState()

    /** 首次构造时载入 + 迁移。同步执行（构造在 `by lazy` 里，不阻塞主线程之外的事）。 */
    init {
        state = readState()
        migrateIfNeeded()
    }

    // ============ 读 ============

    override suspend fun orderedSources(): List<LyricsSource> {
        refreshPluginSources()
        return flatten(effectiveOrder()).filter { it.id !in state.disabled }
    }

    override suspend fun allSources(): List<LyricsSourceEntry> {
        refreshPluginSources()
        val byId = flatten(effectiveOrder()).associateBy { it.id }
        return effectiveOrder().mapNotNull { id ->
            val source = byId[id] ?: return@mapNotNull null
            LyricsSourceEntry(
                id = source.id,
                name = source.name,
                localizeName = source.localizeName,
                bundled = source.bundled,
                removable = !source.bundled,
                capabilities = source.capabilities,
                enabled = id !in state.disabled,
                description = pluginInfosMap[id]?.description.orEmpty(),
                author = pluginInfosMap[id]?.author.orEmpty(),
                version = pluginInfosMap[id]?.version.orEmpty(),
                configFields = pluginInfosMap[id]?.configFields.orEmpty(),
            )
        }
    }

    override suspend fun setOrder(orderedIds: List<String>) {
        refreshPluginSources()
        val known = effectiveOrder().toSet()
        // 未知 id 丢弃；未列出的追加在末尾（保持原相对顺序）——
        // 这样「新装了插件」不会因为没进 order 就消失。
        val knownInOrder = orderedIds.filter { it in known }.distinct()
        val rest = effectiveOrder().filterNot { it in knownInOrder }
        state = state.copy(order = knownInOrder + rest)
        writeState()
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        val disabled = if (enabled) state.disabled - id else (state.disabled + id).distinct()
        state = state.copy(disabled = disabled)
        writeState()
    }

    override suspend fun reload() {
        pluginSources = emptyList()
        refreshPluginSources()
    }

    override fun displayNameOf(source: LyricsSource): String = source.name

    // ============ 配置（第三方插件透传） ============

    suspend fun configOf(id: String): Map<String, String> = state.configs[id].orEmpty()

    suspend fun setConfigValue(id: String, key: String, value: String) {
        val next = state.configs[id].orEmpty().toMutableMap()
        next[key] = value
        state = state.copy(configs = state.configs + (id to next))
        writeState()
    }

    // ============ 内部 ============

    /**
     * 「有效顺序」= （state.order ∪ 实际来源）去重，按 order 优先、新来源追加。
     *
     * ⚠️ **必须**做这一步：新导入的插件不在 `order` 里，若直接按 order 返回，它会**永远不出现**
     * —— 用户导入成功却看不到任何变化。
     */
    private fun effectiveOrder(): List<String> {
        val actual = (builtins + pluginSources).map { it.id }
        val inOrder = state.order.filter { it in actual }
        val missing = actual.filterNot { it in inOrder }
        return inOrder + missing
    }

    private fun flatten(ids: List<String>): List<LyricsSource> {
        val all = builtins + pluginSources
        val byId = all.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    private var pluginInfosMap: Map<String, PluginSourceMeta> = emptyMap()

    private fun refreshPluginSources() {
        if (pluginSources.isNotEmpty() || loadedOnce) return
        loadedOnce = true
        val infos = runCatching { plugins.listSourcesSync() }.getOrDefault(emptyList())
        pluginInfosMap = infos.associate { it.id to pluginMeta(it) }
        pluginSources = infos.map { info -> PluginLyricsSource(info, plugins) }
    }

    private var loadedOnce = false

    private fun readState(): SourcesState =
        runCatching { json.decodeFromString<SourcesState>(stateFile.readText()) }
            .getOrDefault(SourcesState())

    private fun writeState() {
        runCatching {
            rootDir.mkdirs()
            stateFile.writeText(json.encodeToString(state))
        }
    }

    /**
     * 从旧配置迁移（只做一次，之后 `sources.json` 存在就不再走）。
     *
     * ⚠️ **`order` 必须写全量**（含被停用的来源）：`order` 表达的是「排序」，
     * `disabled` 表达的是「开关」。若把停用的来源从 order 里删掉，它就只剩
     * 「新来源（追加在末尾）」这一条路，用户再启用时会被排到最末尾 —— 位置信息永久丢失。
     *
     * 映射（保持改造前的**实际**行为，不重新排序）：
     * - `provider_only` → 顺序 `[sidecar, provider, amll]`，AMLL 停用
     * - `amll_first`    → `[sidecar, amll, provider]`，全部启用（默认）
     * - `amll_only`     → `[sidecar, amll, provider]`，provider 停用；
     *   插件**保持启用** —— 改造前 AMLL_ONLY 也允许插件兜底
     */
    private fun migrateIfNeeded() {
        if (stateFile.isFile) return
        val legacyMode = runCatching {
            LyricsSourceMode.fromKey(legacySettings?.getString(LyricsSourceMode.SETTINGS_KEY))
        }.getOrDefault(LyricsSourceMode.AMLL_FIRST)

        // 插件启用状态由插件服务自己管（旧 state.json 的 enabledIds）；这里只管顺序与内置开关。
        refreshPluginSources()
        val pluginIds = pluginSources.map { it.id }
        val disabled = when (legacyMode) {
            LyricsSourceMode.PROVIDER_ONLY -> setOf(BuiltinLyricsSourceIds.AMLL)
            LyricsSourceMode.AMLL_FIRST -> emptySet()
            LyricsSourceMode.AMLL_ONLY -> setOf(BuiltinLyricsSourceIds.PROVIDER)
        }
        state = SourcesState(
            // 全量顺序：内置三源 + 插件；停用只记在 disabled 里。
            order = BuiltinLyricsSourceIds.DEFAULT_ORDER + pluginIds,
            disabled = disabled.toList(),
        )
        writeState()
    }

    /** 第三方来源的展示元信息（`allSources()` 用）。 */
    private data class PluginSourceMeta(
        val description: String = "",
        val author: String = "",
        val version: String = "",
        val configFields: List<LyricsSourceConfigField> = emptyList(),
    )

    private fun pluginMeta(info: cp.player.core.lyricsplugin.LyricsPluginSourceInfo) = PluginSourceMeta(
        description = info.description,
        author = info.author,
        version = info.version,
        configFields = info.configFields.map { it.toSourceField() },
    )

    private companion object {
        const val STATE_VERSION = 3
    }
}
