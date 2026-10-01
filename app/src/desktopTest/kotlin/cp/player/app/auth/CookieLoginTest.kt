package cp.player.app.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CookieLogin] 的清洗规则。
 *
 * 这些用例全部来自「用户实际会粘进来的东西」：DevTools 的整行请求头、多行文本、
 * 带说明文字的教程片段、以及粘错的网址。清洗错了的表现是**登录静默失败**
 * （cookie 被当成畸形串发给 Provider），排查成本很高，所以钉死在这里。
 */
class CookieLoginTest {

    @Test
    fun `plain cookie passes through`() {
        assertEquals(
            "MUSIC_U=abc; __csrf=def",
            CookieLogin.normalize("MUSIC_U=abc; __csrf=def"),
        )
    }

    @Test
    fun `strips the Cookie header prefix`() {
        assertEquals(
            "MUSIC_U=abc; __csrf=def",
            CookieLogin.normalize("Cookie: MUSIC_U=abc; __csrf=def"),
        )
        // 大小写与 set-cookie 变体都要认
        assertEquals(
            "MUSIC_U=abc",
            CookieLogin.normalize("set-cookie: MUSIC_U=abc"),
        )
        assertEquals(
            "MUSIC_U=abc",
            CookieLogin.normalize("COOKIE:MUSIC_U=abc"),
        )
    }

    @Test
    fun `folds newlines and tabs into separators`() {
        assertEquals(
            "MUSIC_U=abc; __csrf=def; os=pc",
            CookieLogin.normalize("MUSIC_U=abc;\n__csrf=def\tos=pc"),
        )
    }

    @Test
    fun `drops fragments without a name-value pair`() {
        assertEquals(
            "MUSIC_U=abc; __csrf=def",
            CookieLogin.normalize("  MUSIC_U = abc ;  ; 从浏览器复制 ; __csrf=def  "),
        )
    }

    @Test
    fun `keeps the last value for a duplicated name`() {
        // 后面那段通常才是新的（用户先粘旧的、再补上新的）
        assertEquals("a=3; b=2", CookieLogin.normalize("a=1; b=2; a=3"))
    }

    @Test
    fun `does not split on commas inside a value`() {
        // `Expires=Wed, 21 Oct 2015 …` 的值里就有逗号。按逗号切会把日期切成两半，
        // 所以只认 `;` 与换行。
        val normalized = CookieLogin.normalize(
            "MUSIC_U=abc; Expires=Wed, 21 Oct 2015 07:28:00 GMT",
        )
        assertEquals("MUSIC_U=abc; Expires=Wed, 21 Oct 2015 07:28:00 GMT", normalized)
    }

    @Test
    fun `returns null when nothing looks like a cookie`() {
        assertNull(CookieLogin.normalize(""))
        assertNull(CookieLogin.normalize("   "))
        assertNull(CookieLogin.normalize("https://music.163.com/#/login"))
        assertNull(CookieLogin.normalize("请把 cookie 粘贴到这里"))
        // 超长：基本是整段请求 / 整个 HTML 粘进来了
        assertNull(CookieLogin.normalize("a=" + "x".repeat(9000)))
    }

    @Test
    fun `session key detection is case-insensitive and advisory only`() {
        assertTrue(CookieLogin.hasSessionKey("MUSIC_U=abc; __csrf=def"))
        assertTrue(CookieLogin.hasSessionKey("music_u=abc"))
        assertTrue(CookieLogin.hasSessionKey("foo=1; __csrf=def"))
        assertFalse(CookieLogin.hasSessionKey("foo=1; bar=2"))
    }

    @Test
    fun `mask hides the value body but keeps its length`() {
        assertEquals(
            "MUSIC_U=abcd…(8); __csrf=xyz…(3)",
            CookieLogin.mask("MUSIC_U=abcdefgh; __csrf=xyz"),
        )
        // 短值原样显示（没什么可藏的，也便于用户核对）
        assertEquals("a=1…(1)", CookieLogin.mask("a=1"))
    }
}
