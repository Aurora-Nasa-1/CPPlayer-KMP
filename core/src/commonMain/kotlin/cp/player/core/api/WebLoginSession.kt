package cp.player.core.api

/**
 * 网页端登录会话。
 *
 * `sessionId` 是后续轮询的游标；音源不提供时为 null，调用方按「不轮询」处理。
 * 字段直接来自音源在 `AuthAction.WEBVIEW_URL` / `WEBVIEW_CHECK` 上的强类型返回
 * （`AuthActionResult`），不再经过 JSON 解析。
 */
data class WebLoginSession(val url: String, val sessionId: String?)

enum class WebLoginState { PENDING, AUTHORIZED, EXPIRED, DENIED, FAILED }
