package cp.player.core.integration

import cp.player.core.control.LocalServerConfig
import cp.player.core.control.isTokenSatisfied

/**
 * 数据面路由表。
 *
 * ⚠️ 这些字符串是**已发布的对外契约**（见 `docs/dev/INTEGRATION_API.md`）。
 * 改它们等于改对外接口，必须同时改文档 —— `IntegrationRoutingTest > 路由表与对外文档中的路径一致`
 * 就是那道提醒。
 */
object IntegrationRoutes {
    const val API_PREFIX = "/api/v1"

    const val META = "$API_PREFIX/meta"
    const val PROVIDERS = "$API_PREFIX/providers"
    const val SEARCH = "$API_PREFIX/search"
    const val TRACK = "$API_PREFIX/tracks/{mediaId}"
    const val TRACK_BATCH = "$API_PREFIX/tracks/batch"
    const val PLAYBACK = "$API_PREFIX/playback"
    const val PLAYBACK_ACTION = "$API_PREFIX/playback/{action}"
    const val EVENTS = "$API_PREFIX/events"
}

/**
 * 数据面的挂载点（平台无关视图）。
 *
 * 宿主只需要知道「有这么个东西可以挂进引擎」，不能看到 `Routing` ——
 * `ktor-server-*` 只在 `jvmMain` 声明，`commonMain` 里没有那些类型。
 * 真正的挂载签名由 `jvmMain` 的内部接口补上。
 */
interface IntegrationRouteMount

/**
 * 数据面闸门判定结果。
 *
 * [ALLOW] 之外都对应一种**不同的处置**，不能合并：
 * - [FACE_DISABLED]：面没开 → 引导用户去开开关；
 * - [UNAUTHORIZED]：令牌不对 → 引导用户去拿令牌。
 */
enum class IntegrationGate {
    ALLOW,
    UNAUTHORIZED,
    FACE_DISABLED,
}

/**
 * 数据面闸门。
 *
 * ### 判定顺序：**面 > 令牌**
 * 面没开时连「令牌对不对」都不该泄漏 —— 那会告诉未授权的调用方「服务在跑，
 * 只是没开」，属于多余的探测信息。
 *
 * ### 令牌比对委托给 [isTokenSatisfied]
 * 媒体面（`/stream`）与数据面共用**同一个函数**。曾两处各写一遍，漂移成
 * 「媒体面没配令牌就放行」⇒ 绑定 `0.0.0.0` + 令牌为空时局域网裸奔。
 * **同一个规则出现在两个调用点时就该抽函数。**
 *
 * @param bearerToken `Authorization: Bearer <token>` 解析出的令牌；没有该头时为 null
 * @param queryToken `?token=` 解析出的令牌；没有该参数时为 null
 */
fun decideDataApiGate(
    config: LocalServerConfig,
    bearerToken: String?,
    queryToken: String?,
): IntegrationGate {
    if (!config.exposeDataApi) return IntegrationGate.FACE_DISABLED

    // Bearer 优先；**给了非空 Bearer 就不再看 query** —— 两个凭据冲突时按 fail-closed
    // 处理，不挑一个「看起来对的」放行。
    // ⚠️ 空串要当「没给」处理：`Authorization: Bearer ` 不该遮蔽一个正确的 query 令牌。
    val provided = bearerToken?.takeIf { it.isNotEmpty() } ?: queryToken

    return if (isTokenSatisfied(config, provided)) IntegrationGate.ALLOW else IntegrationGate.UNAUTHORIZED
}

/**
 * 解析 `Authorization: Bearer <token>` 头。
 *
 * @return 令牌；不是 Bearer 方案、或方案名后没有令牌时返回 `null`。
 */
fun parseBearerToken(header: String?): String? {
    if (header.isNullOrBlank()) return null
    // 方案名大小写不敏感（RFC 7235），前后空白裁掉；令牌内部空白**保留**，
    // 由比对判定对错 —— 裁剪令牌内容会让「两个不同令牌被认为相同」。
    val trimmed = header.trim()
    val spaceIndex = trimmed.indexOfFirst { it.isWhitespace() }
    if (spaceIndex < 0) return null // 只有方案名没有令牌

    val scheme = trimmed.substring(0, spaceIndex)
    if (!scheme.equals("Bearer", ignoreCase = true)) return null

    val token = trimmed.substring(spaceIndex + 1).trim()
    return token.takeIf { it.isNotEmpty() }
}

/**
 * 平台实现：构造数据面挂载点。
 *
 * - jvm（Android / Desktop 共用）：`KtorIntegrationRoutes`，把路由挂进 Ktor 引擎。
 */
expect fun createIntegrationRoutes(service: IntegrationService): IntegrationRouteMount
