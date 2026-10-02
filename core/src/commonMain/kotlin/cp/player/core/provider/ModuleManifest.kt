package cp.player.core.provider

import kotlinx.serialization.Serializable

@Serializable
data class ModuleManifest(
    val id: String,
    val name: String,
    val version: String,
    val type: String, // "jni", "binary", "http"
    val entryPoint: String,
    val apiMap: Map<String, String>? = null,
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
    val loginMethods: List<String>? = null
)