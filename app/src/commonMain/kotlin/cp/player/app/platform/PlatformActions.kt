package cp.player.app.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

/**
 * 当前是否运行在 Android 平台。
 *
 * 用于能力差异判别（如 SAF 树 URI 不能作为下载写入目录，
 * Android 下载固定保存到应用私有目录）。
 */
expect fun isAndroidPlatform(): Boolean

/** Current desktop distribution family (`windows` or `linux`). */
expect fun desktopPlatform(): String

/**
 * Google Sans Flex `ROND` 轴的**平台默认**圆滑度（0–100）。
 *
 * - Android 16（API 36）起系统把 Google Sans Flexible 的圆滑度开到最大（100），
 *   应用跟随系统观感 ⇒ 默认 100；
 * - 其余平台（Android 15 及以下 / 桌面）按字体文件自带的默认实例 ⇒ 0（方正）。
 *
 * 用户可在「外观与主题 → 字体圆滑度」里覆盖；这里的值只决定**未自定义时**的表现。
 */
expect fun defaultFontRoundness(): Int

/**
 * 保存 Base64 编码的图片到系统相册。
 * 仅 Android 端生效，Desktop 端为空操作。
 *
 * @param base64Image Base64 编码的图片数据（不含 data:image/... 前缀）
 * @param fileName 保存的文件名（不含扩展名）
 */
expect fun saveQrCodeToGallery(base64Image: String, fileName: String)

/**
 * 通过包名打开目标 App。
 * 仅 Android 端生效。
 *
 * @param packageName 目标 App 的 Android 包名
 */
expect fun openTargetApp(packageName: String)

/**
 * 检查目标 App 是否已安装。
 * 仅 Android 端生效，Desktop 端始终返回 false。
 *
 * @param packageName Android 包名
 */
expect fun isPackageInstalled(packageName: String): Boolean

/**
 * 用系统浏览器打开 URL。
 *
 * @param url 要打开的链接
 */
expect fun openUrl(url: String)

/** Download a release asset using the platform's native download flow. */
expect fun downloadUpdate(url: String, fileName: String)

/**
 * 清空应用图片缓存（Coil 内存 + 磁盘）。
 *
 * @return 是否执行了清理
 */
expect fun clearImageCache(): Boolean

/**
 * 读取图片磁盘缓存的当前占用字节数。
 *
 * 用于存储管理页展示缓存体量、清理前后对比。
 *
 * @return 当前占用字节数；-1 表示不可用（缓存未启用或读取失败）
 */
expect fun imageCacheSizeBytes(): Long

/**
 * 在系统文件管理器中打开目录（桌面端资源管理器 / Finder）。
 *
 * @return 是否成功发起打开
 */
expect fun openInFileManager(path: String): Boolean

/**
 * 申请本地媒体扫描所需的运行时读取权限。
 * Android 端触发系统授权弹窗（READ_MEDIA_AUDIO/VIDEO 或 READ_EXTERNAL_STORAGE），
 * Desktop 端无需权限，为空操作。
 */
expect fun requestMediaScanPermission()

/**
 * 媒体读取权限授予结果的回调钩子（UI 层注册，用于授权后自动重试扫描）。
 * 由平台层在权限授予后调用；默认空实现。
 */
expect fun setOnMediaPermissionGranted(callback: (() -> Unit)?)

/**
 * 当前应用是否已在系统电池优化白名单里。
 *
 * Android：读 `PowerManager.isIgnoringBatteryOptimizations`；
 * Desktop：无电池策略概念，恒 true。
 */
expect fun isIgnoringBatteryOptimizations(): Boolean

/**
 * 发起「忽略电池优化」的系统授权弹窗（Android）。
 *
 * 用户在弹窗里点「允许」后，熄屏后台播放才受系统电池策略的完整保护；
 * 部分厂商 ROM（MIUI/HyperOS、HarmonyOS、ColorOS…）即使白名单后仍有
 * 自启动管理，需引导用户另行设置。Desktop 端为空操作。
 */
