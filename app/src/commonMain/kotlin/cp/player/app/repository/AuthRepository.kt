package cp.player.app.repository

import cp.player.core.BackendResult
import cp.player.core.api.WebLoginSession
import cp.player.core.api.WebLoginState
import cp.player.core.music.UnifiedMusicSource
import cp.player.sdk.AuthAction
import cp.player.sdk.AuthActionRequest
import cp.player.sdk.AuthActionResult as ProviderAuthResult

data class QrKeyResult(
    val key: String?,
)

data class QrCodeResult(
    val url: String?,
    val imageBase64: String?,
)

enum class QrStatusKind {
    WAITING,
    SCANNED,
    SUCCESS,
    EXPIRED,
    UNKNOWN,
}

data class QrStatusResult(
    val status: QrStatusKind,
    val cookie: String?,
)

data class AuthActionResult(
    val ok: Boolean,
    val cookie: String? = null,
    val verifyUrl: String? = null,
    val isVerificationChallenge: Boolean = false,
    val reason: String? = null,
)

data class CaptchaImageResult(
    val ok: Boolean,
    val captchaImage: String?,
    val sessionCookie: String?,
)

data class WebLoginCheckResult(
    val state: WebLoginState,
    val cookie: String?,
)

/**
 * 强类型认证仓库（面向 UI 层）。
 *
 * 认证是音源 SDK 的一等能力：`UnifiedMusicSource.authenticate(AuthActionRequest)`
 * 直接返回 [ProviderAuthResult]，凭据由 ProviderHost 的账户会话持有。
 * 这里只做「动作 → 业务结果」的映射，不再解析任何 JSON。
 */
