package cp.player.core.music

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 领域模型必须实现 [java.io.Serializable]。
 *
 * 背景：Android 上 Voyager 保存返回栈（锁屏 / 切后台触发 `onSaveInstanceState`）走的是
 * Java 序列化，`Screen` 构造参数里携带的领域模型不可序列化就会抛
 * `NotSerializableException`（实锤：日推歌单页携带 `List<TrackSummary>`，锁屏即崩）。
 * 各模型经 `cp.player.core.util.JavaSerializable`（expect/actual = `java.io.Serializable`）桥接，
 * 本测试保证这条链没被无意拆掉。
 */
class JavaSerializableTest {

    private fun assertSerializable(vararg instances: Any) {
        for (instance in instances) {
            assertTrue(
                instance is Serializable,
                "${instance::class.simpleName} 必须实现 java.io.Serializable（Voyager 返回栈持久化依赖）",
            )
        }
    }

    @Test
    fun domainModelsAreJavaSerializable() {
        val track = TrackSummary(
            id = "1", name = "歌", artist = "歌手", album = "专辑", coverUrl = null,
            durationMs = 0L, artists = listOf(ArtistSummary(1L, "歌手", null)),
        )
        assertSerializable(
            PlaylistSummary(1L, "歌单", null, 1, null),
            track,
            track.artists.first(),
            PlaylistDetail(PlaylistSummary(1L, "歌单", null, 1, null), listOf(track), null),
            AlbumSummary(1L, "专辑", null, "歌手", 1),
            AlbumDetail(1L, "专辑", null, "歌手", 1L, null, null, null, listOf(track)),
            ArtistProfile(1L, "歌手", null, null, emptyList(), 0, 0, 0),
            ProfileBundle(1L, false, "昵称", null, null, 0, 0, 0),
        )
    }

    @Test
    fun trackSummaryRoundTripsThroughObjectStream() {
        val track = TrackSummary(
            id = "1", name = "歌", artist = "A / B", album = "专辑", coverUrl = "https://x/y.jpg",
            durationMs = 1234L, artists = listOf(ArtistSummary(1L, "A", null), ArtistSummary(0L, "B", null)),
        )
        val bytes = ByteArrayOutputStream().use { out ->
            ObjectOutputStream(out).use { it.writeObject(track) }
            out.toByteArray()
        }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
        assertEquals(track, restored)
    }
}
