package cp.player.core.util

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 桌面数据目录改名（`.kmp-pro` → `.cpplayer`）与一次性迁移的测试。
 *
 * 这段逻辑的危险之处在于**它只会在用户升级后第一次启动时跑一次**：写错了，
 * 用户看到的是「渲染后端设置被重置、下载记录清空、本地媒体索引消失」，
 * 而开发机上永远不会复现（开发机两边目录都可能不存在）。
 *
 * 所以这里把四条不变式钉死：
 * 1. 旧目录存在、新目录不存在 → 数据必须**完整搬过去**；
 * 2. 两边都存在 → **新目录赢**，绝不拿旧数据覆盖用户已经用了一段时间的数据；
 * 3. 幂等 —— 反复调用不产生副作用；
 * 4. 任何异常路径都**不抛** —— 宁可让用户重配一次，也不能让应用起不来。
 *
 * 隔离手段：所有用例都通过 [DesktopDataDir.PROP_HOME] 把「用户目录」重定向到临时目录，
 * 保证绝不触碰真实的 `~`。
 */
class DesktopDataDirTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    /**
     * 把数据根目录重定向到一个全新的临时目录后执行 [block]。
     *
     * 覆写属性必须在调用 [DesktopDataDir] 任何成员**之前**设置好 —— 它每次访问都重新
     * 读属性，所以设置顺序其实不敏感，但保持「先设置后使用」能让用例更好读。
     */
    private fun withDataHome(block: (home: File) -> Unit) {
        val home = Files.createTempDirectory("cpplayer-home").toFile().also { tempDirs += it }
        val saved = System.getProperty(DesktopDataDir.PROP_HOME)
        System.setProperty(DesktopDataDir.PROP_HOME, home.absolutePath)
        try {
            block(home)
        } finally {
            if (saved == null) System.clearProperty(DesktopDataDir.PROP_HOME)
            else System.setProperty(DesktopDataDir.PROP_HOME, saved)
        }
    }

    /** 造一个旧目录，内容刻意包含嵌套子目录，确保迁移是整棵树搬而不是只搬一层。 */
    private fun seedLegacy(home: File): File {
        val legacy = File(home, DesktopDataDir.LEGACY_NAME)
        File(legacy, "local-media").mkdirs()
        File(legacy, "cp_player_prefs.properties").writeText("desktop_render_api=OPENGL")
        File(legacy, "local-media/index.json").writeText("""{"items":[]}""")
        return legacy
    }

    // ======================== 建目录 ========================

    @Test
    fun `root creates the directory when neither old nor new exists`() = withDataHome { home ->
        val root = DesktopDataDir.root()

        assertEquals(File(home, DesktopDataDir.CURRENT_NAME), root)
        assertTrue(root.isDirectory, "全新安装必须自动建出数据目录")
        assertFalse(File(home, DesktopDataDir.LEGACY_NAME).exists(), "不该凭空造出旧目录")
    }

    @Test
    fun `directory creates the subdirectory under the current root`() = withDataHome { home ->
        val modules = DesktopDataDir.directory("modules")

        assertEquals(File(File(home, DesktopDataDir.CURRENT_NAME), "modules"), modules)
        assertTrue(modules.isDirectory)
    }

    @Test
    fun `file returns a path without creating the file`() = withDataHome { home ->
        val file = DesktopDataDir.file("cp_player_prefs.properties")

        assertEquals(File(File(home, DesktopDataDir.CURRENT_NAME), "cp_player_prefs.properties"), file)
        assertFalse(file.exists(), "file() 只给路径，不该有副作用")
    }

    // ======================== 迁移 ========================

    @Test
    fun `legacy directory is moved to the new name on first access`() = withDataHome { home ->
        val legacy = seedLegacy(home)

        val root = DesktopDataDir.root()

        assertTrue(root.isDirectory)
        assertFalse(legacy.exists(), "迁移后旧目录应当消失（rename 语义）")
        assertEquals(
            "desktop_render_api=OPENGL",
            File(root, "cp_player_prefs.properties").readText(),
            "渲染后端设置必须原样保留 —— 丢了用户就得重新调一遍",
        )
        assertEquals(
            """{"items":[]}""",
            File(root, "local-media/index.json").readText(),
            "嵌套子目录里的索引也必须搬过来",
        )
    }

    @Test
    fun `existing new directory wins over legacy data`() = withDataHome { home ->
        val legacy = seedLegacy(home)
        // 用户已经在新目录上跑过一段时间：新数据才是最新的
        val current = File(home, DesktopDataDir.CURRENT_NAME).apply { mkdirs() }
        File(current, "cp_player_prefs.properties").writeText("desktop_render_api=ANGLE")

        DesktopDataDir.root()

        assertEquals(
            "desktop_render_api=ANGLE",
            File(current, "cp_player_prefs.properties").readText(),
            "新目录已存在时必须放弃迁移，绝不能拿旧数据覆盖",
        )
        assertTrue(legacy.isDirectory, "放弃迁移时旧目录应保持原样，便于用户手工找回")
    }

    @Test
    fun `migration records where the data came from`() = withDataHome { home ->
        seedLegacy(home)

        val root = DesktopDataDir.root()

        val marker = File(root, DesktopDataDir.MIGRATION_MARKER)
        assertTrue(marker.isFile, "迁移成功后应留下来源标记，便于事后排查")
        assertEquals(File(home, DesktopDataDir.LEGACY_NAME).absolutePath, marker.readText())
    }

    @Test
    fun `migration is idempotent`() = withDataHome { home ->
        seedLegacy(home)

        val first = DesktopDataDir.root()
        File(first, "cp_player_prefs.properties").writeText("desktop_render_api=VULKAN")
        val second = DesktopDataDir.root()
        val third = DesktopDataDir.root()

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(
            "desktop_render_api=VULKAN",
            File(third, "cp_player_prefs.properties").readText(),
            "重复访问不该把数据搬回去或覆盖掉",
        )
    }

    @Test
    fun `migration leaves no marker when there was nothing to migrate`() = withDataHome { home ->
        DesktopDataDir.root()

        assertFalse(
            File(File(home, DesktopDataDir.CURRENT_NAME), DesktopDataDir.MIGRATION_MARKER).exists(),
            "没发生迁移就不该写标记，否则日志会误导排查方向",
        )
    }

    // ======================== 异常路径：不抛 ========================

    @Test
    fun `a legacy path that is a plain file is ignored instead of throwing`() = withDataHome { home ->
        // 例如用户手工把 `.kmp-pro` 建成了文件，或某个工具留下了同名文件
        File(home, DesktopDataDir.LEGACY_NAME).writeText("not a directory")

        val root = DesktopDataDir.root()

        assertTrue(root.isDirectory, "异常旧路径不该阻止新目录建出来")
        assertFalse(File(root, DesktopDataDir.MIGRATION_MARKER).exists())
    }

    @Test
    fun `migrateIfNeeded reports false for every no-op case`() = withDataHome { home ->
        val legacy = seedLegacy(home)
        val target = File(home, DesktopDataDir.CURRENT_NAME)

        // 直接测内部函数：三种「无事可做」都必须明确返回 false
        assertTrue(DesktopDataDir.migrateIfNeeded(legacy, target), "首次迁移应返回 true")
        assertFalse(DesktopDataDir.migrateIfNeeded(legacy, target), "旧目录已消失 → 无事可做")
        assertFalse(
            DesktopDataDir.migrateIfNeeded(File(home, "does-not-exist"), File(home, "target")),
            "旧目录不存在 → 无事可做",
        )
    }

    // ======================== 接线：调用方确实落在新目录 ========================

    @Test
    fun `platform info directories all live under the current root`() = withDataHome { home ->
        val root = DesktopDataDir.root().absolutePath

        val modules = PlatformInfo.modulesDirectory(PlatformContext.EMPTY)
        val downloads = PlatformInfo.downloadsDirectory(PlatformContext.EMPTY)
        val data = PlatformInfo.dataDirectory(PlatformContext.EMPTY)

        assertTrue(modules.startsWith(root), "modules 目录应落在 $root 下，实际 $modules")
        assertTrue(downloads.startsWith(root), "downloads 目录应落在 $root 下，实际 $downloads")
        assertEquals(root, data)
        assertTrue(File(modules).isDirectory)
        assertTrue(File(downloads).isDirectory)
    }

    @Test
    fun `settings storage persists into the redirected data directory`() = withDataHome { home ->
        val storage = DesktopSettingsStorage("cp_player_prefs")
        storage.putString("desktop_render_api", "OPENGL")

        val expected = File(File(home, DesktopDataDir.CURRENT_NAME), "cp_player_prefs.properties")
        assertTrue(expected.isFile, "配置必须写进新目录，否则改名等于丢设置")
        assertTrue(expected.readText().contains("desktop_render_api=OPENGL"))

        // 重新构造一次，验证确实从磁盘读回来（而不是只在内存 Map 里）
        val reopened = DesktopSettingsStorage("cp_player_prefs")
        assertEquals("OPENGL", reopened.getString("desktop_render_api", null))
    }

    @Test
    fun `legacy settings are visible after migration`() = withDataHome { home ->
        seedLegacy(home)

        // 构造 Storage 就会触发 root()，进而触发迁移
        val storage = DesktopSettingsStorage("cp_player_prefs")

        assertEquals(
            "OPENGL",
            storage.getString("desktop_render_api", null),
            "升级用户最直观的期待：上次选的渲染后端还在",
        )
        assertFalse(
            File(home, DesktopDataDir.LEGACY_NAME).exists(),
            "读设置这一条路径就足以触发迁移，不必等用户打开设置页",
        )
    }
}
