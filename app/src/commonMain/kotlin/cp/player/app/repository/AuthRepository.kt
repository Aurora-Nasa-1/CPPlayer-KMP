package cp.player.app.repository

import cp.player.core.BackendResult
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

sealed interface LoginSessionCheck {
    data object LoggedIn : LoginSessionCheck
    data object LoggedOut : LoginSessionCheck
    data object NetworkError : LoginSessionCheck
}

/**
 * 强类型认证仓库（面向 UI 层）。
 *
 * 认证是音源 SDK 的一等能力：`UnifiedMusicSource.authenticate(AuthActionRequest)`
 * 直接返回 [ProviderAuthResult]，凭据由 ProviderHost 的账户会话持有。
 * 这里只做「动作 → 业务结果」的映射，不再解析任何 JSON。
 */
class AuthRepository(
    private val source: UnifiedMusicSource? = null,
    /** Provider-declared country code; when set it is authoritative. */
    private val defaultCountryCode: String? = null,
) {

    suspend fun getLoginStatus(): Boolean = checkLoginSession() == LoginSessionCheck.LoggedIn

    suspend fun checkLoginSession(): LoginSessionCheck = when (val result = source?.getCurrentAccount()) {
        is BackendResult.Success -> if (result.data.loggedIn) LoginSessionCheck.LoggedIn else LoginSessionCheck.LoggedOut
        is BackendResult.Unsupported -> LoginSessionCheck.LoggedOut
        is BackendResult.Error, null -> LoginSessionCheck.NetworkError
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
     * 短信验证码与密码必须分流到不同字段：网易云 `login/cellphone` 收到 `captcha`
     * 后会把 `password` 置为同一个值；若只填 `password`，短信验证码会被当成密码
     * 做 MD5 ⇒ 上游返回 502，UI 只看到「登录没反应」。
     *
     * 这里不用「纯数字 / 长度」猜：未调用 [sendCaptcha] 就直接提交的纯数字密码
     * （例如 8 位数字密码）会被误判成验证码。由调用方显式声明是验证码，其余一律
     * 按密码处理；验证码不走 MD5（`md5 = false` 只作用于密码字段）。
     */
    suspend fun loginWithPhone(
        phone: String,
        codeOrPass: String,
        useSmsCode: Boolean = false,
        countryCode: String? = null,
        imageCaptcha: String? = null,
        captchaCookie: String? = null,
    ): AuthActionResult = toActionResult(
        authenticate(
            AuthActionRequest(
                action = AuthAction.LOGIN_PHONE,
                phone = phone,
                password = phoneLoginPassword(codeOrPass, useSmsCode),
                captcha = phoneLoginCaptcha(codeOrPass, useSmsCode),
                countryCode = phoneCountryCode(countryCode, defaultCountryCode),
                imageCaptcha = imageCaptcha,
                challengeCookie = captchaCookie,
                md5 = false,
            ),
        ),
    )

    suspend fun sendCaptcha(
        phone: String,
        countryCode: String? = null,
        imageCaptcha: String? = null,
        captchaCookie: String? = null,
    ): AuthActionResult = toActionResult(
        authenticate(
            AuthActionRequest(
                action = AuthAction.CAPTCHA_SEND,
                phone = phone,
                countryCode = phoneCountryCode(countryCode, defaultCountryCode),
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

}

/**
 * 手机号登录的字段分流。
 *
 * 显式模式而不是按「纯数字 / 长度」猜：8 位数字密码不该被当成验证码。
 * `captcha` 与 `password` 必须互斥，否则网易云 `login/cellphone` 会把
 * 验证码同时塞进 `password` 做 MD5。
 */
internal fun phoneLoginPassword(codeOrPass: String, useSmsCode: Boolean): String? =
    codeOrPass.takeIf { !useSmsCode && it.isNotBlank() }

internal fun phoneLoginCaptcha(codeOrPass: String, useSmsCode: Boolean): String? =
    codeOrPass.takeIf { useSmsCode && it.isNotBlank() }

/**
 * 音源声明的区号优先；只有音源未声明时，调用方传入的值才生效。
 * 网易云在 manifest 里声明 `86`，因此宿主不再让用户改区号。
 */
internal fun phoneCountryCode(requested: String?, providerDefault: String?): String? =
    providerDefault?.takeIf { it.isNotBlank() } ?: requested?.takeIf { it.isNotBlank() }
