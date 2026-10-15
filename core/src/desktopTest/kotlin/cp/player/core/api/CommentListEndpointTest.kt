package cp.player.core.api

import cp.player.core.provider.BackendProvider
import cp.player.core.provider.ProviderCookieStorage
import cp.player.core.provider.ProviderManager
import cp.player.core.provider.ProviderType
import cp.player.core.util.PlatformContext
import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 评论**列表**（顶层）请求的形状。
 *
 * ### 为什么单独钉这一条
 * 列表原先走 `getCommentMethod(type)` 返回的 `comment/music` 那一族老端点，而它们的请求体
 * 里只有 `rid` / `limit` / `offset` / `beforeTime` —— **`sortType` 会被 Provider 原样丢掉**
 * （`reference/netease-module-rust/src/api/comment_music.rs`）。表现是：三个排序页签
 * 拿到的是同一份按时间排的列表，点「热度」什么都不变，而请求里那个 `sortType` 参数
 * 让人以为链路是通的。编译期、单测、日志都看不出问题。
 *
 * 现在列表固定走 `comment/new`（唯一认 `sortType` 的端点），并附带两处**必须一起改**的换算：
 * - 资源类型由 `type` 类型码表达（端点不再按类型分）；
 * - 分页是 `pageNo` / `pageSize`，调用方仍传 `offset` ⇒ 必须正确换算，
 *   算错会「下一页拉回同一页」。
 */
class CommentListEndpointTest {

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

    /** 只记录「最后一次调用」的 Provider 替身。 */
    private class RecordingProvider : BackendProvider {
        override val id: String = "recording"
        override val name: String = "Recording"
        override val version: String = "1.0.0"
        override val type: ProviderType = ProviderType.JNI
        override val apiMap: Map<String, String>? = null
        override val updateUrl: String? = null
        override val targetAppPackage: String? = null

        var lastMethod: String? = null
        var lastParams: Map<String, String> = emptyMap()

        override fun startServer(context: PlatformContext, port: Int) = Unit
        override fun stopServer() = Unit
        override fun callApi(method: String, params: Map<String, String>): String {
            lastMethod = method
            lastParams = params
            return """{"code":200,"data":{"comments":[]}}"""
        }

        override fun analyzeAudio(path: String): String = """{"code":200}"""
    }

    private fun fixture(): Pair<MusicApiServiceImpl, RecordingProvider> {
        val settings = MapSettings()
        val manager = ProviderManager(settings, ProviderCookieStorage(settings))
        val provider = RecordingProvider()
        // 不传 context ⇒ 不启动服务、不找端口，只切「当前 Provider」。
        check(manager.switchProvider(provider, context = null, save = false)) { "切换 Provider 失败" }
        return MusicApiServiceImpl(manager, ProviderCookieStorage(settings)) to provider
    }

    @Test
    fun `列表走 comment_new 并带上 sortType 与资源类型码`() = runBlocking {
        val (api, provider) = fixture()

        api.getComments(id = "123", type = "music", limit = 20, offset = 0, sortType = 2)

        assertEquals(MusicApiMethod.COMMENT_NEW, provider.lastMethod)
        assertEquals("123", provider.lastParams["id"])
        assertEquals("2", provider.lastParams["sortType"], "热度排序必须真的发出去")
        assertEquals("0", provider.lastParams["type"], "music ⇒ 类型码 0")
        assertEquals("20", provider.lastParams["pageSize"])
    }

    @Test
    fun `offset 换算成页码而不是原样透传`() = runBlocking {
        val (api, provider) = fixture()

        api.getComments("123", "music", limit = 20, offset = 0, sortType = 1)
        assertEquals("1", provider.lastParams["pageNo"])

        api.getComments("123", "music", limit = 20, offset = 20, sortType = 1)
        assertEquals("2", provider.lastParams["pageNo"])

        api.getComments("123", "music", limit = 20, offset = 40, sortType = 1)
        assertEquals("3", provider.lastParams["pageNo"])

        // 非 20 的页大小同样要对（调用方目前固定 20，但换算规则不能只在一种取值下成立）
        api.getComments("123", "music", limit = 10, offset = 30, sortType = 1)
        assertEquals("4", provider.lastParams["pageNo"])
        assertEquals("10", provider.lastParams["pageSize"])
    }

    @Test
    fun `六种资源类型都映射成类型码`() = runBlocking {
        val (api, provider) = fixture()
        val expected = mapOf(
            "music" to "0",
            "mv" to "1",
            "playlist" to "2",
            "album" to "3",
            "dj" to "4",
            "video" to "5",
        )
        expected.forEach { (type, code) ->
            api.getComments("1", type, 20, 0, 1)
            assertEquals(code, provider.lastParams["type"], "type=$type 的类型码")
            assertEquals(MusicApiMethod.COMMENT_NEW, provider.lastMethod)
        }
    }

    @Test
    fun `三个排序取值原样透传`() = runBlocking {
        val (api, provider) = fixture()
        listOf(1, 2, 3).forEach { sort ->
            api.getComments("1", "music", 20, 0, sort)
            assertEquals(sort.toString(), provider.lastParams["sortType"], "sortType=$sort")
        }
    }

    @Test
    fun `列表不再打到老端点`() = runBlocking {
        val (api, provider) = fixture()
        val legacy = setOf(
            MusicApiMethod.COMMENT_MUSIC,
            MusicApiMethod.COMMENT_PLAYLIST,
            MusicApiMethod.COMMENT_ALBUM,
            MusicApiMethod.COMMENT_MV,
            MusicApiMethod.COMMENT_DJ,
            MusicApiMethod.COMMENT_VIDEO,
        )
        listOf("music", "playlist", "album", "mv", "dj", "video").forEach { type ->
            api.getComments("1", type, 20, 0, 1)
            assertTrue(
                provider.lastMethod !in legacy,
                "type=$type 仍打到老端点 ${provider.lastMethod} —— 那些端点收不到 sortType，排序会失效",
            )
        }
    }
}
