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

    /**
     * 是否对外提供**媒体面**（`/stream`）。
     *
     * 默认 `true`：媒体面是本功能的由来，既有接收端依赖它。
     * 与 [exposeDataApi] 分开控制，因为两者暴露的东西完全不同 ——
     * 媒体面是「一个字节流」，数据面是「用户的歌单/搜索/播控」。
     */
    val exposeStream: Boolean = true,

    /**
     * 是否对外提供**数据面**（`/api/v1/...`）。
     *
     * ⚠️ **默认关闭**：数据面会暴露搜索、歌单、播放状态与（可选）播控，
     * 属于显著扩大的攻击面，只能由用户显式打开。
     */
    val exposeDataApi: Boolean = false,

    /**
     * 是否允许**远程播控**（数据面内部的第二道开关）。
     *
     * 与 [exposeDataApi] 的关系：数据面开了才谈得上这项；数据面关着时它无意义。
     * 「数据面开着」≠「有写能力」—— 集成方只看数据面开关就会在写操作上撞 403。
     */
    val allowRemoteControl: Boolean = false,
) {

    /** 本机是否静音（音频只从接收端出）。 */
    val silentLocalOutput: Boolean get() = outputMode == OutputMode.SERVER_ONLY

    /**
     * 是否只绑在回环地址上。
     *
     * 语义是**对外不可达**，不是「地址字符串等于 127.0.0.1」——
     * 后者漏掉了 `localhost` / `::1` 这类写法。
     */
    val boundToLoopback: Boolean get() = bindAddress == BIND_LOOPBACK

    /** 是否对外（局域网）暴露流输出。 */
    val exposedToLan: Boolean get() = bindAddress != BIND_LOOPBACK

    /**
     * **基地址，不含路径**。数据面端点即 `baseUrl + "/api/v1/..."`。
     *
     * 刻意不带路径：描述符里的 `baseUrl` 字段要能被集成方直接拼出数据面端点。
     * 若这里返回的是 `/stream` 地址，集成方就得自己做字符串截断，而那正是
     * 「文档说 baseUrl、实现给 stream 地址」这类漂移的温床。
     *
     * @param host 要对外广播的主机。绑定 `0.0.0.0` 时必须传入真实网卡地址，
     *             否则集成方会拿到一个自己连不上的 `0.0.0.0`。
     */
    fun baseUrl(host: String = bindAddress): String = "http://$host:$streamPort"

    /** 流输出地址（接收端拉流用）。由 [baseUrl] 拼出，避免两处各写一遍端口。 */
    fun streamUrl(host: String = bindAddress): String = "${baseUrl(host)}/stream"

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

        const val KEY_EXPOSE_STREAM = "local_server_expose_stream"
        const val KEY_EXPOSE_DATA_API = "local_server_expose_data_api"
        const val KEY_ALLOW_REMOTE_CONTROL = "local_server_allow_remote_control"

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
        // 三个路由级开关都是 **fail-closed**：任何解析失败都回退到更保守的一侧。
        // 只有 exposeStream 的保守侧是 true —— 它关掉会断掉既有接收端，
        // 而另两个的保守侧是 false，因为它们打开等于扩大攻击面。
        exposeStream = settings.getString(LocalServerConfig.KEY_EXPOSE_STREAM)
            ?.toBooleanStrictOrNull() ?: true,
        exposeDataApi = settings.getString(LocalServerConfig.KEY_EXPOSE_DATA_API)
            ?.toBooleanStrictOrNull() ?: false,
        allowRemoteControl = settings.getString(LocalServerConfig.KEY_ALLOW_REMOTE_CONTROL)
            ?.toBooleanStrictOrNull() ?: false,
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
        // 三个路由级开关必须一起写回。漏掉它们的话，用户在设置页打开数据面、
        // 重启应用后开关会静默回到「关」——而 read() 是 fail-closed 的，
        // 症状看起来像「设置没保存」，实际是 write() 少写了三行。
        settings.putString(LocalServerConfig.KEY_EXPOSE_STREAM, config.exposeStream.toString())
        settings.putString(LocalServerConfig.KEY_EXPOSE_DATA_API, config.exposeDataApi.toString())
        settings.putString(LocalServerConfig.KEY_ALLOW_REMOTE_CONTROL, config.allowRemoteControl.toString())
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

/**
 * 令牌是否通过校验 —— **媒体面与数据面唯一的令牌规则实现**。
 *
 * ### 为什么必须只有一处
 * 曾经媒体面写的是 `if (!config.requiresToken) return true`，数据面写的是完整比较。
 * 同一个规则两处各写一遍，就漂移成了「媒体面没配令牌就放行」：
 * 「绑定 `0.0.0.0` + 令牌为空」是一个**可达状态**（用户手工清掉令牌键、配置文件被改），
 * 此时同网段任何人都能无限拉流。**同一个规则出现在两个调用点时就该抽函数。**
 *
 * ### 三条分支
 * 1. **配了令牌** → 必须逐字匹配（大小写敏感：令牌是十六进制串，宽松比较会削弱熵）；
 * 2. **没配令牌 + 只绑回环** → 放行（外部根本连不上，本机脚本不该要求配置）；
 * 3. **没配令牌 + 绑定非回环** → **拒绝**。这条**不可配置关闭** —— 局域网裸奔没有正当场景。
 *
 * @param provided 调用方给出的令牌；未提供时为 `null`，空串等同未提供。
 */
fun isTokenSatisfied(config: LocalServerConfig, provided: String?): Boolean {
    val expected = config.accessToken
    if (expected.isBlank()) {
        // 没配令牌时「回环放行」是唯一的安全豁免；非回环一律拒绝。
        return config.boundToLoopback
    }
    // 配了令牌就必须校验 —— 否则用户以为设了令牌有保护，实际任意进程都能直接拉流。
    val given = provided?.takeIf { it.isNotEmpty() } ?: return false
    return given == expected
}
