package cp.player.core.playback

import cp.player.core.license.VipLicenseManager
import cp.player.core.util.SettingsStorage
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopFlickEngineTest {

    private class FakeStorage(private val map: MutableMap<String, String> = mutableMapOf()) : SettingsStorage {
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value != null) map[key] = value else map.remove(key)
        }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }

    private lateinit var storage: FakeStorage
    private val isWindowsHost: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @BeforeTest
    fun setUp() {
        storage = FakeStorage()
        VipLicenseManager.deactivate()
    }

    @AfterTest
    fun tearDown() {
        VipLicenseManager.deactivate()
    }

    @Test
    fun `DesktopEngineManager detects flick player engine availability`() {
        assumeTrue("FlickEngine DLL packaging check is Windows-only", isWindowsHost)
        // DesktopHiResEngine should detect DLL in app/native or native
        val available = DesktopEngineManager.isHiResEngineSupported
        println("[Test] DesktopHiResEngine isAvailable: $available")
        assertTrue(available, "rust_lib_flick_player DLL should be present and detected on desktop")
    }

    @Test
    fun `DesktopPlayerEngineType defaults to FLICK_STANDARD when not VIP`() {
        storage.putString(PlaybackSessionSettings.KEY_AUDIO_ENGINE, PlaybackSessionSettings.ENGINE_STANDARD)
        val player = DesktopEngineManager.createPlayer(storage = storage, isVipUser = false)
        assertNotNull(player)
        // In standard mode, supportsExclusiveAudio should be false
        assertFalse(player.supportsExclusiveAudio)
        player.release()
    }

    @Test
    fun `exclusive is never implied by the engine key alone`() {
        // 老用户的历史键位（选过「发烧内核」）：独占开关没写过 ⇒ 必须仍是共享。
        storage.putString(PlaybackSessionSettings.KEY_AUDIO_ENGINE, PlaybackSessionSettings.ENGINE_FLICK_HIRES)
        val player = DesktopEngineManager.createPlayer(storage = storage, isVipUser = true)
        assertNotNull(player)
        assertFalse(
            player.supportsExclusiveAudio,
            "选了 FlickEngine 不等于同意独占声卡 —— 独占必须由 KEY_WASAPI_EXCLUSIVE 显式开启",
        )
        player.release()
    }

    @Test
    fun `VIP with explicit switch enables exclusive`() {
        assumeTrue("WASAPI exclusive mode requires the Windows native FlickEngine", isWindowsHost)
        storage.putString(PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE, "true")
        val player = DesktopEngineManager.createPlayer(storage = storage, isVipUser = true)
        assertNotNull(player)
        assertTrue(player.supportsExclusiveAudio)
        player.release()
    }

    @Test
    fun `non-VIP cannot get exclusive even with the switch on`() {
        storage.putString(PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE, "true")
        val player = DesktopEngineManager.createPlayer(storage = storage, isVipUser = false)
        assertNotNull(player)
        assertFalse(player.supportsExclusiveAudio, "卡密未激活时开关必须无效（独占是 VIP 特权）")
        player.release()
    }

    @Test
    fun `wasapi exclusive preference defaults to off`() {
        assertFalse(
            PlaybackSessionSettings.wasapiExclusive(storage),
            "缺失键时必须回落为「关」—— 这是「禁止默认独占」的最后一道闸",
        )
        storage.putString(PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE, "true")
        assertTrue(PlaybackSessionSettings.wasapiExclusive(storage))
        storage.putString(PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE, "not-a-bool")
        assertFalse(PlaybackSessionSettings.wasapiExclusive(storage))
    }

    @Test
    fun `DesktopHiResEngine setExclusiveMode sets process env and toggles mode`() {
        val ok1 = DesktopHiResEngine.setExclusiveMode(true)
        assertTrue(ok1)
        assertEquals(true, DesktopHiResEngine.isExclusiveMode)

        val ok2 = DesktopHiResEngine.setExclusiveMode(false)
        assertTrue(ok2)
        assertEquals(false, DesktopHiResEngine.isExclusiveMode)
    }

    // ---------------------------------------------------------------- 独占缺省回归
    //
    // 真实事故：设置页明明是「共享」，冷启动照样
    // `SUCCESS: Initialized in EXCLUSIVE (Bit-Perfect) hardware mode!`（其它应用全静音）。
    // 三层各有各的「默认值」，漏一处就回到独占 —— 这里钉源码：
    // ① setExclusiveMode 必须无条件把开关下发到 Rust（`std::env::set_var` 是可靠通道）；
    // ② setProcessEnv 的 FFM 兜底不许用 `invokeExact`（调用点返回类型 Object ≠ handle 的 int
    //    ⇒ WrongMethodTypeException 被静默吞掉，环境变量从未写成）；
    // ③ vendored cpal（真正选 AUDCLNT_SHAREMODE 的地方）缺省必须是 shared。

    private fun readSource(relative: String): String {
        var dir: java.io.File? = java.io.File("").absoluteFile
        val found = buildList {
            while (dir != null) {
                java.io.File(dir, relative).takeIf { it.isFile }?.let { add(it) }
                val underModule = java.io.File(java.io.File(dir, "core"), relative)
                if (underModule.isFile) add(underModule)
                dir = dir.parentFile
            }
        }.firstOrNull()
        check(found != null) { "找不到 $relative" }
        return found.readText()
    }

    private fun functionBody(source: String, signature: String): String =
        source.substringAfter(signature).substringBefore("\n    }")

    @Test
    fun `setExclusiveMode 必须无条件下发 JNI 不许被 isInitialized 挡住`() {
        val src = readSource("src/desktopMain/kotlin/cp/player/core/playback/DesktopHiResEngine.kt")
        val body = functionBody(src, "fun setExclusiveMode(enabled: Boolean): Boolean {")
        assertTrue(
            body.contains("nativeSetExclusiveMode(enabled)"),
            "setExclusiveMode 必须调用 nativeSetExclusiveMode —— 它才是 Rust 侧可靠的 env 写入通道",
        )
        assertFalse(
            body.contains("isInitialized"),
            "nativeSetExclusiveMode 不许被 isInitialized 门控：" +
                "冷启动时 setExclusiveMode 先于 initEngine() 执行，被挡掉后 CP_WASAPI_EXCLUSIVE 永远没人写",
        )
    }

    @Test
    fun `setProcessEnv 不许用 invokeExact 且宽串分配不得依赖 JDK 22 的 allocateFrom`() {
        val src = readSource("src/desktopMain/kotlin/cp/player/core/playback/DesktopHiResEngine.kt")
        val body = functionBody(src, "private fun setProcessEnv(key: String, value: String) {")
        assertFalse(
            body.contains("invokeExact("),
            "invokeExact 的调用点描述符会被 Kotlin 编译成 (MemorySegment,MemorySegment)Object，" +
                "与 downcall handle 的 (MemorySegment,MemorySegment)int 不一致 ⇒ WrongMethodTypeException " +
                "被 catch 静默吞掉，环境变量从未写成。用 invokeWithArguments（asType 适配）",
        )
        assertTrue(body.contains("invokeWithArguments"), "FFM 兜底通道必须真的调用 SetEnvironmentVariableW")
        assertTrue(body.contains("allocWideCString"), "宽字符分配走 allocWideCString（JDK 21 可用）")
        val allocBody = functionBody(src, "private fun allocWideCString(")
        assertFalse(
            allocBody.contains("allocateFrom("),
            "Arena.allocateFrom(String, Charset) 是 JDK 22+ API，生产运行时 JBR 21 上是 NoSuchMethodError",
        )
    }

    @Test
    fun `vendored cpal 的 CP_WASAPI_EXCLUSIVE 缺省必须是共享`() {
        assumeTrue("vendored WASAPI source is only available in the Windows native-engine workspace", isWindowsHost)
        val src = readSource("native/flick-player-engine/vendor/cpal/src/host/wasapi/device.rs")
        val block = src.substringAfter("CP_WASAPI_EXCLUSIVE").substringBefore(";")
        assertTrue(
            block.contains("unwrap_or(false)"),
            "cpal 是真正选 AUDCLNT_SHAREMODE 的地方：环境变量缺失时必须回落 shared，" +
                "与 engine.rs::desired_output_signature 及 DEFAULT_WASAPI_EXCLUSIVE 一致（缺省=独占曾是事故根因）",
        )
    }

    @Test
    fun `DesktopHiResPlatformPlayer reports correct audio effect capabilities`() {
        val player = DesktopHiResPlatformPlayer(isExclusive = false)
        val caps = player.audioEffectCapabilities
        assertTrue(caps.supportsEqualizer)
        assertTrue(caps.supportsBalance)
        assertTrue(caps.supportsVirtualizer)
        // leveling 跟随引擎能力位（apiLevel ≥ 2 的 DLL 才有 compressor/limiter 导出），
        // 不硬编码 true/false —— 测试环境可能拿到新旧任意一版 DLL。
        assertEquals(DesktopHiResEngine.supportsExtendedFeatures, caps.supportsLeveling)
        player.release()
    }
}
