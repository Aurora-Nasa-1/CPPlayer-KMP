package cp.player.app.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 让下面的断言读起来短一点；两者都是 [DesktopRenderTuning] 里的嵌套枚举。 */
private typealias Backend = DesktopRenderTuning.Backend
private typealias BackendSource = DesktopRenderTuning.BackendSource

/**
 * [DesktopRenderTuning] 的取值优先级与安全模式测试。
 *
 * 这段逻辑的价值全在优先级顺序上：它同时是「设置页持久化」和「启动即崩时的自救通道」，
 * 顺序写反就会让自救通道失效——而那种失败只会在用户已经进不去设置页时暴露，手工测不到。
 * 所以用测试把顺序钉住。
 *
 * 注意：这些用例**只读写 JVM 系统属性与临时目录**，不碰
 * `~/.cpplayer/cp_player_prefs.properties`，也不碰真实的探测文件。
 * 做法是每个用例都显式设置 `cp.player.*` 覆盖项，让解析在 `storedBackend()` 之前短路；
 * 唯一走持久化分支的用例只做「不抛异常 + 与实际存储值一致」的性质断言，不写入任何东西。
 */
class DesktopRenderTuningTest {

    private companion object {
        const val PROP_API = "skiko.renderApi"
        const val PROP_VSYNC = "skiko.vsync.enabled"
        const val OVERRIDE_API = "cp.player.renderApi"
        const val OVERRIDE_VSYNC = "cp.player.vsync"

        /** 探测文件路径的覆写点，见 [DesktopRenderTuning]。 */
        const val OVERRIDE_PROBE = "cp.player.probeFile"
    }

    /**
     * 清空全部相关属性后执行 [block]，结束后原样恢复。
     *
     * 同时把安全模式的探测文件重定向到临时目录。否则
     * [DesktopRenderTuning.applyBeforeSkikoInit] 可能往真实 `~/.cpplayer/` 写探测文件，
     * 而测试里永远等不到出帧去删它 → 用户下次真正启动时会被误判成「后端不可用」、
     * 设置被悄悄回退。测试绝不能有这种副作用。
     */
    private fun withCleanProperties(block: () -> Unit) {
        val keys = listOf(PROP_API, PROP_VSYNC, OVERRIDE_API, OVERRIDE_VSYNC, OVERRIDE_PROBE)
        val saved = keys.associateWith { System.getProperty(it) }
        keys.forEach(System::clearProperty)
        val probeDir = Files.createTempDirectory("cpplayer-probe").toFile()
        System.setProperty(OVERRIDE_PROBE, File(probeDir, "render_tuning_probe").path)
        try {
            block()
        } finally {
            saved.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
            probeDir.deleteRecursively()
        }
    }

    // ======================== Backend.fromStorage ========================

    @Test
    fun `unknown or missing stored value falls back to AUTO`() {
        assertEquals(DesktopRenderTuning.Backend.AUTO, DesktopRenderTuning.Backend.fromStorage(null))
        assertEquals(DesktopRenderTuning.Backend.AUTO, DesktopRenderTuning.Backend.fromStorage(""))
        assertEquals(DesktopRenderTuning.Backend.AUTO, DesktopRenderTuning.Backend.fromStorage("GARBAGE"))
        // 枚举改名后残留的旧值也必须安全回落，而不是崩在设置页上。
        assertEquals(DesktopRenderTuning.Backend.AUTO, DesktopRenderTuning.Backend.fromStorage("DirectX"))
    }

    @Test
    fun `known stored value round trips`() {
        DesktopRenderTuning.Backend.entries.forEach { backend ->
            assertEquals(backend, DesktopRenderTuning.Backend.fromStorage(backend.name))
        }
    }

    @Test
    fun `AUTO is the only backend with an empty skiko value`() {
        // 空值 == 「不干预」，一旦被写进 skiko.renderApi 就等于强制了一个非法后端。
        assertEquals("", DesktopRenderTuning.Backend.AUTO.value)
        DesktopRenderTuning.Backend.entries
            .filter { it != DesktopRenderTuning.Backend.AUTO }
            .forEach { assertTrue(it.value.isNotBlank(), "${it.name} 的 skiko 取值不能为空") }
    }

