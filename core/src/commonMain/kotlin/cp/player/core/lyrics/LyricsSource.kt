package cp.player.core.lyrics

import cp.player.core.music.CPMediaId
import cp.player.core.playback.SyncedLyricLine

/**
 * 一条歌词来源的能力声明。
 *
 * 与 `cp.player.core.lyricsplugin.PluginCapability` 是两个层面的东西：
 * 那个是**插件 manifest 里写的**（决定插件能不能进列表），这个是**宿主侧的运行期能力**
 * （决定引擎会不会向它发某类请求）。同名的能力两者需要对应，但宿主不假设插件一定声明齐全。
 */
enum class LyricsCapability {
    /** 能按关键词搜歌（用于「无 ID 时模糊匹配」）。 */
    SEARCH_SONGS,

    /** 能取歌词正文 —— 这是唯一必需的能力。 */
    GET_LYRICS,

    /** 能按外部平台 ID 精确取词（AMLL 这类有 ID 索引的来源）。 */
    LOOKUP_BY_ID,

    /** 能搜封面（第三方插件可能有，宿主内置来源都没有）。 */
    SEARCH_COVERS,
}

/**
 * 一次取词请求。
 *
 * ⚠️ [mediaId] 是本次融合的关键新增：现状 `PlaybackControllerImpl.fetchFromPlugins` 只把
 * `title/artist/album` 交给插件，插件无法做精确 ID 取词，只能模糊搜索 —— 命中率与误配
 * 都明显差于 AMLL 那条路。把完整的 [CPMediaId] 透传给**所有**来源后，内置的 AMLL 源与
 * 第三方插件都能用「平台 ID 精确取」优先、关键词回退。
 *
 * @param mediaId 当前曲目的统一标识；本地曲（`local://…`）也有值，由来源自行判断是否可用
 * @param durationMs 曲目时长（毫秒），供「选时长最接近的候选」用；0 表示未知
 * @param isLocal 是否本地文件 —— 本地曲不该走「平台 ID 精确取」与音源 API
 */
data class LyricsRequest(
    val mediaId: CPMediaId?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long = 0L,
    val isLocal: Boolean = false,
) {
    /** 关键词形式的检索串（插件 `searchSongs` 用）；空标题返回空串，由调用方决定是否放弃。 */
    val keyword: String
        get() = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * 一次取词的结果。
 *
 * @param lines 已归一化为宿主统一模型的歌词行（**不再**是 LRC / TTML 原文）
 * @param hasWordLevel 是否含逐字时间戳（UI 据此决定是否上卡拉 OK 样式）
 * @param sourceId / sourceName 是谁命中的 —— 由引擎负责填充，来源自身不必关心
 */
data class LyricsSourceResult(
    val lines: List<SyncedLyricLine>,
    val sourceId: String = "",
    val sourceName: String = "",
    val hasWordLevel: Boolean = lines.any { it.words.isNotEmpty() },
) {
    val hasTranslation: Boolean get() = lines.any { !it.translation.isNullOrBlank() }
    val hasPhonetic: Boolean get() = lines.any { !it.romanization.isNullOrBlank() }
}

/**
 * 一条**歌词来源**（内置或第三方，一视同仁）。
 *
 * ### 设计立场
 * 「内置歌词源」不是 JS 插件 —— 它是宿主内的 Kotlin 实现，只与第三方插件**共用这个接口**。
 * 强行把 AMLL / 边车歌词包成 JS 只会引入 Rhino 求值开销、并让它难以访问 Ktor 与平台 API。
 * 所以本方案**只统一接口，不统一执行体**。
 *
 * ### 实现约定
 * - `fetch` 返回 null 表示**未命中**（交给下一条来源），不是错误；抛异常同样被引擎吞掉并继续。
 * - 实现不得自行做「多来源遍历」——顺序由 `LyricsSourceRegistry` 统一决定。
 */
interface LyricsSource {

    /** 稳定标识：内置源用 `builtin.*` 前缀，第三方插件用其 manifest id。 */
    val id: String

    /** 展示名（i18n：内置源由宿主在文案层本地化，插件用 manifest 里写的名字）。 */
    val name: String

    /** true = 随宿主分发，**不可删除**。 */
    val bundled: Boolean

    /** 该展示名是否需要经过文案层本地化（内置源 true；第三方插件 false，用作者给的名字）。 */
    val localizeName: Boolean get() = false

    val capabilities: Set<LyricsCapability>

    /**
     * 取词。
     *
     * @return 命中的结果；未命中返回 null
     */
    suspend fun fetch(request: LyricsRequest): LyricsSourceResult?
}

/**
 * 内置来源的稳定 id。
 *
 * 写成常量而不是散落的字面量：这些 id 会进 `sources.json` 的持久化顺序表，
 * 改名等于让所有用户的排序失效。
 */
object BuiltinLyricsSourceIds {
    const val SIDECAR = "builtin.sidecar"
    const val AMLL = "builtin.amll"
    const val PROVIDER = "builtin.provider"

    /** 默认顺序（首次安装 / 老配置迁移的兜底）：与改造前 `AMLL_FIRST` 的行为一致。 */
    val DEFAULT_ORDER = listOf(SIDECAR, AMLL, PROVIDER)

    val ALL = setOf(SIDECAR, AMLL, PROVIDER)
}
