package cp.player.core.util

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * `defaultSettingsStorage()` 的**共享实例**不变量。
 *
 * ### 这个测试在防什么
 *
 * 桌面实现是「构造时把整个 properties 读进内存 + 每次写入全量回写」。因此同一个
 * namespace 上只要存在两个实例，两者就各持一份快照，**后写者会用陈旧快照覆盖先写者**。
 *
 * 修之前 `cp_player_prefs` 上同时有三个写者：
 * 1. `AppModel.settings` —— 写的是 `val settings get() = defaultSettingsStorage()`，每次访问新建；
 * 2. `MusicBackend` 注入的那个（`Main.kt` 的 `ensureBackendInitialized`）；
 * 3. `DesktopRenderTuning.prefs` —— `by lazy { defaultSettingsStorage() }`。
 *
 * 症状是「改完主题 → 去渲染后端页动一下垂直同步 → 主题被回退」，而且**只在桌面端复现**，
 * 单看任何一个页面都找不出问题。
 *
 * ### 为什么 key 里必须带数据目录
 *
 * `DesktopDataDir.PROP_HOME` 是测试隔离点。只按 namespace 缓存会让上一个用例的实例
 * （其 `file` 指向旧临时目录）泄漏到下一个用例，表现为「写盘写到了别的用例的目录」。
 * [different data directories do not share instances] 就是钉住这一点的。
 */
class DesktopSettingsStorageSharingTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun withDataHome(block: () -> Unit) {
        val home = Files.createTempDirectory("cpplayer-prefs").toFile().also { tempDirs += it }
        val saved = System.getProperty(DesktopDataDir.PROP_HOME)
        System.setProperty(DesktopDataDir.PROP_HOME, home.absolutePath)
        try {
            block()
        } finally {
            if (saved == null) System.clearProperty(DesktopDataDir.PROP_HOME)
            else System.setProperty(DesktopDataDir.PROP_HOME, saved)
        }
    }

    @Test
    fun `same namespace and data directory share one instance`() = withDataHome {
        assertSame(
            defaultSettingsStorage("cp_player_prefs"),
            defaultSettingsStorage("cp_player_prefs"),
            "同一 namespace 必须共用实例，否则全量回写会互相覆盖",
        )
    }

    @Test
    fun `different namespaces get different instances`() = withDataHome {
        assertNotSame(
            defaultSettingsStorage("cp_player_prefs"),
            defaultSettingsStorage("cp_player_window"),
            "窗口尺寸有独立 namespace，绝不能与主设置共用一个 store",
        )
    }

    @Test
    fun `different data directories do not share instances`() {
        // 测试隔离：换一个数据目录必须拿到全新的实例，否则用例之间会互相污染。
        val first = mutableListOf<SettingsStorage>()
        withDataHome { first += defaultSettingsStorage("cp_player_prefs") }
        val second = mutableListOf<SettingsStorage>()
        withDataHome { second += defaultSettingsStorage("cp_player_prefs") }

        assertNotSame(
            first.single(),
            second.single(),
            "数据目录不同却复用了同一个实例 —— 会把设置写进上一个用例的目录",
        )
    }

    @Test
    fun `a second writer does not clobber the first writer's keys`() = withDataHome {
        // 回归本体：模拟 AppModel 与 DesktopRenderTuning 先后写同一个 namespace。
        val appModel = defaultSettingsStorage("cp_player_prefs")
        val renderTuning = defaultSettingsStorage("cp_player_prefs")

        appModel.putString("theme_mode", "DARK")
        renderTuning.putString("desktop_vsync_override", "true")

        val reopened = defaultSettingsStorage("cp_player_prefs")
        assertEquals(
            "DARK",
            reopened.getString("theme_mode", null),
            "第二个写者把第一个写者的 theme_mode 抹掉了 —— 这正是那个丢设置的 bug",
        )
        assertEquals("true", reopened.getString("desktop_vsync_override", null))
    }

    @Test
    fun `direct construction still yields an isolated snapshot`() = withDataHome {
        // 直接构造不走共享缓存，供需要独立快照的场景与测试使用。
        assertNotSame(
            DesktopSettingsStorage("cp_player_prefs"),
            DesktopSettingsStorage("cp_player_prefs"),
        )
    }
}
