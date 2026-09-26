package cp.player.core.integration

import kotlinx.serialization.Serializable

/** 描述符文件名。集成方按固定路径读它，改名等于破坏发现机制。 */
const val INTEGRATION_DESCRIPTOR_FILE_NAME = "integration.json"

/**
 * 端点描述符：让第三方软件**不必手抄**地址与令牌。
 *
 * ### 为什么是文件而不是 mDNS
 * 一个文件零依赖、零端口、零权限；mDNS 要引入依赖并处理多网卡。
 * 「发现」这件事不需要广播 —— 集成方只要能在固定位置读到就够了。
 *
 * ### ⚠️ 这个文件含明文令牌
 * 桌面实现会把权限收紧到仅属主可读写（POSIX 平台）。
 * 诚实说明：令牌本来就明文存在同目录的 `cp_player_prefs.properties` 里，
 * 所以对**同用户**进程这不是新增暴露面，**跨用户**才是 —— 权限收紧挡的正是后者。
 *
 * @property baseUrl **基地址，不含路径**。数据面端点即 `baseUrl + "/api/v1/..."`。
 * @property pid 写入时 CPPlayer 的进程号。**唯一用途**是让集成方判断描述符是否陈旧：
 *   进程异常退出（崩溃、被强杀）时来不及删除文件，于是「文件还在，服务已经没了」。
 *   集成方**必须**用它判断进程是否还活着。
 * @property updatedAt 写入时刻（ISO-8601 带时区偏移，截到秒）。给人排查用。
 */
@Serializable
data class IntegrationDescriptor(
    val app: String,
    val apiVersion: Int,
    val baseUrl: String,
    val token: String,
    val pid: Long,
    val updatedAt: String,
)

/**
 * 描述符的读写。
 *
 * 抽成接口而不是直接写文件：Android **没有** `~/.cpplayer`，
 * 而且它的两个选项（私有目录别的 App 读不到 / 外部存储全机可读）都不成立，
 * 所以 Android 是**有理由的**空实现，不是「以后再说」。
 */
interface IntegrationDescriptorWriter {

    /** 发布（覆盖）描述符。 */
    fun publish(baseUrl: String, token: String)

    /**
     * 删除描述符。
     *
     * **必须幂等且不抛**：它在服务停止 / 停用路径上被调用，可能从未 [publish] 过。
     * 抛异常会顺着关停流程往上冒，属于「清理逻辑反而制造故障」。
     */
    fun clear()
}

/**
 * 平台实现。
 *
 * - desktop：写 `~/.cpplayer/integration.json`（**必须**走 `DesktopDataDir`）。
 * - android：**有理由的**空实现。将来要给 Android 上的第三方 App 供数，
 *   正确形态是 ContentProvider / AIDL 适配层加在 `IntegrationService` **之上**，
 *   保持「一套用例，多种传输」。
 */
expect fun createIntegrationDescriptorWriter(): IntegrationDescriptorWriter