    // ======================== 启动参数覆盖 ========================

    @Test
    fun `launch argument is applied to skiko property`() {
        withCleanProperties {
            System.setProperty(OVERRIDE_API, "OPENGL")
            DesktopRenderTuning.applyBeforeSkikoInit()
            assertEquals("OPENGL", System.getProperty(PROP_API))
        }
    }

    @Test
    fun `launch argument is case insensitive`() {
        withCleanProperties {
            System.setProperty(OVERRIDE_API, "opengl")
            DesktopRenderTuning.applyBeforeSkikoInit()
            assertEquals("OPENGL", System.getProperty(PROP_API))
        }
    }

    @Test
    fun `AUTO launch argument leaves skiko property untouched`() {
        withCleanProperties {
            System.setProperty(OVERRIDE_API, "AUTO")
            DesktopRenderTuning.applyBeforeSkikoInit()
            assertNull(System.getProperty(PROP_API), "AUTO 不应写入 skiko.renderApi")
        }
    }

    /**
     * 自救通道的核心保证：`-Dcp.player.renderApi=AUTO` 必须能压过设置页里选错的持久化值。
     * 这里通过「外部 skiko.renderApi 已存在时本模块不得覆盖」间接验证同一优先级链。
     */
    @Test
    fun `externally specified skiko api wins over launch argument`() {
        withCleanProperties {
            System.setProperty(PROP_API, "ANGLE")
            System.setProperty(OVERRIDE_API, "OPENGL")
            DesktopRenderTuning.applyBeforeSkikoInit()
            assertEquals("ANGLE", System.getProperty(PROP_API), "外部显式指定的 skiko.renderApi 不应被覆盖")
        }
    }

    @Test
    fun `unrecognised launch argument degrades to AUTO instead of writing garbage`() {
        withCleanProperties {
            System.setProperty(OVERRIDE_API, "NOT_A_REAL_BACKEND")
            DesktopRenderTuning.applyBeforeSkikoInit()
            assertNull(System.getProperty(PROP_API), "无法识别的取值应回落到 AUTO，而不是原样写进 skiko.renderApi")
        }
    }

    // ======================== 垂直同步 ========================

    @Test
    fun `vsync override is applied`() {
        withCleanProperties {
            System.setProperty(OVERRIDE_VSYNC, "false")
            DesktopRenderTuning.applyBeforeSkikoInit()
            assertEquals("false", System.getProperty(PROP_VSYNC))
        }
    }

    @Test
    fun `externally specified vsync wins over launch argument`() {
        withCleanProperties {
            System.setProperty(PROP_VSYNC, "true")
            System.setProperty(OVERRIDE_VSYNC, "false")
            DesktopRenderTuning.applyBeforeSkikoInit()
            assertEquals("true", System.getProperty(PROP_VSYNC))
        }
    }

    @Test
    fun `non boolean vsync override is ignored`() {
        withCleanProperties {
            System.setProperty(OVERRIDE_VSYNC, "maybe")
            DesktopRenderTuning.applyBeforeSkikoInit()
            assertNull(System.getProperty(PROP_VSYNC), "非布尔值不应被写进 skiko.vsync.enabled")
        }
    }

    // ======================== 展示串 ========================

    @Test
    fun `requested summary reports auto when unset`() {
        withCleanProperties {
            assertTrue(
                DesktopRenderTuning.requestedApiSummary().contains("自动"),
                "未设置时应说明是「自动」",
            )
        }
    }

    @Test
    fun `requested summary names a recognised backend`() {
        withCleanProperties {
            System.setProperty(PROP_API, "OPENGL")
            val summary = DesktopRenderTuning.requestedApiSummary()
            assertTrue(summary.contains("OpenGL"), "应给出可读名称，实际：$summary")
        }
    }

