package cp.player.app.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 手机号登录字段分流：验证码与密码必须互斥。
 *
 * 回归事故：两者都塞进 `password` 时，网易云把短信验证码当密码做 MD5，
 * 上游返回 502，界面表现为「点了登录没反应」。
 */
class AuthPhoneLoginFieldsTest {

    @Test
    fun `sms code fills captcha and leaves password empty`() {
        assertEquals("123456", phoneLoginCaptcha("123456", useSmsCode = true))
        assertNull(phoneLoginPassword("123456", useSmsCode = true))
    }

    @Test
    fun `password always uses password field even when it is all digits`() {
        assertEquals("12345678", phoneLoginPassword("12345678", useSmsCode = false))
        assertNull(phoneLoginCaptcha("12345678", useSmsCode = false))
    }

    @Test
    fun `blank input never becomes a login credential`() {
        assertNull(phoneLoginCaptcha("", useSmsCode = true))
        assertNull(phoneLoginPassword("", useSmsCode = false))
    }
}
