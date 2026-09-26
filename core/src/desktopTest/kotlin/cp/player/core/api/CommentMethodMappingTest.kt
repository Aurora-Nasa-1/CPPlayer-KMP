package cp.player.core.api

import cp.player.core.provider.ProviderCookieStorage
import cp.player.core.provider.ProviderManager
import cp.player.core.util.SettingsStorage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 评论端点的类型映射（`getCommentMethod`）。
 *
 * 这段映射在 KMP 移植时被整段换成了"恒返回 `comment/new`"：于是
 * `getComments(id, "playlist")` 与 `getComments(id, "music")` 打的是**同一个请求**，
 * 而 `comment/new` 又不在 `MusicApiServiceImpl` 的响应校验表里 —— 连响应形态都没人校验。
 * 对照基准是旧项目 `reference/cp-player-legacy` 里同样的 `when` 分支。
 *
 * 映射是纯函数、测起来最便宜，所以钉住它。它同时决定了
 * `CachedMusicApiService.isCacheable` 必须覆盖哪几个方法：漏一个，那个分支就静默不走缓存。
 */
class CommentMethodMappingTest {

    private class MapSettings : SettingsStorage {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() { map.clear() }
    }

    private val api = MusicApiServiceImpl(
        providerManager = ProviderManager(MapSettings(), ProviderCookieStorage(MapSettings())),
        cookieStorage = ProviderCookieStorage(MapSettings()),
    )

    @Test
    fun `按资源类型路由到各自的端点`() {
        assertEquals(MusicApiMethod.COMMENT_MUSIC, api.getCommentMethod("music"))
        assertEquals(MusicApiMethod.COMMENT_PLAYLIST, api.getCommentMethod("playlist"))
        assertEquals(MusicApiMethod.COMMENT_ALBUM, api.getCommentMethod("album"))
        assertEquals(MusicApiMethod.COMMENT_MV, api.getCommentMethod("mv"))
        assertEquals(MusicApiMethod.COMMENT_DJ, api.getCommentMethod("dj"))
        assertEquals(MusicApiMethod.COMMENT_VIDEO, api.getCommentMethod("video"))
    }

    @Test
    fun `未知类型退回音乐评论而不是新版评论接口`() {
        assertEquals(MusicApiMethod.COMMENT_MUSIC, api.getCommentMethod("event"))
        assertEquals(MusicApiMethod.COMMENT_MUSIC, api.getCommentMethod(""))
        // 大小写敏感，与旧项目行为一致
        assertEquals(MusicApiMethod.COMMENT_MUSIC, api.getCommentMethod("MUSIC"))
    }

    @Test
    fun `不再返回没有响应校验的新版评论端点`() {
        val allTypes = listOf("music", "playlist", "album", "mv", "dj", "video", "event", "")
        allTypes.forEach { type ->
            assertEquals(
                false,
                api.getCommentMethod(type) == MusicApiMethod.COMMENT_NEW,
                "type=$type 不应落到没有响应校验的 comment/new"
            )
        }
    }
}
