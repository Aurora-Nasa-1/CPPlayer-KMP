package cp.player.app.repository

import cp.player.core.api.MusicApiService
import kotlinx.serialization.json.JsonElement

class AuthRepository(private val api: MusicApiService) {
    suspend fun getLoginStatus(): JsonElement = api.getLoginStatus()

    suspend fun getQrKey(): JsonElement = api.getQrKey()

    suspend fun createQrCode(key: String): JsonElement = api.createQrCode(key)

    suspend fun checkQrStatus(key: String): JsonElement = api.checkQrStatus(key)

    suspend fun login(email: String, password: String): JsonElement = api.login(email, password)

    suspend fun loginWithPhone(
        phone: String,
        codeOrPass: String,
        imageCaptcha: String? = null,
        captchaCookie: String? = null,
    ): JsonElement = api.loginWithPhone(phone, codeOrPass, imageCaptcha = imageCaptcha, captchaCookie = captchaCookie)

    suspend fun sendCaptcha(
        phone: String,
        imageCaptcha: String? = null,
        captchaCookie: String? = null,
    ): JsonElement = api.sendCaptcha(phone, imageCaptcha, captchaCookie)

    /**
     * 取音源的图形验证码（通用 `captcha/image` 方法）。
     *
     * 响应网易云形状：`{code:200, captchaImage:"data:image/...;base64,…", cookie:"…"}`；
     * `cookie` 是验证码会话，发送验证码 / 登录时原样传回（[sendCaptcha] / [loginWithPhone]）。
     * 音源未实现时返回 `{code:-1}` 形状，调用方按失败处理。
     */
    suspend fun getCaptchaImage(): JsonElement = api.callApi("captcha/image", emptyMap(), cookie = null)

    suspend fun loginAnonymous(): JsonElement = api.loginAnonymous()

    suspend fun logout(): JsonElement = api.logout()
}
