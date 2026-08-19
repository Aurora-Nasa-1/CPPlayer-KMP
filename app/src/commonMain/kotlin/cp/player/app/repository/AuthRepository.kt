package cp.player.app.repository

import cp.player.kmp.api.MusicApiService
import kotlinx.serialization.json.JsonElement

class AuthRepository(private val api: MusicApiService) {
    suspend fun getLoginStatus(): JsonElement = api.getLoginStatus()

    suspend fun getQrKey(): JsonElement = api.getQrKey()

    suspend fun createQrCode(key: String): JsonElement = api.createQrCode(key)

    suspend fun checkQrStatus(key: String): JsonElement = api.checkQrStatus(key)

    suspend fun login(email: String, password: String): JsonElement = api.login(email, password)

    suspend fun loginWithPhone(phone: String, codeOrPass: String): JsonElement =
        api.loginWithPhone(phone, codeOrPass)

    suspend fun sendCaptcha(phone: String): JsonElement = api.sendCaptcha(phone)

    suspend fun loginAnonymous(): JsonElement = api.loginAnonymous()

    suspend fun logout(): JsonElement = api.logout()
}
