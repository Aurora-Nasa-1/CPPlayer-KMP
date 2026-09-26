package cp.player.core.cache

import cp.player.core.api.MusicApiMethod
import cp.player.core.api.MusicApiService
import cp.player.core.provider.BackendProvider
import cp.player.core.provider.ProviderCookieStorage
import cp.player.core.provider.ProviderManager
import cp.player.core.util.SettingsStorage
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [CachedMusicApiService] 的读透行为。
 *
 * 这层是"看不见的"：它一旦失效，表现不是崩溃而是**每次都打网络**（用户只会觉得慢），
 * 而它一旦**过度**生效，表现是用户刚改完歌单却看不到变化。所以下面把两条边界都钉死：
 * 命中不再回源、写操作必须失效。
 *
 * 夹具说明：`MusicApiService` 有 100+ 个方法，手写桩不现实，用 JDK 动态代理按方法名拦。
 * 这是 desktopTest（JVM）专属手段，不影响 commonMain。
 */
class CachedMusicApiServiceTest {

    // ======================== 夹具 ========================

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

    private class Fixture(
        val cache: ApiCache = InMemoryApiCache(),
        config: CacheConfig = CacheConfig(),
        providers: List<BackendProvider> = emptyList(),
    ) {
        /** 底层被真正调用的方法名（命中缓存时不该增长）。 */
        val calls = mutableListOf<String>()

        /** 底层下一次返回什么。 */
        var next: JsonElement = ok("v1")

        /** 非 null 时底层抛这个异常，用来模拟网络故障。 */
        var throwOnCall: Exception? = null

        val providerManager = ProviderManager(MapSettings(), ProviderCookieStorage(MapSettings()))

        val api: CachedMusicApiService = CachedMusicApiService(
            delegate = fakeDelegate(),
            cache = cache,
            providerManager = providerManager,
            allProviders = { providers },
            config = config,
        )

        private fun fakeDelegate(): MusicApiService {
            val iface = MusicApiService::class.java
            return java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, m, rawArgs ->
                val name = m.name
                when {
                    m.declaringClass == Any::class.java -> when (name) {
                        "toString" -> "FakeMusicApiService"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === rawArgs?.firstOrNull()
                        else -> null
                    }
                    // 非 suspend 的接口默认方法：按生产实现的分支返回，
                    // 保证测试里的缓存键与真实运行时一致
                    name == "getCommentMethod" -> commentMethodOf(rawArgs?.firstOrNull() as? String ?: "")
                    else -> {
                        calls += name
                        throwOnCall?.let { throw it }
                        next
                    }
                }
            } as MusicApiService
        }

