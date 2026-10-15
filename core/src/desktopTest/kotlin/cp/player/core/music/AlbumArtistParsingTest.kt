package cp.player.core.music

import cp.player.core.BackendResult
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 搜索 / 专辑 / 歌手 / 用户资料解析的回归测试。
 *
 * 钉住的核心 bug：**「专辑」页签恒为空**。
 * `cloudsearch?type=10` 返回的是 `result.albums`，而旧实现里
 * [SearchResult] 根本没有 `albums` 字段，界面拿 `result.playlists` 顶替 ——
 * 上游在专辑搜索里不返回那个数组，于是计数恒为 0、永远是「没有找到结果」。
 */
class AlbumArtistParsingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(raw: String) = json.parseToJsonElement(raw)

    @Test
    fun `专辑搜索结果解析到 albums 而不是 playlists`() {
        val payload = """
            {
              "code": 200,
              "result": {
                "albumCount": 2,
                "albums": [
                  {
                    "id": 1001,
                    "name": "范特西",
                    "picUrl": "https://example.com/a1.jpg",
                    "artist": { "id": 6452, "name": "周杰伦" },
                    "size": 10,
                    "publishTime": 998688000000
                  },
                  {
                    "id": 1002,
                    "name": "叶惠美",
                    "picUrl": "https://example.com/a2.jpg",
                    "artists": [ { "id": 6452, "name": "周杰伦" } ],
                    "trackCount": 11
                  }
                ]
              }
            }
        """.trimIndent()

        val result = MusicSourceFromApi.parseSearchSongs(parse(payload), type = 10)
        assertTrue(result is BackendResult.Success, "解析应当成功，实际: $result")
        val data = result.data

        // 关键断言：数据必须落在 albums 上。
        assertEquals(2, data.albums.size, "专辑搜索结果必须解析到 SearchResult.albums")
        assertTrue(data.playlists.isEmpty(), "专辑搜索不应凭空产生歌单")
        assertTrue(data.songs.isEmpty(), "专辑搜索不应凭空产生单曲")

        val first = data.albums[0]
        assertEquals(1001L, first.id)
        assertEquals("范特西", first.name)
        assertEquals("周杰伦", first.artistName)
        assertEquals(6452L, first.artistId)
        assertEquals(10, first.trackCount)
        assertEquals(998688000000L, first.publishTimeMs)

        // `artists` 数组形态（第二个条目）与 `artist` 单对象形态必须解析出同样的字段。
        val second = data.albums[1]
        assertEquals("周杰伦", second.artistName)
        assertEquals(6452L, second.artistId)
        assertEquals(11, second.trackCount)
    }

    @Test
    fun `歌手搜索结果保留头像与 id`() {
        val payload = """
            {
              "code": 200,
              "result": {
                "artists": [
                  { "id": 6452, "name": "周杰伦", "img1v1Url": "https://example.com/av1.jpg" },
                  { "id": 0, "name": "" }
                ]
              }
            }
        """.trimIndent()

        val data = (MusicSourceFromApi.parseSearchSongs(parse(payload), type = 100) as BackendResult.Success).data
        assertEquals(1, data.artists.size, "id/name 缺失的条目应当被丢弃")
        assertEquals(6452L, data.artists[0].id)
        assertEquals("周杰伦", data.artists[0].name)
        assertEquals("https://example.com/av1.jpg", data.artists[0].avatarUrl)
    }

    @Test
    fun `歌手资料取 data_artist 且 fan 数来自 data_user`() {
        val payload = """
            {
              "code": 200,
              "data": {
                "artist": {
                  "id": 6452,
                  "name": "周杰伦",
                  "alias": ["Jay Chou"],
                  "albumSize": 40,
                  "musicSize": 500,
                  "briefDesc": "华语流行歌手"
                },
                "user": { "followeds": 123456 }
              }
            }
        """.trimIndent()

        val profile = MusicSourceFromApi.parseArtistProfile(parse(payload))
        assertNotNull(profile)
        assertEquals(6452L, profile.id)
        assertEquals("周杰伦", profile.name)
        assertEquals(40, profile.albumSize)
        assertEquals(500, profile.musicSize)
        assertEquals("华语流行歌手", profile.briefDesc)
        assertEquals(listOf("Jay Chou"), profile.alias)
        // 粉丝数不在 artist 上，而在同级的 user 里 —— 取错就会恒为 0。
        assertEquals(123456, profile.followeds)
    }

    @Test
    fun `普通用户返回 null 而不是抛异常`() {
        // `artist/detail` 对普通用户 id 会返回 200 但没有 data.artist。
        val payload = """{ "code": 200, "data": { "user": { "followeds": 3 } } }"""
        assertEquals(null, MusicSourceFromApi.parseArtistProfile(parse(payload)))
    }

    @Test
    fun `专辑详情从 album_songs 取曲目`() {
        val payload = """
            {
              "code": 200,
              "album": {
                "id": 1001,
                "name": "范特西",
                "picUrl": "https://example.com/a1.jpg",
                "artist": { "id": 6452, "name": "周杰伦" },
                "company": "杰威尔",
                "description": "第二张专辑",
                "publishTime": 998688000000,
                "songs": [
                  {
                    "id": 3001,
                    "name": "爱在西元前",
                    "ar": [ { "name": "周杰伦" } ],
                    "al": { "name": "范特西", "picUrl": "https://example.com/a1.jpg" },
                    "dt": 234000
                  }
                ]
              }
            }
        """.trimIndent()

        val detail = (MusicSourceFromApi.parseAlbumDetail(parse(payload)) as BackendResult.Success).data
        assertEquals(1001L, detail.id)
        assertEquals("周杰伦", detail.artistName)
        assertEquals(6452L, detail.artistId)
        assertEquals("杰威尔", detail.company)
        assertEquals(1, detail.tracks.size)
        assertEquals("3001", detail.tracks[0].id)
        assertEquals("爱在西元前", detail.tracks[0].name)
        assertEquals("周杰伦", detail.tracks[0].artist)
        assertEquals(234000L, detail.tracks[0].durationMs)
    }

    @Test
    fun `专辑详情取顶层 songs 而不是为空的 album_songs`() {
        // ⚠️ 这是网易云 v1 `/api/v1/album/{id}` 的**真实形状**（2026-10-15 用 JNI 直连
        // 音源模块 ncm_api_rs.dll 抓下来的）：`album.songs` 存在但**是空数组**，
        // 真正的曲目在与 `album` 平级的 `songs` 里。
        // 旧实现写的是 `album["songs"] ?: this["songs"]` —— elvis 链在第一项就停下，
        // 于是专辑详情恒解析出 0 首曲目，界面永远显示「暂无曲目」。
        val payload = """
            {
              "code": 200,
              "album": {
                "id": 18875,
                "name": "跨时代",
                "picUrl": "https://example.com/a2.jpg",
                "artist": { "id": 6452, "name": "周杰伦" },
                "company": "杰威尔",
                "publishTime": 1274112000000,
                "size": 2,
                "songs": []
              },
              "songs": [
                {
                  "id": 185666,
                  "name": "跨时代",
                  "ar": [ { "id": 6452, "name": "周杰伦" } ],
                  "al": { "name": "跨时代", "picUrl": "https://example.com/a2.jpg" },
                  "dt": 194000
                },
                {
                  "id": 185667,
                  "name": "说了再见",
                  "ar": [ { "id": 6452, "name": "周杰伦" } ],
                  "al": { "name": "跨时代", "picUrl": "https://example.com/a2.jpg" },
                  "dt": 235000
                }
              ]
            }
        """.trimIndent()

        val detail = (MusicSourceFromApi.parseAlbumDetail(parse(payload)) as BackendResult.Success).data
        assertEquals(2, detail.tracks.size, "曲目必须从顶层 songs 取到，而不是那个空的 album.songs")
        assertEquals("185666", detail.tracks[0].id)
        assertEquals("跨时代", detail.tracks[0].name)
        assertEquals("周杰伦", detail.tracks[0].artist)
        // 专辑元信息仍要来自 `album` 对象本身（不是被顶层 songs 顶掉）。
        assertEquals(18875L, detail.id)
        assertEquals("周杰伦", detail.artistName)
        assertEquals("杰威尔", detail.company)
    }

    @Test
    fun `专辑详情两处曲目都为空时返回空列表而不是报错`() {
        // 「这张专辑没有可播放曲目」是正常状态，不该让整页变成错误态。
        val payload = """{ "code": 200, "album": { "id": 1, "name": "空专辑", "songs": [] }, "songs": [] }"""
        val detail = (MusicSourceFromApi.parseAlbumDetail(parse(payload)) as BackendResult.Success).data
        assertTrue(detail.tracks.isEmpty())
    }

    @Test
    fun `收藏专辑取非空的 data 而不是空壳 albums`() {
        // `parseAlbumArray` 同一类缺陷：`albums` 存在但为空、真数据在 `data` 里时，
        // 只判「存在」会让整面专辑墙空掉。
        val payload = """
            {
              "code": 200,
              "albums": [],
              "data": [
                { "id": 1001, "name": "范特西", "artist": { "id": 6452, "name": "周杰伦" }, "size": 10 }
              ]
            }
        """.trimIndent()

        val albums = (MusicSourceFromApi.parseAlbumSublist(parse(payload)) as BackendResult.Success).data
        assertEquals(1, albums.size)
        assertEquals(1001L, albums[0].id)
    }

    @Test
    fun `用户详情从 profile 解析关注与粉丝`() {
        val payload = """
            {
              "code": 200,
              "profile": {
                "userId": 42,
                "nickname": "听歌的人",
                "avatarUrl": "https://example.com/u.jpg",
                "signature": "晚安",
                "follows": 12,
                "followeds": 34,
                "playlistCount": 5
              }
            }
        """.trimIndent()

        val profile = MusicSourceFromApi.parseUserDetail(parse(payload))
        assertNotNull(profile)
        assertEquals(42L, profile.userId)
        assertEquals("听歌的人", profile.nickname)
        assertEquals("晚安", profile.signature)
        assertEquals(12, profile.follows)
        assertEquals(34, profile.followeds)
        assertEquals(5, profile.playlistCount)
    }

    @Test
    fun `听歌排行从条目里的 song 取曲目`() {
        val payload = """
            {
              "code": 200,
              "allData": [
                { "playCount": 30, "song": { "id": 3001, "name": "爱在西元前", "ar": [ { "name": "周杰伦" } ] } },
                { "playCount": 5 }
              ]
            }
        """.trimIndent()

        val tracks = (MusicSourceFromApi.parseUserRecords(parse(payload)) as BackendResult.Success).data
        assertEquals(1, tracks.size, "没有 song 字段的条目应当被丢弃")
        assertEquals("3001", tracks[0].id)
        assertEquals("爱在西元前", tracks[0].name)
    }

    @Test
    fun `关注列表认 userId 与 id 两种键`() {
        val payload = """
            {
              "code": 200,
              "follow": [
                { "userId": 7, "nickname": "A", "avatarUrl": "https://example.com/a.jpg" },
                { "id": 8, "name": "B" },
                { "nickname": "没有 id" }
              ]
            }
        """.trimIndent()

        val users = (MusicSourceFromApi.parseUserList(parse(payload)) as BackendResult.Success).data
        assertEquals(2, users.size)
        assertEquals(7L, users[0].id)
        assertEquals("A", users[0].name)
        assertEquals(8L, users[1].id)
        assertEquals("B", users[1].name)
    }

    @Test
    fun `歌手专辑从 hotAlbums 解析`() {
        val payload = """
            {
              "code": 200,
              "hotAlbums": [
                {
                  "id": 1001,
                  "name": "范特西",
                  "picUrl": "https://example.com/a1.jpg",
                  "artist": { "id": 6452, "name": "周杰伦" },
                  "size": 10,
                  "publishTime": 998688000000
                }
              ]
            }
        """.trimIndent()

        val albums = (MusicSourceFromApi.parseArtistAlbums(parse(payload)) as BackendResult.Success).data
        assertEquals(1, albums.size)
        assertEquals(1001L, albums[0].id)
        assertEquals(6452L, albums[0].artistId)
    }
}
