package cp.player.core.provider

import kotlinx.serialization.Serializable

/**
 * UI capability labels. SDK method capabilities are mapped here, not treated as old API endpoints.
 * Keep unsupported labels for existing screens, but never advertise them for the current SDK.
 */
object ProviderCapability {
    /** 歌曲与歌单搜索。 */
    const val SEARCH = "search"

    /** 音频播放串流。 */
    const val PLAYBACK = "playback"

    /** 扫码登录。 */
    const val QR_LOGIN = "qrLogin"

    /** WebView / 网页登录与跳转验证。 */
    const val WEBVIEW_LOGIN = "webviewLogin"

    /** 逐字歌词（YRC / TTML 级）。 */
    const val WORD_SYNCED_LYRICS = "wordSyncedLyrics"

    /** 歌曲评论与楼层回复。 */
    const val COMMENTS = "comments"

    /** 云盘 / 我的音乐。 */
    const val CLOUD_DRIVE = "cloudDrive"

    /** 每日推荐 / 私人 FM。 */
    const val RECOMMEND = "recommend"

    /** 搜索兜底歌词（音源侧 `lyric/new`）。 */
    const val LYRICS_SEARCH = "lyricsSearch"

    /** 排行榜 / 发现榜单。 */
    const val CHARTS = "charts"

    /** 用户歌单（个人创建/收藏）。 */
    const val USER_PLAYLISTS = "userPlaylists"

    /** 歌手主页与作品浏览。 */
    const val ARTIST_EXPLORER = "artistExplorer"

    /** 专辑主页与曲目浏览。 */
    const val ALBUM_EXPLORER = "albumExplorer"

    /** 自定义目录或流派浏览。 */
    const val DIRECTORY_BROWSER = "directoryBrowser"

    /** 全部已知能力，供 UI 按稳定顺序展示。 */
    val ALL: List<String> = listOf(
        SEARCH, PLAYBACK, QR_LOGIN, WEBVIEW_LOGIN, WORD_SYNCED_LYRICS, COMMENTS, CLOUD_DRIVE, RECOMMEND,
        LYRICS_SEARCH, CHARTS, USER_PLAYLISTS, ARTIST_EXPLORER, ALBUM_EXPLORER, DIRECTORY_BROWSER,
    )

    val SDK_SUPPORTED: Set<String> = setOf(
        SEARCH, PLAYBACK, LYRICS_SEARCH, USER_PLAYLISTS, ARTIST_EXPLORER, ALBUM_EXPLORER,
    )

    fun sdkLabels(capabilities: List<String>?): List<String> {
        val declared = capabilities.orEmpty().toSet()
        return buildList {
            if (SEARCH in declared || listOf(
                cp.player.sdk.Capability.SEARCH, cp.player.sdk.Capability.SEARCH_PLAYLISTS,
                cp.player.sdk.Capability.SEARCH_ALBUMS, cp.player.sdk.Capability.SEARCH_ARTISTS,
            ).any { it in declared }) add(SEARCH)
            if (PLAYBACK in declared || cp.player.sdk.Capability.RESOLVE in declared) add(PLAYBACK)
            if (LYRICS_SEARCH in declared || cp.player.sdk.Capability.LYRICS in declared) add(LYRICS_SEARCH)
            if (USER_PLAYLISTS in declared || cp.player.sdk.Capability.USER_PLAYLISTS in declared) add(USER_PLAYLISTS)
            if (ARTIST_EXPLORER in declared || listOf(
                cp.player.sdk.Capability.ARTIST, cp.player.sdk.Capability.ARTIST_TRACKS,
                cp.player.sdk.Capability.ARTIST_ALBUMS,
            ).all { it in declared }) add(ARTIST_EXPLORER)
            if (ALBUM_EXPLORER in declared || cp.player.sdk.Capability.ALBUM in declared) add(ALBUM_EXPLORER)
        }
    }
}


/**
 * 声明式音源自定义设置项规范。
 */
@Serializable
data class ProviderSettingItemSchema(
    val key: String,
    val label: String,
    /** 输入类型: "string", "password", "switch", "select" */
    val type: String,
    val placeholder: String? = null,
    val default: String? = null,
    val options: List<ProviderSettingOption>? = null,
    val required: Boolean = false,
    val description: String? = null
)

@Serializable
data class ProviderSettingOption(
    val label: String,
    val value: String
)

/**
 * Retained host/catalog compatibility number, not the SDK contract major.
 * A compatible number never enables retired v1/v3 protocols; [PROVIDER_SDK_CONTRACT] is required.
 */
const val HOST_PROVIDER_API_VERSION: Int = 3

/** The only installable music-provider contract. Its major is unrelated to old API versions. */
const val PROVIDER_SDK_CONTRACT: String = "kmp-sdk-v1"

