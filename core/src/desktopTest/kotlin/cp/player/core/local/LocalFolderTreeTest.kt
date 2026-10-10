package cp.player.core.local

import cp.player.core.media.LocalMediaItem
import cp.player.core.media.LocalTrackMetadata
import cp.player.core.media.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文件夹树测试。
 *
 * 最要紧的一条是「公共前缀不能当根节点」——Android 上直接展开会得到
 * `/ → storage → emulated → 0 → Music` 五层空壳，用户要连点五次才见到音乐。
 */
class LocalFolderTreeTest {

    @Test
    fun `Android 路径的公共前缀不当作根节点`() {
        val items = listOf(
            audio("/storage/emulated/0/Music/专辑A/1.mp3"),
            audio("/storage/emulated/0/Music/专辑B/2.mp3"),
        )
        val nodes = LocalFolderTree.build(items)
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.depth == 1 }, "根节点应当直接是专辑目录")
        assertTrue(nodes.all { it.parentPath == null })
        assertEquals(setOf("专辑A", "专辑B"), nodes.map { it.name }.toSet())
    }

    @Test
    fun `Windows 路径同样剥掉公共前缀`() {
        val items = listOf(
            audio("C:\\Users\\me\\Music\\AlbumA\\1.mp3"),
            audio("C:\\Users\\me\\Music\\AlbumB\\2.mp3"),
        )
        val nodes = LocalFolderTree.build(items)
        assertEquals(setOf("AlbumA", "AlbumB"), nodes.map { it.name }.toSet())
        assertTrue(nodes.all { it.depth == 1 })
    }

    @Test
    fun `单个目录时根节点是该目录本身`() {
        val nodes = LocalFolderTree.build(listOf(audio("/music/AlbumA/1.mp3")))
        assertEquals(1, nodes.size)
        assertEquals("AlbumA", nodes.single().name)
        assertEquals(1, nodes.single().depth)
    }

    @Test
    fun `嵌套目录生成层级与父子关系`() {
        val items = listOf(
            audio("/music/A/1.mp3"),
            audio("/music/A/sub/2.mp3"),
        )
        val nodes = LocalFolderTree.build(items)
        assertEquals(2, nodes.size)
        val parent = nodes.single { it.name == "A" }
        val child = nodes.single { it.name == "sub" }
        assertEquals(1, parent.depth)
        assertNull(parent.parentPath)
        assertTrue(parent.hasChildren)
        assertEquals(2, child.depth)
        assertEquals(parent.path, child.parentPath)
    }

    @Test
    fun `父节点排在自己的子节点之前`() {
        // 顺序渲染依赖这一点：父节点必须先出现，否则缩进树会错位
        val items = listOf(
            audio("/music/A/sub/2.mp3"),
            audio("/music/A/1.mp3"),
        )
        val nodes = LocalFolderTree.build(items)
        assertEquals(listOf("A", "sub"), nodes.map { it.name })
    }

    @Test
    fun `递归曲目数含子目录`() {
        val items = listOf(
            audio("/music/A/1.mp3"),
            audio("/music/A/sub/2.mp3"),
            audio("/music/A/sub/3.mp3"),
        )
        val parent = LocalFolderTree.build(items).single { it.name == "A" }
        assertEquals(1, parent.directSongCount, "本目录直接包含 1 首")
        assertEquals(3, parent.totalSongCount, "含子目录共 3 首")
    }

    @Test
    fun `content URI 不参与目录树`() {
        val items = listOf(
            audio("content://com.android.externalstorage.documents/tree/primary%3AMusic/song.mp3"),
        )
        assertTrue(LocalFolderTree.build(items).isEmpty())
    }

    @Test
    fun `视频不参与目录树`() {
        val items = listOf(
            audio("/music/A/1.mp3"),
            LocalMediaItem(path = "/music/A/movie.mkv", title = "m", mediaType = MediaType.VIDEO),
        )
        assertEquals(1, LocalFolderTree.build(items).size)
    }

    // ============ 展开 / 折叠 ============

    @Test
    fun `默认只显示顶层目录`() {
        val items = listOf(
            audio("/music/A/1.mp3"),
            audio("/music/A/sub/2.mp3"),
            audio("/music/B/3.mp3"),
        )
        val nodes = LocalFolderTree.build(items)
        val visible = LocalFolderTree.visibleNodes(nodes, expanded = emptySet())
        assertEquals(setOf("A", "B"), visible.map { it.name }.toSet())
    }

    @Test
    fun `展开父目录后子目录出现`() {
        val items = listOf(
            audio("/music/A/1.mp3"),
            audio("/music/A/sub/2.mp3"),
        )
        val nodes = LocalFolderTree.build(items)
        val parent = nodes.single { it.name == "A" }
        val visible = LocalFolderTree.visibleNodes(nodes, expanded = setOf(parent.path))
        assertEquals(listOf("A", "sub"), visible.map { it.name })
    }

    @Test
    fun `父目录未展开时孙目录不显示`() {
        // 少判一层就会漏出「爷爷折叠着、孙子露在外面」的错位。
        // 这里必须让公共前缀停在 /music/A —— 只有一条路径时前缀会被剥到只剩根，
        // 就构造不出「父未展开」的局面了。
        val items = listOf(
            audio("/music/A/mid/leaf/1.mp3"),
            audio("/music/A/other/2.mp3"),
        )
        val nodes = LocalFolderTree.build(items)
        assertEquals(listOf("mid", "other", "leaf"), nodes.map { it.name })
        val leaf = nodes.single { it.name == "leaf" }
        // 只展开 leaf —— 但它的父 mid 自己没展开，所以 leaf 不该出现
        val visible = LocalFolderTree.visibleNodes(nodes, expanded = setOf(leaf.path))
        assertEquals(listOf("mid", "other"), visible.map { it.name })
    }

    // ============ 曲目归属 ============

    @Test
    fun `直接曲目不含子目录`() {
        val items = listOf(
            audio("/music/A/1.mp3"),
            audio("/music/A/sub/2.mp3"),
        )
        val node = LocalFolderTree.build(items).single { it.name == "A" }
        assertEquals(listOf("/music/A/1.mp3"), LocalFolderTree.directSongs(items, node).map { it.path })
    }

    @Test
    fun `全部曲目含子目录并按目录与轨号排序`() {
        val items = listOf(
            audio("/music/A/sub/1.mp3", track = 2),
            audio("/music/A/2.mp3", track = 2),
            audio("/music/A/1.mp3", track = 1),
        )
        val node = LocalFolderTree.build(items).single { it.name == "A" }
        val all = LocalFolderTree.allSongs(items, node)
        assertEquals(3, all.size)
        // 同一目录内按轨号：1 在前
        assertEquals(
            listOf("/music/A/1.mp3", "/music/A/2.mp3"),
            all.filter { !it.path.contains("/sub/") }.map { it.path },
        )
    }

    @Test
    fun `前缀相似的兄弟目录不会被误当成子目录`() {
        val items = listOf(
            audio("/music/Ab/1.mp3"),
            audio("/music/A/2.mp3"),
        )
        val nodeA = LocalFolderTree.build(items).single { it.name == "A" }
        val all = LocalFolderTree.allSongs(items, nodeA)
        assertEquals(
            listOf("/music/A/2.mp3"),
            all.map { it.path },
            "`/music/Ab` 不应被当成 `/music/A` 的子目录",
        )
    }

    // ============ 路径工具 ============

    @Test
    fun `parentOf 处理各种根形态`() {
        assertEquals("/a/b", LocalFolderTree.parentOf("/a/b/c"))
        assertEquals("", LocalFolderTree.parentOf("/a"))
        assertEquals("", LocalFolderTree.parentOf("C:"))
        assertEquals("C:", LocalFolderTree.parentOf("C:/Music"))
        assertEquals("", LocalFolderTree.parentOf("content://x/y"))
    }

    @Test
    fun `ancestorsOf 沿路径向上`() {
        assertEquals(
            listOf("/a/b/c", "/a/b", "/a"),
            LocalFolderTree.ancestorsOf("/a/b/c/d"),
        )
    }

    private fun audio(path: String, track: Int? = null) = LocalMediaItem(
        path = path,
        title = path.substringAfterLast('/'),
        durationMs = 60_000,
        mediaType = MediaType.AUDIO,
        coverUri = null,
        metadata = LocalTrackMetadata(trackNumber = track),
    )
}
