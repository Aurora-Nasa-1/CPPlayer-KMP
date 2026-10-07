package cp.player.core.provider

import kotlinx.serialization.Serializable

/**
 * 音源插件的能力标识（manifest 的 `capabilities` 数组）。
 *
 * 用途：宿主在**安装前**就能知道这个音源声称支持什么，从而
 * ① 在音源管理页显示能力标签，② 在用户想用某能力（如扫码登录、逐字歌词）
 * 而当前音源没声明时给出明确原因，而不是让功能静默失效。
 *
 * 取值刻意用裸字符串（不走 enum）：manifest 是外部输入，遇到未识别的能力名
 * 必须**忽略而不是解析失败** —— 新宿主能力出现时，旧宿主仍应能加载该音源。
 */
object ProviderCapability {
    /** 扫码登录。 */
    const val QR_LOGIN = "qrLogin"

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

    /** 全部已知能力，供 UI 按稳定顺序展示。 */
    val ALL: List<String> = listOf(
        QR_LOGIN, WORD_SYNCED_LYRICS, COMMENTS, CLOUD_DRIVE, RECOMMEND, LYRICS_SEARCH,
    )
}

/**
 * 本宿主支持的最高 **插件 API 版本**。
 *
 * 与歌词源插件的 `apiVersion` 是两套独立编号：音源插件走本字段，
 * 歌词源插件走 `LyricsPluginManifest.pluginApiVersion`。两者不要互相套用。
 *
 * 版本语义：
 * - **1**：初代契约（`apiMap` 里以 Kotlin 函数名 → 远端接口名映射，宿主按需调用）。
 *   所有既有音源包都是 v1，**必须永远可加载**。
 * - **2**：在 v1 之上增加 `capabilities` 声明的语义约束（宿主会按声明裁剪 UI，
 *   未声明不等同于不支持——只是不展示标签）。
 * - **3**：统一来源插件体系（本仓库当前推进的目标）。音源与歌词源共用
 *   **一套外置插件管理界面**；`capabilities` 成为可选但被推荐的元数据。
 *
 * ⚠️ 判定「能不能装」用的是 [ModuleManifest.minHostApiVersion]，不是本字段。
 */
const val HOST_PROVIDER_API_VERSION: Int = 3

@Serializable
data class ModuleManifest(
    val id: String,
    val name: String,
    val version: String,
    val type: String, // "jni", "binary", "http"
    val entryPoint: String,
    val apiMap: Map<String, String>? = null,
    /**
     * 插件自己实现的 API 版本（缺省 = **1**，即所有历史包的行为）。
     *
     * ⚠️ 缺省必须是 1 而不是 [HOST_PROVIDER_API_VERSION]：历史包的 manifest 里
     * 根本没有这个字段，默认成 3 会让老包被误判为「新格式」而走上 v3 专属路径。
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
     * 声称支持的能力列表（见 [ProviderCapability]，可选）。
     *
     * 未识别的条目**忽略而不报错**（前向兼容）。null / 缺省 = 未声明，
     * 宿主按「保守展示」处理：不显示能力标签，但不禁用任何功能。
     */
    val capabilities: List<String>? = null,
    /** 检查更新 URL（可选），指向返回最新版本信息的 JSON 端点 */
    val updateUrl: String? = null,
    /**
     * 支持的 ABI 列表（可选）。
     * 用于声明模块支持的 CPU 架构，如 ["arm64-v8a", "armeabi-v7a"]。
     * 当 zip 包含 per-ABI 目录结构（lib/{abi}/）时，
     * 加载器会自动根据设备 ABI 选择正确的 native library。
     *
     * 为 null 或空时，表示单架构模块（向后兼容旧格式）。
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
     * 音源支持的登录方式声明（可选）。
     *
     * 取值：`"qr"`（扫码）、`"email"`（邮箱）、`"sms"`（手机验证码）、
     * `"cookie"`（粘贴 Cookie）、`"captchaImage"`（图形验证码人机校验）。
     *
     * - **null / 缺省**：按旧行为展示全部四种方式（网易云系音源的默认）；
     * - **声明了列表**：登录页只显示声明的方式（`cookie` 是宿主端能力，
     *   声明了 sms/qr/email 时仍会强制附加上）；
     *   `"captchaImage"` 表示登录流程支持图形验证码（见 `captcha/image` 方法）。
     */
    val loginMethods: List<String>? = null,
    /**
     * 整个模块 zip 包的 SHA-256（十六进制，大小写不敏感，可选）。
     *
     * 导入 / 更新时宿主会对**原始 zip 字节**重新计算并与本字段比对，不匹配即拒绝安装
     * （损坏或被篡改的包——尤其 jni/binary 类型会执行原生代码——不再被静默加载）。
     * **null / 缺省 = 跳过校验**（向后兼容旧格式模块包；CODE_REVIEW K5）。
     * 生成方式：`sha256sum xxx.zip`，把结果填进 manifest.json 后重新打包。
     */
    val sha256: String? = null
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
     * 本包能否被当前宿主加载。
     *
     * 唯一判据是 `minHostApiVersion <= HOST_PROVIDER_API_VERSION`。
     * 包**声明的** `apiVersion` 高于宿主时**不算错误**（前向兼容的常见做法是
     * 包同时声明 minHostApiVersion，宿主只看后者）。
     */
    val isLoadable: Boolean get() = resolvedMinHostApiVersion <= HOST_PROVIDER_API_VERSION

    /**
     * 声明且被宿主**认识**的能力（按 [ProviderCapability.ALL] 的稳定顺序）。
     *
     * 未声明的能力不在这里 —— 但**不代表宿主会禁用对应功能**：
     * 能力标签只用于展示与「为什么这个按钮没有」的解释。
     */
    val knownCapabilities: List<String>
        get() {
            val declared = capabilities.orEmpty().toSet()
            return ProviderCapability.ALL.filter { it in declared }
        }

    /** 是否声称支持 [capability]（见 [ProviderCapability]）。 */
    fun supports(capability: String): Boolean = capability in capabilities.orEmpty()
}