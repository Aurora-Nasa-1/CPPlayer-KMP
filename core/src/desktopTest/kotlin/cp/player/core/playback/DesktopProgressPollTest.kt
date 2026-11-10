package cp.player.core.playback

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FlickEngine 进度来源回归测试：钉住「无总时长 + 进度条不动 + 拖动无效」这次真实事故。
 *
 * ### 事故链
 * Rust 引擎**从不构造** `AudioEvent::Progress`（`nativePollEvent` 的事件流里永远没有
 * Progress），而 `DesktopHiResEngine` 又从未调用 DLL 早已导出的 `nativeGetProgress` ——
 * `DesktopHiResPlatformPlayer.handleProgress` 从未跑过：时长只来自曲目元信息（本地文件
 * 通常没有）、位置永远冻在起点。更糟的是时长为 0 时进度条把拖动目标 coerce 成 0，
 * seek 永远落在开头。
 *
 * 所以这里分两层锁：① 进度 JSON 解析（null / 缺字段 / 未知时长的退化）；
 * ② 源码接线（桥接层必须轮询 `nativeGetProgress` 并合成 Progress 事件）——
 * 后者运行期量不到（测试里没有活动音源），只能钉源码。
 */
class DesktopProgressPollTest {

    private fun readSource(relative: String): String {
        var dir: File? = File("").absoluteFile
        val found = buildList {
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { add(it) }
                val underModule = File(File(dir, "core"), relative)
                if (underModule.isFile) add(underModule)
                dir = dir.parentFile
            }
        }.firstOrNull()
        check(found != null) { "找不到 $relative" }
        return found.readText()
    }

    // ---------------------------------------------------------------- 进度 JSON 解析

    @Test
    fun `正常进度 JSON 解析出位置时长与缓冲`() {
        val p = parseDesktopProgressJson(
            """{"position_secs":12.5,"duration_secs":245.3,"buffer_level":0.75}""",
        )
        assertNotNull(p)
        assertEquals(12.5, p.positionSecs)
        assertEquals(245.3, p.durationSecs)
        assertEquals(0.75, p.bufferLevel)
    }

    @Test
    fun `serde_json 的 null 空对象与空串都解析为无进度`() {
        assertNull(parseDesktopProgressJson("null"))
        assertNull(parseDesktopProgressJson(""))
        assertNull(parseDesktopProgressJson("   "))
        assertNull(parseDesktopProgressJson("{}"))
    }

    @Test
    fun `缺少 position_secs 的对象视为无进度`() {
        // 引擎可能返回别的形状；没有位置就无从谈起进度，别造出全 0 的假进度。
        assertNull(parseDesktopProgressJson("""{"duration_secs":245.3}"""))
    }

    @Test
    fun `时长未知时 duration_secs 解析为 0 而不是抛异常`() {
        // Rust 侧 duration_secs 是 Option<f64>：未知时长序列化为 null。
        // 0.0 让调用方的「> 0 才采纳」门（handleProgress）自然忽略，不覆盖已有时长。
        val p = parseDesktopProgressJson(
            """{"position_secs":3.0,"duration_secs":null,"buffer_level":0.0}""",
        )
        assertNotNull(p)
        assertEquals(3.0, p.positionSecs)
        assertEquals(0.0, p.durationSecs)
    }

    @Test
    fun `整数形式的数值按 Double 解析`() {
        // serde_json 对 f64 整数值输出 "5.0"，但对整型字段会输出 "5"；两条都要能吃。
        val p = parseDesktopProgressJson(
            """{"position_secs":5,"duration_secs":300,"buffer_level":1}""",
        )
        assertNotNull(p)
        assertEquals(5.0, p.positionSecs)
        assertEquals(300.0, p.durationSecs)
        assertEquals(1.0, p.bufferLevel)
    }

    @Test
    fun `缺 buffer_level 退化为 0`() {
        val p = parseDesktopProgressJson("""{"position_secs":1.5,"duration_secs":10.0}""")
        assertNotNull(p)
        assertEquals(0.0, p.bufferLevel)
    }

    @Test
    fun `科学计数法数值不丢`() {
        val p = parseDesktopProgressJson("""{"position_secs":1.5e-3,"duration_secs":10.0}""")
        assertNotNull(p)
        assertEquals(0.0015, p.positionSecs)
    }

    // ---------------------------------------------------------------- 源码接线

    @Test
    fun `DesktopHiResEngine 必须声明并调用 nativeGetProgress`() {
        val source = readSource(
            "src/desktopMain/kotlin/cp/player/core/playback/DesktopHiResEngine.kt",
        )
        assertTrue(
            source.contains("external fun nativeGetProgress()"),
            "DesktopHiResEngine 必须声明 nativeGetProgress（DLL 早已导出该符号）",
        )
        assertTrue(
            source.contains("nativeGetProgress()"),
            "getProgress() 必须真的调用 nativeGetProgress",
        )
    }

    @Test
    fun `进度轮询必须合成 Progress 事件喂给 audioEvents`() {
        val source = readSource(
            "src/desktopMain/kotlin/cp/player/core/playback/DesktopHiResEngine.kt",
        )
        val pollBlock = source
            .substringAfter("private fun startProgressPolling()")
            .substringBefore("\n    private fun ")
            .substringBefore("\n    fun ")
        assertTrue(pollBlock.isNotEmpty(), "找不到 startProgressPolling 实现")
        assertTrue(
            pollBlock.contains("_audioEvents.tryEmit(progress)"),
            "轮询结果必须走 audioEvents —— DesktopHiResPlatformPlayer 的时长/位置/seek 对账全挂在这条流上",
        )
        assertTrue(
            pollBlock.contains("_engineProgress.value = progress"),
            "engineProgress StateFlow 也要同步",
        )
    }

    @Test
    fun `进度轮询必须随引擎生命周期启停`() {
        val source = readSource(
            "src/desktopMain/kotlin/cp/player/core/playback/DesktopHiResEngine.kt",
        )
        assertTrue(
            Regex("isInitialized = true\\s*startPolling\\(\\)\\s*startProgressPolling\\(\\)").containsMatchIn(source),
            "initEngine 成功路径必须同时启动事件轮询与进度轮询",
        )
        assertTrue(
            Regex("progressJob\\?\\.cancel\\(\\)").findAll(source).count() >= 2,
            "stopEngine 与 shutdownEngine 都要停掉进度轮询",
        )
    }

    @Test
    fun `Android 桥接同样必须轮询进度`() {
        val source = readSource(
            "src/androidMain/kotlin/cp/player/engine/RustEngine.kt",
        )
        assertTrue(
            source.contains("private fun startProgressPolling()"),
            "RustEngine 必须有进度轮询（引擎不推送 Progress 事件）",
        )
        assertTrue(
            source.substringAfter("private fun startProgressPolling()")
                .contains("_audioEvents.tryEmit(progress)"),
            "Android 轮询结果也必须走 audioEvents",
        )
    }

    // ------------------------------------------------- DLL 搜索顺序 & 降级（旧 DLL 事故）

    @Test
    fun `DLL 搜索顺序必须让 app-native 优先于持久化目录`() {
        // 真实事故：~/.cpplayer/native/ 残留旧 DLL（缺 nativeGetProgress 等新导出），
        // 但搜索顺序排在 app/native 之前 ⇒ 旧 DLL 抢先加载 ⇒ UnsatisfiedLinkError。
        // 持久化目录只能当最后兜底，不能遮蔽工作区里的新 DLL。
        val source = readSource(
            "src/desktopMain/kotlin/cp/player/core/playback/DesktopHiResEngine.kt",
        )
        val loader = source
            .substringAfter("private fun tryLoadLibrary()")
            .substringBefore("\n    fun initEngine")
        assertTrue(loader.isNotEmpty(), "找不到 tryLoadLibrary 实现")
        val appNativeIdx = loader.indexOf("app/native/")
        val cpplayerIdx = loader.indexOf(".cpplayer/native/")
        assertTrue(appNativeIdx >= 0, "搜索候选必须包含 app/native/ 路径")
        assertTrue(cpplayerIdx >= 0, "搜索候选必须包含 .cpplayer/native/ 路径")
        assertTrue(
            cpplayerIdx > appNativeIdx,
            ".cpplayer/native/ 必须排在 app/native/ 之后 —— 否则残留旧 DLL 会遮蔽新 DLL",
        )
    }

    @Test
    fun `getProgress 必须对 UnsatisfiedLinkError 优雅降级`() {
        // 旧 DLL 没有 nativeGetProgress 导出时，每 100ms 轮询会刷 UnsatisfiedLinkError。
        // 必须单独捕获、只打一条日志、停止轮询，而不是当作普通异常反复重试。
        val source = readSource(
            "src/desktopMain/kotlin/cp/player/core/playback/DesktopHiResEngine.kt",
        )
        val getProgressBlock = source
            .substringAfter("fun getProgress(): DesktopAudioEvent.Progress?")
            .substringBefore("\n    /**")
            .substringBefore("\n    private fun startProgressPolling")
        assertTrue(getProgressBlock.isNotEmpty(), "找不到 getProgress 实现")
        assertTrue(
            getProgressBlock.contains("UnsatisfiedLinkError"),
            "getProgress 必须单独捕获 UnsatisfiedLinkError（旧 DLL 缺导出）",
        )
        assertTrue(
            getProgressBlock.contains("progressJob?.cancel()"),
            "UnsatisfiedLinkError 时必须停止进度轮询，不能每 100ms 重试刷日志",
        )
    }

    // ------------------------------------------------- Ended 状态保护（播完不切歌事故）

    @Test
    fun `DesktopHiResPlatformPlayer 的 handleStateChanged 必须保护 Ended 不被 Idle 吞掉`() {
        // 引擎播完（无 gapless 队列）时先发 TrackEnded 紧跟 StateChanged(Stopped)，
        // StateFlow 合并快速赋值 ⇒ Ended 被 Idle 吞掉 ⇒ onTrackEnded() 不触发 ⇒ 不切歌。
        // 与 PlatformPlayer.android.kt 的 STATE_ENDED 保护同构。
        val source = readSource(
            "src/desktopMain/kotlin/cp/player/core/playback/DesktopHiResPlatformPlayer.kt",
        )
        val block = source
            .substringAfter("private fun handleStateChanged")
            .substringBefore("\n    private fun ")
        assertTrue(block.isNotEmpty(), "找不到 handleStateChanged 实现")
        assertTrue(
            block.contains("PlatformPlaybackState.Ended"),
            "handleStateChanged 必须检查 Ended 状态",
        )
        assertTrue(
            Regex(
                "Ended\\s*&&[\\s\\S]*?(Idle|Paused)",
            ).containsMatchIn(block),
            "Ended 状态不能被 Idle/Paused 覆写（否则播完不切下一首）",
        )
    }

    @Test
    fun `Android FlickPlatformPlayer 同样必须保护 Ended 不被 Idle 吞掉`() {
        val source = readSource(
            "src/androidMain/kotlin/cp/player/core/playback/flick/FlickPlatformPlayer.kt",
        )
        val block = source
            .substringAfter("private fun handleStateChanged")
            .substringBefore("\n    private fun ")
        assertTrue(block.isNotEmpty(), "找不到 handleStateChanged 实现")
        assertTrue(
            block.contains("PlatformPlaybackState.Ended"),
            "FlickPlatformPlayer.handleStateChanged 必须检查 Ended 状态",
        )
        assertTrue(
            Regex(
                "Ended\\s*&&[\\s\\S]*?(Idle|Paused)",
            ).containsMatchIn(block),
            "Ended 状态不能被 Idle/Paused 覆写（否则播完不切下一首）",
        )
    }
}