expect fun requestIgnoreBatteryOptimizations()

/**
 * 应用「激进保活」开关（Android）。
 *
 * ### 它到底做什么
 * 开启时持有 Wi-Fi 高性能锁 + 组播锁，使本机在熄屏 / 退到后台后仍然：
 * 1. **不被 Wi-Fi 省电模式丢包** —— 否则组播发现信标收不到，
 *    「手机在旁边却搜不到」正是这个原因（不是代码问题）；
 * 2. 维持一段在线窗口，让换设备播放 / 设备同步仍可能命中。
 *
 * ### 它**不**做什么（避免误解）
 * 它**不能**让进程免于被系统杀死 —— Android 的 LMK 按 `oomAdj` 淘汰进程，
 * 与「进程有多大 / 用什么语言写」无关（把待机件改写成 JNI 原生进程同样无效）。
 * 真正可靠的常驻只有前台服务；本开关只是把「系统愿意留你多久」往有利方向推。
 *
 * ### 代价
 * 持续持锁会增加耗电，且会阻止 Wi-Fi 进入省电状态。所以**默认关闭**，
 * 必须由用户在设置页显式开启，且设置页必须写明代价。
 *
 * Desktop 端为空操作（桌面没有 Wi-Fi 省电丢包与后台限制这两件事）。
 */
expect fun applyAggressiveStandby(enabled: Boolean)

/**
 * 激进保活**当前是否真的生效**。
 *
 * 与「用户是否打开了开关」是两回事：持锁可能因权限被拒、Wi-Fi 未开启等原因失败。
 * 设置页据此显示「已生效 / 未生效」，而不是只回显开关状态 ——
 * 只回显开关会让用户以为保护已生效，实际什么都没发生。
 */
expect fun isAggressiveStandbyActive(): Boolean

/**
 * 当前活动网络是否按「计费网络」处理（蜂窝数据 / 计费热点）。
 *
 * 用于「移动数据音质」：计费网络下在线播放改用单独设置的音质档位。
 * Desktop 无计费网络概念，恒为 false。
 */
expect fun isNetworkMetered(): Boolean

/**
 * 网络计费状态流：收集时先发射**当前**状态，之后每次变化（WiFi ↔ 蜂窝）各发射一次。
 *
 * 订阅方自行去重（实现侧只保证「变化才发」或「重发同值」，两种都合法）。
 * 流随收集器生命周期：取消收集即注销平台回调。Desktop 恒发射 false 后不再发射。
 */
expect fun networkMeteredChanges(): Flow<Boolean>

/**
 * 处理返回键事件。
 * Android 端使用 BackHandler，Desktop 端为空操作。
 *
 * @param enabled 是否启用返回键处理
 * @param onBack 返回键按下时的回调
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/**
 * 「渲染后端」设置页正文。
 *
 * 仅桌面端有意义：Compose Desktop 的绘制由 Skiko 承担，而 Skiko 在 Windows 默认走
 * Direct3D 12，其 `Present` + `DwmFlush` 的出帧节奏会与 VRR / 系统帧节奏控制相互影响
 * （上游 compose-multiplatform#1648）。Android 端渲染完全由系统负责，故实现为空。
 *
 * 宿主见 `cp.player.app.ui.screen.RenderTuningSettingsScreen`，该入口也只在桌面端出现。
 */
@Composable
expect fun PlatformRenderTuningContent()

/**
 * 桌面端「关闭窗口时的行为」设置行（消息通知设置页里）。
 *
 * 仅桌面端有意义：Android 没有「关闭窗口」这个动作（Activity 生命周期由系统管），
 * 「最小化到托盘 / 直接退出」两档都没有对应概念，故实现为空 ——
 * 与 [PlatformRenderTuningContent] 同一套做法。
 */
@Composable
expect fun PlatformCloseBehaviorSetting(index: Int, total: Int)