    /**
     * 外部用遗留别名（`SOFTWARE` / `DIRECT_SOFTWARE`）指定时，绝不能显示成「自动」——
     * 否则对照实验会得出相反的结论。识别不了就如实显示原始值。
     */
    @Test
    fun `requested summary surfaces unrecognised raw value instead of claiming auto`() {
        withCleanProperties {
            System.setProperty(PROP_API, "SOFTWARE")
            val summary = DesktopRenderTuning.requestedApiSummary()
            assertTrue(summary.contains("SOFTWARE"), "必须回显原始取值，实际：$summary")
            assertTrue(summary.contains("未识别"), "应标注为未识别，实际：$summary")
        }
    }

    // ======================== 持久化回落（只读） ========================

    @Test
    fun `no override falls back to persisted setting and never throws`() {
        withCleanProperties {
            val stored = DesktopRenderTuning.storedBackend()
            DesktopRenderTuning.applyBeforeSkikoInit()
            val applied = System.getProperty(PROP_API)
            if (stored == DesktopRenderTuning.Backend.AUTO) {
                assertNull(applied, "未持久化任何后端时不应写入 skiko.renderApi")
            } else {
                assertEquals(stored.value, applied)
            }
        }
    }

    // ======================== 安全模式：探测文件判定 ========================

    @Test
    fun `no probe means the previous launch was healthy`() {
        assertNull(DesktopRenderTuning.abandonedBackend(null), "没有探测文件就不该回退")
    }

    @Test
    fun `blank or AUTO probe needs no revert`() {
        assertNull(DesktopRenderTuning.abandonedBackend(""))
        assertNull(DesktopRenderTuning.abandonedBackend("   "))
        assertNull(DesktopRenderTuning.abandonedBackend(DesktopRenderTuning.Backend.AUTO.name))
    }

    @Test
    fun `probe naming a real backend triggers a revert`() {
        assertEquals(
            DesktopRenderTuning.Backend.OPENGL,
            DesktopRenderTuning.abandonedBackend("OPENGL"),
            "探测文件活到下次启动就说明该后端没能出帧，必须回退",
        )
    }

    @Test
    fun `probe parsing tolerates case and surrounding whitespace`() {
        // 探测文件可能被带上换行；解析若不稳健，安全网会静默失效——这是最坏的一种 bug。
        assertEquals(DesktopRenderTuning.Backend.OPENGL, DesktopRenderTuning.abandonedBackend("opengl\n"))
        assertEquals(
            DesktopRenderTuning.Backend.SOFTWARE_COMPAT,
            DesktopRenderTuning.abandonedBackend(" software_compat "),
        )
    }

    @Test
    fun `unreadable probe content does not revert`() {
        // 内容损坏时宁可不动用户设置：回退是「确信起不来」才做的事，不能凭猜测。
        assertNull(DesktopRenderTuning.abandonedBackend("GARBAGE"))
        // SOFTWARE 是 Skiko 的遗留别名，不是本枚举取值，因此不构成回退理由。
        assertNull(DesktopRenderTuning.abandonedBackend("SOFTWARE"))
    }