class AuthRepository(
    private val source: UnifiedMusicSource? = null,
) {

    suspend fun getLoginStatus(): Boolean = when (val result = source?.getCurrentAccount()) {
        is BackendResult.Success -> result.data.loggedIn
        is BackendResult.Error, is BackendResult.Unsupported, null -> false
    }

    suspend fun getQrKey(): QrKeyResult {
        val result = authenticate(AuthActionRequest(action = AuthAction.QR_KEY, md5 = false))
        return QrKeyResult(result?.key)
    }

    suspend fun createQrCode(key: String): QrCodeResult {
        val result = authenticate(AuthActionRequest(action = AuthAction.QR_CREATE, key = key, md5 = false))
        return QrCodeResult(url = result?.url, imageBase64 = result?.imageBase64)
    }

    suspend fun checkQrStatus(key: String): QrStatusResult {
        val result = authenticate(AuthActionRequest(action = AuthAction.QR_CHECK, key = key, md5 = false))
        return QrStatusResult(qrStatusOf(result), result?.cookie)
    }

    suspend fun login(email: String, password: String): AuthActionResult =
        toActionResult(authenticate(AuthActionRequest(action = AuthAction.LOGIN_EMAIL, email = email, password = password, md5 = false)))

    /**
     * 手机号登录。
     *
     * 单输入框同时收验证码与密码（UI 文案即「验证码或密码」）。历史实现固定按密码提交，
     * 这里保持一致：只有 `password` 字段会被填。需要走短信验证码登录时，
     * 应先 `sendCaptcha` 再由音源按其能力判定（见 `AuthActionRequest.captcha`）。
     */
    suspend fun loginWithPhone(
        phone: String,
        codeOrPass: String,
        imageCaptcha: String? = null,
        captchaCookie: String? = null,
    ): AuthActionResult = toActionResult(
        authenticate(
            AuthActionRequest(
                action = AuthAction.LOGIN_PHONE,
                phone = phone,
                password = codeOrPass,
                imageCaptcha = imageCaptcha,
                challengeCookie = captchaCookie,
                md5 = false,
            ),
        ),
    )

    suspend fun sendCaptcha(
        phone: String,
        imageCaptcha: String? = null,
        captchaCookie: String? = null,
    ): AuthActionResult = toActionResult(
        authenticate(
            AuthActionRequest(
                action = AuthAction.CAPTCHA_SEND,
                phone = phone,
                imageCaptcha = imageCaptcha,
                challengeCookie = captchaCookie,
                md5 = false,
            ),
        ),
    )

    /** 取音源的图形验证码；`ok` 只有在真正拿到图片时才为 true。 */
    suspend fun getCaptchaImage(): CaptchaImageResult {
        val result = authenticate(AuthActionRequest(action = AuthAction.CAPTCHA_IMAGE, md5 = false))
        val image = result?.imageBase64
        return CaptchaImageResult(
            ok = result?.success == true && !image.isNullOrBlank(),
            captchaImage = image,
            sessionCookie = result?.cookie,
        )
    }

    /**
     * 网页端登录入口。
     *
     * `sessionId` 取自 [ProviderAuthResult.key]：SDK 结果里没有独立的会话字段，
     * 音源约定用 `key` 承载轮询游标；没有就退化成 null（调用方按「不轮询」处理）。
     */
    suspend fun getWebviewLoginUrl(): WebLoginSession? {
        val result = authenticate(AuthActionRequest(action = AuthAction.WEBVIEW_URL, md5 = false))
        val url = result?.url?.takeIf(String::isNotBlank) ?: return null
        if (result?.success != true) return null
        return WebLoginSession(url, result.key?.takeIf(String::isNotBlank))
    }

    suspend fun checkWebviewLogin(sessionId: String): WebLoginCheckResult {
        val result = authenticate(
            AuthActionRequest(action = AuthAction.WEBVIEW_CHECK, sessionId = sessionId, md5 = false),
        )
        return WebLoginCheckResult(webLoginStateOf(result), result?.cookie)
    }

    suspend fun loginAnonymous(): AuthActionResult =
        toActionResult(authenticate(AuthActionRequest(action = AuthAction.LOGIN_ANONYMOUS, md5 = false)))

    suspend fun logout(): Boolean =
        authenticate(AuthActionRequest(action = AuthAction.LOGOUT, md5 = false))?.success == true

    // ======================== 映射 ========================

    private suspend fun authenticate(request: AuthActionRequest): ProviderAuthResult? =
        when (val result = source?.authenticate(request)) {
            is BackendResult.Success -> result.data
            is BackendResult.Error, is BackendResult.Unsupported, null -> null
        }

    private fun toActionResult(result: ProviderAuthResult?): AuthActionResult {
        if (result == null) return AuthActionResult(ok = false, reason = "当前音源不支持该登录方式")
        return AuthActionResult(
            ok = result.success,
            cookie = result.cookie,
            verifyUrl = result.verifyUrl,
            isVerificationChallenge = result.challenge,
            reason = result.message,
        )
    }

    /** 二维码状态：优先看 `status`（上游 800/801/802/803），没有再退回 `code`。 */
    private fun qrStatusOf(result: ProviderAuthResult?): QrStatusKind {
        if (result == null) return QrStatusKind.UNKNOWN
        if (result.cookie?.isNotBlank() == true && result.success) return QrStatusKind.SUCCESS
        return when (result.status ?: result.code) {
            803, 2, 4 -> QrStatusKind.SUCCESS
            802, 1 -> QrStatusKind.SCANNED
            800, 3 -> QrStatusKind.EXPIRED
            801, 0 -> QrStatusKind.WAITING
            else -> if (result.success) QrStatusKind.SUCCESS else QrStatusKind.UNKNOWN
        }
    }

    /** 网页登录状态：SDK 只给 `status`，约定 0 = 等待、其他非成功值一律按失败处理。 */
    private fun webLoginStateOf(result: ProviderAuthResult?): WebLoginState = when {
        result == null -> WebLoginState.FAILED
        result.success && result.cookie?.isNotBlank() == true -> WebLoginState.AUTHORIZED
        result.status == 0 -> WebLoginState.PENDING
        result.success -> WebLoginState.PENDING
        else -> WebLoginState.FAILED
    }
}
