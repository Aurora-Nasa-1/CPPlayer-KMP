package cp.player.core.provider

import cp.player.core.util.PlatformContext

/**
 * 音乐后端 Provider 接口（KMP 版）。
 *
 * 每个音乐数据源（如 NeteaseCloudMusicApi、自建后端等）都实现此接口。
 * CPPlayer 通过此接口与不同的音乐服务通信，无需关心底层实现。
 *
 * ### 实现类型
 * - [HttpProvider] — 连接到已有的 HTTP API 服务（commonMain，Ktor）
 * - [BinaryProvider] — 启动独立可执行文件作为后端（平台 expect/actual）
 * - [JniProvider] — 通过 JNI 调用 Native 库（Android + Desktop）
 *
 * ### 账号隔离
 * 每个 Provider 维护独立的账号体系。用户的 cookie、歌单、推荐等数据
 * 都与特定 Provider 绑定。切换 Provider 时，会自动切换到该 Provider 的账号。
 *
 * @see ProviderManager 管理 Provider 生命周期和切换
 * @see ModuleManager 从模块包加载 Provider
 * @see cp.player.core.api.MusicApiService 统一的 API 调用入口
 */
interface BackendProvider {
    /** Provider 唯一标识符（如 "netease"、"my-custom"） */
    val id: String

    /** Provider 显示名称（如 "NeteaseCloudMusicApi"） */
    val name: String

    /** Provider 版本号（语义化版本） */
    val version: String

    /** Provider 实现类型 */
    val type: ProviderType

    /**
     * API 方法名映射表。
     *
     * key = CPPlayer 内部标准方法名（如 "cloudsearch"）
     * value = Provider 实际端点名（如 "search"）
     *
     * - 如果映射为 "unsupported"，该功能会被标记为不支持
     * - 如果为 null，CPPlayer 会直接使用内部方法名
     */
    val apiMap: Map<String, String>?

    /** 检查更新 URL（可选），指向返回最新版本信息的 JSON 端点 */
    val updateUrl: String?

    /**
     * 目标 App 的 Android 包名（可选）。
     * 用于登录页跳转到音源对应官方 App 扫码登录。
     * 仅 Android 端生效。
     */
    val targetAppPackage: String?

    /**
     * 音源声明的登录方式（可选）。
     *
     * 取值见 [ModuleManifest.loginMethods]：`qr` / `email` / `sms` / `cookie` /
     * `captchaImage`。null = 未声明，登录页按网易云系默认展示全部方式。
     * 内置网易云 Provider 不用声明（走默认分支）；外部模块由 manifest 带入。
     */
    val loginMethods: List<String>? get() = null

    /**
     * 音源声称支持的能力（见 [ProviderCapability]），可选。
     *
     * null = 未声明。**未声明不等于不支持** —— 能力只用于展示与解释，
     * 不参与功能开关（否则每个老音源都会瞬间「少一堆功能」）。
     * 内置 Provider 可以不给（返回 null）；外部模块由 manifest 带入，
     * 并经 [PlatformSupport] 侧的 [ModuleManifest.knownCapabilities] 过滤。
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
     * 启动 Provider 服务。
     *
     * 对于 BinaryProvider，会启动可执行文件；
     * 对于 JniProvider，会启动本地服务；
     * 对于 HttpProvider，此方法为空操作（服务由外部启动）。
     */
    fun startServer(context: PlatformContext, port: Int)

    /** 停止 Provider 服务。 */
    fun stopServer()

    /**
     * 调用 Provider API。
     *
     * @param method 已通过 [apiMap] 映射后的实际方法名
     * @param params 请求参数（包含 cookie 等认证信息）
     * @return JSON 格式的响应字符串
     */
    fun callApi(method: String, params: Map<String, String>): String

    /**
     * 分析音频文件（可选）。
     *
     * @param path 音频文件路径
     * @return JSON 格式的分析结果
     */
    fun analyzeAudio(path: String): String

    /**
     * 检查 Provider 是否已就绪，可以正常提供服务。
     *
     * 对于 JNI 类型的 Provider，此方法会在 .so 加载失败时返回 false。
     * 调用方应在切换/使用 Provider 前检查此状态，避免使用不可用的 Provider。
     *
     * 默认返回 true（BinaryProvider、HttpProvider 始终就绪）。
     */
    fun isReady(): Boolean = true

    /**
     * 释放该 Provider 持有的资源（HTTP 连接池、子进程等）。
     *
     * 默认空实现 —— 无状态实现（内置内嵌单例等）不需要释放，靠默认值自动继承。
     * 有持有资源的实现（[HttpProvider] 的 Ktor 客户端、[BinaryProvider] 的子进程 + 客户端）
     * 必须覆写，否则 Provider 重载 / [cp.player.core.MusicBackend.reset] 会泄漏连接池与线程。
     */
    fun close() {}
}

/**
 * Provider 实现类型枚举。
 */
enum class ProviderType {
    /** JNI 本地库（C/C++/Rust 编写的 .so 文件） */
    JNI,
    /** 独立可执行文件（通过 HTTP 与 CPPlayer 通信） */
    BINARY,
    /** WebSocket 通信（预留） */
    WEBSOCKET,
    /** HTTP API 服务（如 NeteaseCloudMusicApi） */
    HTTP
}