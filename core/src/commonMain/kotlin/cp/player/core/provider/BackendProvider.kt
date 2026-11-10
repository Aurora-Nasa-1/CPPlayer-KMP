package cp.player.core.provider

import cp.player.core.util.defaultSettingsStorage

/**
 * Host-owned installed-provider descriptor. Provider authors implement SDK MusicProvider instead.
 * SdkBackendProvider bridges metadata and an account-scoped session factory to this registry.
 * Host never maps or dispatches provider method names; every request is a typed SDK call.
 *
 * @see ProviderManager 管理 Provider 生命周期和切换
 * @see ModuleManager 从模块包加载 Provider
 * @see cp.player.core.music.UnifiedMusicSource
 */
interface BackendProvider {
    /** Typed session factory. A missing factory is an error, never a legacy fallback. */
    fun createSdkProvider(context: cp.player.sdk.ProviderContext): cp.player.sdk.MusicProvider? = null
    val sdkAllowedOrigins: Set<String> get() = emptySet()
    fun onSdkSessionRetired(listener: () -> Unit): () -> Unit = {}
    /** Provider 唯一标识符（如 "netease"、"my-custom"） */
    val id: String

    /** Provider 显示名称（如 "NeteaseCloudMusicApi"） */
    val name: String

    /** Provider 版本号（语义化版本） */
    val version: String

    /** Provider 实现类型 */
    val type: ProviderType

    /** 检查更新 URL（可选），指向返回最新版本信息的 JSON 端点 */
    val updateUrl: String?

    /**
     * 目标 App 的 Android 包名（可选）。
     * 用于登录页跳转到音源对应官方 App 扫码登录。
     * 仅 Android 端生效。
     */
    val targetAppPackage: String?

    /**
     * Reserved UI metadata. Installed SDK descriptors expose an empty list until auth is implemented.
     */
    val loginMethods: List<String>? get() = null

    /**
     * 音源声称支持的能力（见 [ProviderCapability]），可选。
     *
     * UI labels only. The module loader maps supported SDK methods via
     * [ModuleManifest.knownCapabilities]; runtime dispatch checks MusicProvider.capabilities().
     *
     * 带默认实现：`core` 接口新增成员必须给默认实现（见 AGENTS.md）。
     */
    val capabilities: List<String>? get() = null

    /**
     * 音源实现的插件 API 版本（缺省 1，见 [HOST_PROVIDER_API_VERSION]）。
     * 同样给默认实现以保持所有既有实现类可编译。
     */
    val apiVersion: Int get() = 1

    /**
     * Optional display label. It never chooses a protocol or bypasses the contract gate.
     */
    val apiVersionTag: String? get() = null

    /** Must equal [PROVIDER_SDK_CONTRACT] to be activated. Null is not an implicit legacy mode. */
    val contract: String? get() = null

    /** 声明式自定义设置项规范（可选） */
    val settingsSchema: List<ProviderSettingItemSchema>? get() = null

    /**
     * 音源自定义音质选项及其向软件原生音质映射列表（可选）。
     * null 时表示未声明，按宿主默认原生音质处理。
     */
    val qualityOptions: List<ProviderQualityOption>? get() = null

    /**
     * 将原生目标音质解析为发往当前音源的实际参数 key。
     */
    fun resolveQualityKey(targetQuality: String): String =
        AudioQualityResolver.resolveKey(qualityOptions, targetQuality)

    /**
     * 根据音源实际返回的 key 还原原生音质与自定义标签配置。
     */
    fun resolveQualityOption(actualKey: String): ProviderQualityOption? =
        AudioQualityResolver.resolveOption(qualityOptions, actualKey)

    /**
     * 加载或运行时的错误信息（若有）。
     * 正常就绪时为 null；失败时返回具体错误原因，供状态机与健康诊断界面展示。
     */
    val loadError: String? get() = null

    /**
     * Provider 运行诊断信息（键值对，可选）。
     * 例如端口、子进程状态、库路径等，供健康与诊断界面查询。
     */
    fun getDiagnostics(): Map<String, String> = emptyMap()

    /** 是否包含自定义配置项（根据 [settingsSchema] 判定） */
    val hasCustomSettings: Boolean get() = !settingsSchema.isNullOrEmpty()

    /**
     * 获取当前音源持久化的所有自定义配置值（若用户未配置则取 schema 默认值）。
     */
    fun getCustomSettings(): Map<String, String> {
        val schema = settingsSchema ?: return emptyMap()
        val storage = defaultSettingsStorage("provider_config_$id")
        return schema.associate { item ->
            item.key to (storage.getString(item.key) ?: item.default.orEmpty())
        }
    }

    /**
     * 读取单个自定义配置项的值。
     */
    fun getCustomSetting(key: String): String? {
        val storage = defaultSettingsStorage("provider_config_$id")
        return storage.getString(key) ?: settingsSchema?.firstOrNull { it.key == key }?.default
    }

    /**
     * 保存或更新单个自定义配置项。
     */
    fun updateCustomSetting(key: String, value: String) {
        defaultSettingsStorage("provider_config_$id").putString(key, value)
    }

    /**
     * 批量更新自定义配置项。
     */
    fun updateCustomSettings(settings: Map<String, String>) {
        val storage = defaultSettingsStorage("provider_config_$id")
        settings.forEach { (k, v) ->
            storage.putString(k, v)
        }
    }

    /**
     * 重置所有自定义配置项为默认值。
     */
    fun resetCustomSettings() {
        val schema = settingsSchema ?: return
        val storage = defaultSettingsStorage("provider_config_$id")
        schema.forEach { item ->
            if (item.default != null) {
                storage.putString(item.key, item.default)
            } else {
                storage.remove(item.key)
            }
        }
    }

    /**
     * Do not expose unimplemented SDK features even if old metadata claims them.
     */
    fun isFeatureAvailable(capability: String): Boolean =
        capability in ProviderCapability.sdkLabels(capabilities)

    /** 是否明确声称支持 [capability]（见 [ProviderCapability]）。 */
    fun supports(capability: String): Boolean =
        isFeatureAvailable(capability)

    /** 让当前账号会话失效，但保留已安装的 descriptor（下次取数重新建会话）。 */
    fun retireSessions() {}

    /**
     * Descriptor readiness only. Session factories and requests may still return typed failures.
     */
    fun isReady(): Boolean = true

    /**
     * Dispose the installed descriptor and retire its sessions. Session owners drain adapters.
     */
    fun close() {}
}

/**
 * Provider 实现类型枚举。
 */
/**
 * 音源的加载形态。当前只有两种，且都由 host 显式构造：
 * - [JNI]：受信的原生 SDK 库，经 `jniSdkProvider` 注册；
 * - [SCRIPT]：模块包里的 JS/TS 脚本，经 `ProviderFactory.create` 加载。
 *
 * 旧版的自起 HTTP 服务 / 独立可执行文件 / FFM / WebSocket 都已随 JSON 兼容层退役，
 * 不再有可安装的实现，保留枚举值只会让「类型」展示出现永远取不到的分支。
 */
enum class ProviderType {
    /** 受信 JNI 原生库（每账号一个 session）。 */
    JNI,
    /** KMP SDK 脚本包（JS/TS）。 */
    SCRIPT,
}
