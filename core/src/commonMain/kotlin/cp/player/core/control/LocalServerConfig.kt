package cp.player.core.control

import cp.player.core.util.SettingsStorage
import kotlin.random.Random

/**
 * 音频输出目标。
 *
 * - [SPEAKER]：本机声卡正常出声，同时（可选）把流推给接收端；
 * - [SERVER_ONLY]：**本机不出声**，只把流对外提供。队列推进、歌词、打卡
 *   仍由本机播放器的时间轴驱动，但音量恒为 0。
 */
enum class OutputMode {
    SPEAKER,
    SERVER_ONLY,
}

/**
 * 「本地服务器输出 + 外部推送」配置。
 *
 * ### 角色划分
 * CPPlayer 在这里是**推送方（pusher）**：
 * 1. 本机开一个 HTTP 流输出服务（默认 `:8080`），把当前曲目以 HTTP 流形式对外提供；
 * 2. 主动把流地址与传输指令 POST 给**接收端**（默认 `http://127.0.0.1:8420`）。
 *
 * 接收端（游戏 radio 等）只需实现文档里的 `/api/v1/...` 接口，不需要感知 CPPlayer 内部结构。
 *
 * @property enabled 是否启用本地服务器输出模式。
 * @property outputMode 音频输出目标；[OutputMode.SERVER_ONLY] 时本机静音。
 * @property bindAddress 流输出服务的绑定地址。
 * @property streamPort 流输出服务端口（CPPlayer 自己监听）。
 * @property accessToken 流输出访问令牌；非空时 `/stream` 需携带 `?token=`。
 * @property pushEnabled 曲目变化时是否自动推送到接收端。
 * @property receiverBaseUrl 接收端基地址，例如 `http://127.0.0.1:8420`。
 */