        /** 与 `MusicApiService.getCommentMethod` 保持一致的分支（代理必须自己实现这个非 suspend 方法）。 */
        private fun commentMethodOf(type: String): String = when (type) {
            "playlist" -> MusicApiMethod.COMMENT_PLAYLIST
            "album" -> MusicApiMethod.COMMENT_ALBUM
            else -> MusicApiMethod.COMMENT_MUSIC
        }
    }

    private fun fakeProvider(id: String, payload: JsonElement): BackendProvider {
        val iface = BackendProvider::class.java
        return java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, _ ->
            when (m.name) {
                "getId" -> id
                "getApiMap" -> emptyMap<String, String>()
                "callApi" -> payload.toString()
                else -> null
            }
        } as BackendProvider
    }

    /** 直接塞一条"很久以前"的缓存，用来模拟过期。 */
    private fun seedStale(cache: ApiCache, method: String, params: Map<String, String>, data: JsonElement) {
        cache.putData(
            cacheKey("default", method, params, null),
            data,
            now = currentTimeMillis() - 60 * 60 * 1000
        )
    }

    // ======================== 命中 / 回源 ========================

    @Test
    fun `TTL 内命中不再发起网络请求`() = runBlocking {
        val f = Fixture()

        assertEquals("v1", tagOf(f.api.getPlaylistDetail(1L)))
        assertEquals("v1", tagOf(f.api.getPlaylistDetail(1L)))

        assertEquals(1, f.calls.count { it == "getPlaylistDetail" })
        assertEquals(1L, f.api.stats.value.hits)
        assertEquals(1L, f.api.stats.value.misses)
        assertEquals(1L, f.api.stats.value.stores)
    }

    @Test
    fun `过期条目会回源并更新缓存`() = runBlocking {
        val cache = InMemoryApiCache()
        seedStale(cache, MusicApiMethod.PLAYLIST_DETAIL, mapOf("id" to "1"), ok("old"))

        val f = Fixture(cache = cache)
        f.next = ok("new")

        assertEquals("new", tagOf(f.api.getPlaylistDetail(1L)))
        // 回源结果已写回，第二次直接命中
        assertEquals("new", tagOf(f.api.getPlaylistDetail(1L)))
        assertEquals(1, f.calls.count { it == "getPlaylistDetail" })
    }

    @Test
    fun `总开关关闭时直通网络且不写缓存`() = runBlocking {
        val f = Fixture(config = CacheConfig(enableCache = false))

        f.api.getPlaylistDetail(1L)
        f.api.getPlaylistDetail(1L)

        assertEquals(2, f.calls.count { it == "getPlaylistDetail" })
        assertEquals(0, f.cache.size())
        assertEquals(0L, f.api.stats.value.hits)
    }

    @Test
    fun `同一端点下不同 type 不共用缓存`() = runBlocking {
        val f = Fixture()

        // "music" 与未知类型都会落到 comment/music（getCommentMethod 的 else 分支），
        // 端点相同 ⇒ 只能靠键里的 type 区分，否则两种评论互相串数据
        f.api.getComments("9", "music", 20, 0, 1)
        f.api.getComments("9", "event", 20, 0, 1)

        assertEquals(2, f.calls.count { it == "getComments" })
    }

    // ======================== 降级 ========================

    @Test
    fun `网络异常时回退到过期缓存而不是抛错`() = runBlocking {
        val cache = InMemoryApiCache()
        seedStale(cache, MusicApiMethod.PLAYLIST_DETAIL, mapOf("id" to "1"), ok("stale"))

        val f = Fixture(cache = cache)
        f.throwOnCall = RuntimeException("network down")

        assertEquals("stale", tagOf(f.api.getPlaylistDetail(1L)))
        assertEquals(1L, f.api.stats.value.staleServed)
    }

    @Test
    fun `没有旧缓存时网络异常原样抛出`() = runBlocking {
        val f = Fixture()
        f.throwOnCall = RuntimeException("network down")

        val thrown = assertFailsWith<RuntimeException> { f.api.getPlaylistDetail(1L) }
        assertEquals("network down", thrown.message)
    }

    @Test
    fun `失败响应不写缓存`() = runBlocking {
        val f = Fixture()
        f.next = err(500)

        assertEquals(500, codeOf(f.api.getPlaylistDetail(1L)))
        assertEquals(0, f.cache.size())
        assertEquals(0L, f.api.stats.value.stores)
    }

    @Test
    fun `回源失败时优先返回旧缓存`() = runBlocking {
        val cache = InMemoryApiCache()
        seedStale(cache, MusicApiMethod.PLAYLIST_DETAIL, mapOf("id" to "1"), ok("stale"))

        val f = Fixture(cache = cache)
        f.next = err(500)

        assertEquals("stale", tagOf(f.api.getPlaylistDetail(1L)))
        assertEquals(1L, f.api.stats.value.staleServed)
    }

    @Test
    fun `当前 Provider 失败时回退到其它 Provider 并写回缓存`() = runBlocking {
        val f = Fixture(providers = listOf(fakeProvider("p2", ok("from-p2"))))
        f.next = err(500)

        assertEquals("from-p2", tagOf(f.api.getPlaylistDetail(1L)))
        // 容灾结果同样进缓存：下次不再回源
        assertEquals("from-p2", tagOf(f.api.getPlaylistDetail(1L)))
        assertEquals(1, f.calls.count { it == "getPlaylistDetail" })
    }

    // ======================== 账号隔离与失效 ========================

    @Test
    fun `不同账号不共用缓存`() = runBlocking {
        val f = Fixture()

        f.providerManager.cookieStorage.saveCookie("default", "MUSIC_U=alice")
        assertEquals("v1", tagOf(f.api.getUserPlaylists(42L)))

        f.providerManager.cookieStorage.saveCookie("default", "MUSIC_U=bob")
        f.next = ok("v2")
        assertEquals("v2", tagOf(f.api.getUserPlaylists(42L)))

        assertEquals(2, f.calls.count { it == "getUserPlaylists" })
    }

    @Test
    fun `写操作会失效对应读缓存`() = runBlocking {
        val f = Fixture()

        f.api.getPlaylistTracks(7L, 1000, 0)
        f.api.getPlaylistTracks(7L, 1000, 0)
        assertEquals(1, f.calls.count { it == "getPlaylistTracks" })

        f.api.addTracksToPlaylist(7L, listOf("1"))

        f.next = ok("v2")
        assertEquals("v2", tagOf(f.api.getPlaylistTracks(7L, 1000, 0)))
        assertEquals(2, f.calls.count { it == "getPlaylistTracks" })
    }

    @Test
    fun `喜欢歌曲后不再返回旧的喜欢列表`() = runBlocking {
        val f = Fixture()

        f.api.getLikeList(42L)
        f.api.getLikeList(42L)
        assertEquals(1, f.calls.count { it == "getLikeList" })

        f.api.likeSong("1", true)

        f.next = ok("v2")
        assertEquals("v2", tagOf(f.api.getLikeList(42L)))
        assertEquals(2, f.calls.count { it == "getLikeList" })
    }

    @Test
    fun `发评论后不再返回旧的评论列表`() = runBlocking {
        val f = Fixture()

        f.api.getComments("9", "playlist", 20, 0, 1)
        f.api.getComments("9", "playlist", 20, 0, 1)
        assertEquals(1, f.calls.count { it == "getComments" })

        f.api.postComment("9", "playlist", "hello", null)

        f.next = ok("v2")
        assertEquals("v2", tagOf(f.api.getComments("9", "playlist", 20, 0, 1)))
        assertEquals(2, f.calls.count { it == "getComments" })
    }

    @Test
    fun `登出会清空缓存`() = runBlocking {
        val f = Fixture()

        f.api.getPlaylistDetail(1L)
        assertEquals(1, f.cache.size())

        f.api.logout()
        assertEquals(0, f.cache.size())
    }
}

// ======================== 断言辅助 ========================

private fun ok(tag: String): JsonElement = Json.parseToJsonElement("""{"code":200,"tag":"$tag"}""")

private fun err(code: Int): JsonElement = Json.parseToJsonElement("""{"code":$code,"msg":"boom"}""")

private fun tagOf(e: JsonElement): String? = ((e as? JsonObject)?.get("tag") as? JsonPrimitive)?.contentOrNull

private fun codeOf(e: JsonElement): Int? = ((e as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