    @Test
    fun `probe file round trips and clears`() {
        val dir = Files.createTempDirectory("cpplayer-probe-io").toFile()
        try {
            val probe = File(dir, "render_tuning_probe")
            assertNull(DesktopRenderTuning.readProbeFrom(probe), "文件不存在应读成 null")

            DesktopRenderTuning.writeProbeTo(probe, DesktopRenderTuning.Backend.OPENGL)
            assertEquals("OPENGL", DesktopRenderTuning.readProbeFrom(probe))
            assertEquals(
                DesktopRenderTuning.Backend.OPENGL,
                DesktopRenderTuning.abandonedBackend(DesktopRenderTuning.readProbeFrom(probe)),
                "写进去再读出来必须能判定为「需回退」，否则安全网等于没接上",
            )

            probe.delete()
            assertNull(DesktopRenderTuning.readProbeFrom(probe), "删除后应读成 null（= 启动健康）")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writing a probe creates missing parent directories`() {
        // 首次运行时 ~/.cpplayer 可能还不存在，此时写探测文件不能失败——否则安全网在
        // 最需要它的新装机上恰好失效。
        val dir = Files.createTempDirectory("cpplayer-probe-mkdir").toFile()
        try {
            val probe = File(File(dir, "nested"), "render_tuning_probe")
            DesktopRenderTuning.writeProbeTo(probe, DesktopRenderTuning.Backend.ANGLE)
            assertEquals("ANGLE", DesktopRenderTuning.readProbeFrom(probe))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ======================== 安全模式：该不该立字据 ========================

    @Test
    fun `only a settings page choice arms the probe`() {
        assertTrue(
            DesktopRenderTuning.shouldArmProbe(BackendSource.SETTINGS, Backend.OPENGL),
            "设置页选了 OpenGL → 该记账",
        )
        assertFalse(
            DesktopRenderTuning.shouldArmProbe(BackendSource.SETTINGS, Backend.AUTO),
            "「自动」本身没什么可失败的，记账只会白担风险",
        )
        assertFalse(
            DesktopRenderTuning.shouldArmProbe(BackendSource.OVERRIDE, Backend.OPENGL),
            "启动参数每次压过设置页，回退持久化值也救不了它，记账只会误报",
        )
        assertFalse(
            DesktopRenderTuning.shouldArmProbe(BackendSource.EXTERNAL, Backend.OPENGL),
            "外部指定 skiko.renderApi 同理",
        )
        assertFalse(DesktopRenderTuning.shouldArmProbe(BackendSource.DEFAULT, Backend.AUTO))
        assertFalse(
            DesktopRenderTuning.shouldArmProbe(null, Backend.OPENGL),
            "还没跑过 applyBeforeSkikoInit 就不该记账",
        )
    }

    @Test
    fun `launch argument does not arm the startup probe`() {
        withCleanProperties {
            System.setProperty(OVERRIDE_API, "OPENGL")
            DesktopRenderTuning.applyBeforeSkikoInit()
            DesktopRenderTuning.beginStartupProbe()
            assertNull(
                DesktopRenderTuning.readProbeFrom(File(System.getProperty(OVERRIDE_PROBE)!!)),
                "启动参数指定的后端不该被立字据——它每次都压过设置页，回退持久化值也救不了它",
            )
        }
    }

    // ======================== 「重启后生效」提示 ========================

    @Test
    fun `restart is not pending when the running value matches the stored choice`() {
        assertFalse(
            DesktopRenderTuning.restartPending(
                runningApi = "OPENGL",
                stored = DesktopRenderTuning.Backend.OPENGL,
                externalOverride = false,
            ),
        )
        assertFalse(
            DesktopRenderTuning.restartPending(
                runningApi = null,
                stored = DesktopRenderTuning.Backend.AUTO,
                externalOverride = false,
            ),
            "未设置属性 + 持久化为「自动」= 已生效",
        )
    }

    @Test
    fun `restart is pending when the stored choice differs from the running value`() {
        assertTrue(
            DesktopRenderTuning.restartPending(
                runningApi = null,
                stored = DesktopRenderTuning.Backend.OPENGL,
                externalOverride = false,
            ),
            "刚选 OpenGL 但本次运行未设置属性 → 需要重启",
        )
        assertTrue(
            DesktopRenderTuning.restartPending(
                runningApi = "OPENGL",
                stored = DesktopRenderTuning.Backend.AUTO,
                externalOverride = false,
            ),
            "刚改回「自动」但本次仍跑在 OpenGL → 需要重启",
        )
    }

    @Test
    fun `external override suppresses the restart hint`() {
        // 外部启动参数优先于设置页，此时改设置页根本不会生效，
        // 再提示「重启后生效」就是误导用户去重启一个不会有变化的设置。
        assertFalse(
            DesktopRenderTuning.restartPending(
                runningApi = "ANGLE",
                stored = DesktopRenderTuning.Backend.OPENGL,
                externalOverride = true,
            ),
        )
    }
}