data class LocalServerConfig(
    val enabled: Boolean = false,
    val outputMode: OutputMode = OutputMode.SPEAKER,
    val bindAddress: String = BIND_LOOPBACK,
    val streamPort: Int = DEFAULT_STREAM_PORT,
    val accessToken: String = "",
    val pushEnabled: Boolean = true,
    val receiverBaseUrl: String = DEFAULT_RECEIVER_BASE_URL,
) {

    /** 本机是否静音（音频只从接收端出）。 */
    val silentLocalOutput: Boolean get() = outputMode == OutputMode.SERVER_ONLY

    /** 是否对外（局域网）暴露流输出。 */
    val exposedToLan: Boolean get() = bindAddress != BIND_LOOPBACK

    /** 流输出地址（接收端拉流用）。 */
    fun streamUrl(host: String = bindAddress): String = "http://$host:$streamPort/stream"

    /** 带令牌的流地址；未配置令牌时等同于 [streamUrl]。 */
    fun streamUrlWithToken(host: String = bindAddress): String =
        if (accessToken.isBlank()) streamUrl(host) else "${streamUrl(host)}?token=$accessToken"

    /** 某一曲目的流地址（接收端按曲目拉流）。 */
    fun streamUrlFor(mediaId: String, host: String = bindAddress): String {
        val base = "${streamUrl(host)}?mediaId=${encodeQuery(mediaId)}"
        return if (accessToken.isBlank()) base else "$base&token=$accessToken"
    }

    /** 规范化后的接收端基地址（去掉尾部斜杠）。 */
    val receiverUrl: String get() = receiverBaseUrl.trim().trimEnd('/')

    /** 是否需要校验流输出令牌。 */
    val requiresToken: Boolean get() = accessToken.isNotBlank()

    companion object {
        const val BIND_LOOPBACK = "127.0.0.1"
        const val BIND_ALL = "0.0.0.0"

        const val DEFAULT_STREAM_PORT = 8080
        const val DEFAULT_RECEIVER_BASE_URL = "http://127.0.0.1:8420"

        const val KEY_ENABLED = "local_server_enabled"
        const val KEY_OUTPUT_MODE = "local_server_output_mode"
        const val KEY_BIND = "local_server_bind"
        const val KEY_STREAM_PORT = "local_server_stream_port"
        const val KEY_TOKEN = "local_server_token"
        const val KEY_PUSH_ENABLED = "external_push_enabled"
        const val KEY_RECEIVER_URL = "external_push_receiver_url"

        val PORT_RANGE = 1024..65535
        val BIND_OPTIONS = listOf(BIND_LOOPBACK, BIND_ALL)

        /** 生成随机访问令牌（32 位十六进制）。 */
        fun newToken(): String =
            (0 until 16).joinToString("") { Random.nextInt(0, 256).toString(16).padStart(2, '0') }

        /** 端口越界或非法时回退到 [fallback]。 */
        fun normalizePort(value: Int?, fallback: Int): Int = value?.takeIf { it in PORT_RANGE } ?: fallback

        /** 极简 query 转义（mediaId 含 `://` 与 `/`，必须转义）。 */
        internal fun encodeQuery(raw: String): String = buildString {
            for (ch in raw) {
                when {
                    ch.isLetterOrDigit() || ch in "-_.~" -> append(ch)
                    else -> append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
    }
}

/**
 * [LocalServerConfig] 的持久化读写。
 *
 * 键值与 [SettingsStorage] 解耦为独立对象，便于后端与前端共用同一套键。
 */
object LocalServerConfigStore {

    /** 读取配置；缺失或非法字段回退默认值。 */
    fun read(settings: SettingsStorage): LocalServerConfig = LocalServerConfig(
        enabled = settings.getString(LocalServerConfig.KEY_ENABLED)?.toBooleanStrictOrNull() ?: false,
        outputMode = settings.getString(LocalServerConfig.KEY_OUTPUT_MODE)
            ?.let { raw -> OutputMode.entries.firstOrNull { it.name == raw } }
            ?: OutputMode.SPEAKER,
        bindAddress = settings.getString(LocalServerConfig.KEY_BIND)
            ?.takeIf { it in LocalServerConfig.BIND_OPTIONS }
            ?: LocalServerConfig.BIND_LOOPBACK,
        streamPort = LocalServerConfig.normalizePort(
            settings.getString(LocalServerConfig.KEY_STREAM_PORT)?.toIntOrNull(),
            LocalServerConfig.DEFAULT_STREAM_PORT,
        ),
        accessToken = settings.getString(LocalServerConfig.KEY_TOKEN).orEmpty(),
        pushEnabled = settings.getString(LocalServerConfig.KEY_PUSH_ENABLED)?.toBooleanStrictOrNull() ?: true,
        receiverBaseUrl = settings.getString(LocalServerConfig.KEY_RECEIVER_URL)
            ?.takeIf { it.isNotBlank() }
            ?: LocalServerConfig.DEFAULT_RECEIVER_BASE_URL,
    )

    /** 写入全部字段。 */
    fun write(settings: SettingsStorage, config: LocalServerConfig) {
        settings.putString(LocalServerConfig.KEY_ENABLED, config.enabled.toString())
        settings.putString(LocalServerConfig.KEY_OUTPUT_MODE, config.outputMode.name)
        settings.putString(LocalServerConfig.KEY_BIND, config.bindAddress)
        settings.putString(LocalServerConfig.KEY_STREAM_PORT, config.streamPort.toString())
        settings.putString(LocalServerConfig.KEY_TOKEN, config.accessToken)
        settings.putString(LocalServerConfig.KEY_PUSH_ENABLED, config.pushEnabled.toString())
        settings.putString(LocalServerConfig.KEY_RECEIVER_URL, config.receiverBaseUrl)
    }

    /** 确保令牌已生成（首次启用时调用），返回生效值。 */
    fun ensureToken(settings: SettingsStorage): String {
        val existing = settings.getString(LocalServerConfig.KEY_TOKEN).orEmpty()
        if (existing.isNotBlank()) return existing
        val token = LocalServerConfig.newToken()
        settings.putString(LocalServerConfig.KEY_TOKEN, token)
        return token
    }

    /** 重新生成令牌，返回新值。 */
    fun regenerateToken(settings: SettingsStorage): String {
        val token = LocalServerConfig.newToken()
        settings.putString(LocalServerConfig.KEY_TOKEN, token)
        return token
    }
}