@Serializable
data class ModuleManifest(
    val id: String,
    val name: String,
    val version: String,
    val type: String, // "js" or "script"; native SDK adapters require trusted registration
    val entryPoint: String,
    /**
     * Catalog metadata only. Omission does not select a legacy runtime.
     */
    val apiVersion: Int? = null,
    /**
     * 本插件**要求宿主至少**达到的 API 版本（缺省 = 1）。
     *
     * 宿主拒绝加载 `minHostApiVersion > HOST_PROVIDER_API_VERSION` 的包，
     * 并提示「请升级应用」—— 否则会以旧代码执行新契约，失败点难以定位。
     */
    val minHostApiVersion: Int? = null,
    /**
     * Optional display label. It never selects a transport or response parser.
     */
    val apiVersionTag: String? = null,
    /**
     * UI labels (search/playback) or SDK method names, used for installation metadata.
     * Runtime dispatch is gated by MusicProvider.capabilities(), not by this declaration.
     */
    val capabilities: List<String>? = null,
    /** 检查更新 URL（可选），指向返回最新版本信息的 JSON 端点 */
    val updateUrl: String? = null,
    /**
     * Legacy metadata retained for decoding only. Installed script packages have no native ABI.
     */
    val supportedAbis: List<String>? = null,
    /**
     * 目标 App 的 Android 包名（可选）。
     * 用于登录页"打开目标 App"按钮，通过 Intent 跳转到音源对应的官方 App。
     * 例如网易云音乐为 "com.netease.cloudmusic"。
     * 仅 Android 端生效，Desktop 端忽略。
     */
    val targetAppPackage: String? = null,
    /**
     * Reserved legacy metadata. The current SDK has no authentication workflow; the loader ignores it.
     */
    val loginMethods: List<String>? = null,
    /**
     * @deprecated 压缩包内声明并校验自身 zip 的 sha256 逻辑自相矛盾且无法实现
     *
     * （修改 manifest.json 写入哈希后重新打包必然改变 zip 的 sha256）。
     * 整包完整性校验应由外部发布渠道（如更新服务清单、分发源）在包外进行；
     * 宿主在导入时不对此字段进行自校验。本字段保留仅为兼容旧 manifest 反序列化。
     * 新包无需声明此字段。
     */
    @Deprecated("压缩包内校验自身 sha256 无法实现，已废弃且宿主不再校验")
    val sha256: String? = null,
    /**
     * Required contract selector. Only [PROVIDER_SDK_CONTRACT] can be installed.
     * Null, standard, netease-legacy and unknown majors are rejected, never guessed.
     */
    val contract: String? = null,
    /** Exact HTTP origins available to a kmp-sdk-v1 session. Empty denies HTTP. */
    val sdkOrigins: List<String> = emptyList(),
    /**
     * 声明式音源设置界面配置项（可选）。
     * 宿主可根据此配置自动渲染设置表单，并持久化用户配置。
     */
    val settingsSchema: List<ProviderSettingItemSchema>? = null,
    /**
     * 音源自定义音质选项及其向软件原生音质映射列表（可选）。
     * 声明后前端根据此列表渲染当前音源档位并实现跨音源自动保持与平滑映射。
     */
    val qualityOptions: List<ProviderQualityOption>? = null
) {
    /**
     * 已解析的 API 版本（缺省 1）。
     *
     * ⚠️ 不写成 `val apiVersion: Int = 1` 的默认参数：序列化时缺省字段会**被写回**，
     * 于是一个「什么都没声明」的老包打包往返一次就凭空多出 `"apiVersion": 1`，
     * 与用户的原始包不再逐字节一致（sha256 校验因此会莫名其妙地失败）。
     */
    val resolvedApiVersion: Int get() = apiVersion ?: 1

    /** 已解析的宿主最低要求（缺省 1）。 */
    val resolvedMinHostApiVersion: Int get() = minHostApiVersion ?: 1

    /**
     * 已解析的接口代际标签：去空白之后仍非空才算声明。
     *
     * 为什么要掐掉空串：`"apiVersionTag": ""` 这类值在 UI 上会渲染成一个
     * 只有分隔点的空角标（`· API 版本: ·`），看起来像宿主漏读字段。
     * 空串应当与「没声明」等价。
     */
    val resolvedApiVersionTag: String?
        get() = apiVersionTag?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Contract, runtime, identity, entry path and host-version validation.
     */
    val isLoadable: Boolean get() = loadabilityError == null

    /** Validate before touching an installed module or executing any provider code. */
    val loadabilityError: String?
        get() = when {
            !Regex("[a-z0-9][a-z0-9._-]{0,127}").matches(id) || ".." in id || id == "local" ->
                "Invalid provider ID"
            contract != PROVIDER_SDK_CONTRACT ->
                "Legacy Provider v1/v3 is retired; rebuild with provider-sdk-kmp ($PROVIDER_SDK_CONTRACT)"
            type !in setOf("js", "script") ->
                "Only SDK JavaScript packages can be imported; native providers require trusted registration"
            entryPoint.isBlank() || entryPoint.startsWith("/") || '\\' in entryPoint ||
                ':' in entryPoint || entryPoint.split('/').any { it.isEmpty() || it == "." || it == ".." } ->
                "Provider entryPoint must be a relative path inside the package"
            resolvedApiVersion < 1 || resolvedMinHostApiVersion < 1 ->
                "Invalid provider API version"
            resolvedMinHostApiVersion > HOST_PROVIDER_API_VERSION ->
                "Provider requires host API $resolvedMinHostApiVersion (supported: $HOST_PROVIDER_API_VERSION)"
            else -> null
        }

    /**
     * Only currently implemented SDK capabilities can become UI labels.
     */
    val knownCapabilities: List<String>
        get() = ProviderCapability.sdkLabels(capabilities)

    /** 是否声称支持 [capability]（见 [ProviderCapability]）。 */
    fun supports(capability: String): Boolean = capability in knownCapabilities

    /**
     * No legacy permissive fallback: omitted metadata does not advertise unsupported features.
     */
    fun isFeatureAvailable(capability: String): Boolean =
        supports(capability)
}
