package cp.player.core.playback

import cp.player.core.util.VirtualThreadDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 桌面端 Hi-Res 发烧音频底层事件。
 */
sealed class DesktopAudioEvent {
    data class StateChanged(val state: String, val path: String? = null) : DesktopAudioEvent()
    data class Progress(
        val positionSecs: Double,
        val durationSecs: Double,
        val bufferLevel: Double,
    ) : DesktopAudioEvent()
    data class TrackEnded(val path: String) : DesktopAudioEvent()
    data class CrossfadeStarted(val fromPath: String, val toPath: String) : DesktopAudioEvent()
    data class Error(val message: String) : DesktopAudioEvent()
    data class NextTrackReady(val path: String) : DesktopAudioEvent()
    data class FormatChanged(
        val sampleRate: Int,
        val bitrate: Int,
        val bitDepth: Int = 0,
        val channels: Int = 0,
        val codecName: String = "",
    ) : DesktopAudioEvent()
}

/**
 * 桌面端原生发烧音频引擎 JNI 桥接单例。
 *
 * 底层基于专有 Rust `rust_lib_flick_player`（Symphonia 解码 + WASAPI Exclusive 独占直通 + DSD 软解 + 64位浮点 DSP）。
 *
 * 在未编译/未附带原生动态库（.dll / .dylib / .so）时，[isAvailable] 为 false，不产生任何副作用。
 */
object DesktopHiResEngine {
    private val logger = Logger.getLogger("DesktopHiResEngine")
    private const val LIB_NAME = "rust_lib_flick_player"

    /**
     * 进度轮询间隔（ms）。
     *
     * 引擎**不推送** Progress 事件（`AudioEvent::Progress` 在 Rust 侧从不被构造，
     * `nativePollEvent` 的事件流里永远不会有 Progress）—— 进度只能由宿主按节拍拉取
     * `nativeGetProgress`。legacy 的 `FlickPlayer.startPositionPolling` 用 50 ms；
     * 100 ms 对 `SeekSettle.TOLERANCE_MS`（250 ms）的对账窗口够用，又不给 JNI 添负担。
     *
     * 漏掉这层轮询的表现（真实事故）：无总时长、进度条不动、拖动进度条无效
     * —— 时长为 0 时进度条把拖动目标 coerce 成 0，seek 永远落在开头。
     */
    private const val PROGRESS_POLL_INTERVAL_MS = 100L

    private var _isAvailable = false
    val isAvailable: Boolean get() = _isAvailable

    private var isInitialized = false

    /**
     * 专用引擎线程调度器。
     * 所有 JNI 调用通过此调度器串行执行，避免阻塞 Swing EDT / Compose 渲染出帧。
     */
    val engineDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "DesktopHiResEngine-JNI").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private val _engineState = MutableStateFlow("Idle")
    val engineState: StateFlow<String> = _engineState.asStateFlow()

    private val _engineProgress = MutableStateFlow(DesktopAudioEvent.Progress(0.0, 0.0, 0.0))
    val engineProgress: StateFlow<DesktopAudioEvent.Progress> = _engineProgress.asStateFlow()

    private val _audioEvents = MutableSharedFlow<DesktopAudioEvent>(extraBufferCapacity = 64)
    val audioEvents: SharedFlow<DesktopAudioEvent> = _audioEvents.asSharedFlow()

    private var pollJob: Job? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + VirtualThreadDispatcher.dispatcher)

    init {
        tryLoadLibrary()
    }

    private fun tryLoadLibrary() {
        // 1. 尝试从系统路径或 java.library.path 加载
        try {
            System.loadLibrary(LIB_NAME)
            _isAvailable = true
            logger.info("Loaded native audio library '$LIB_NAME' from system path.")
            return
        } catch (_: UnsatisfiedLinkError) {
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "Failed to load '$LIB_NAME' from system path", e)
        }

        // 2. 尝试从应用运行根目录（exe/jar 所在位置）、本地配置目录或工作区常见输出目录查找
        val os = System.getProperty("os.name", "").lowercase()
        val libFileName = when {
            os.contains("win") -> "$LIB_NAME.dll"
            os.contains("mac") -> "lib$LIB_NAME.dylib"
            else -> "lib$LIB_NAME.so"
        }

        val searchCandidates = linkedSetOf<File>()

        // (a) 当前正在运行的可执行程序（CPPlayer.exe）所在目录探测 (Java 9+ ProcessHandle)
        try {
            ProcessHandle.current().info().command().ifPresent { exePath ->
                val exeFile = File(exePath)
                val appRoot = exeFile.parentFile
                if (appRoot != null && appRoot.exists()) {
                    searchCandidates += File(appRoot, libFileName)
                    searchCandidates += File(appRoot, "runtime/bin/$libFileName")
                    searchCandidates += File(appRoot, "app/$libFileName")
                }
            }
        } catch (_: Throwable) {
        }

        // (b) 当前代码包（JAR）位置逆向推导安装根目录
        try {
            val codeSourceLoc = DesktopHiResEngine::class.java.protectionDomain?.codeSource?.location
            if (codeSourceLoc != null) {
                val jarFile = File(codeSourceLoc.toURI())
                val jarDir = jarFile.parentFile // 通常为 <install>/app
                if (jarDir != null && jarDir.exists()) {
                    searchCandidates += File(jarDir, libFileName)
                    val appRoot = jarDir.parentFile // 通常为 <install>
                    if (appRoot != null && appRoot.exists()) {
                        searchCandidates += File(appRoot, libFileName)
                        searchCandidates += File(appRoot, "runtime/bin/$libFileName")
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // (c) Compose 应用资源目录环境变量
        System.getProperty("compose.application.resources.dir")?.let { resDir ->
            searchCandidates += File(resDir, libFileName)
            searchCandidates += File(resDir, "../$libFileName")
        }

        // (d) 常规工作区目录（dev 模式下 app/native 是权威来源）
        val userDir = File(System.getProperty("user.dir"))
        searchCandidates += File(userDir, libFileName)
        searchCandidates += File(userDir, "native/$libFileName")
        searchCandidates += File(userDir, "../native/$libFileName")
        searchCandidates += File(userDir, "app/native/$libFileName")
        searchCandidates += File(userDir, "native/flick-player-engine/target/release/$libFileName")
        searchCandidates += File(userDir, "../native/flick-player-engine/target/release/$libFileName")

        // (e) 持久化目录放最后 —— 它可能残留旧版本 DLL（曾因排在 app/native 之前
        //     而遮蔽新 DLL，旧 DLL 缺 nativeGetProgress 等导出 ⇒ UnsatisfiedLinkError）。
        searchCandidates += File(System.getProperty("user.home"), ".cpplayer/native/$libFileName")

        for (candidate in searchCandidates) {
            if (candidate.exists()) {
                try {
                    System.load(candidate.absolutePath)
                    _isAvailable = true
                    logger.info("Loaded native audio library from: ${candidate.absolutePath}")
                    return
                } catch (e: Throwable) {
                    logger.log(Level.WARNING, "Failed loading candidate: ${candidate.absolutePath}", e)
                }
            }
        }

        _isAvailable = false
        logger.info("Desktop Hi-Res native library not present. Standard Rodio fallback active.")
    }

    fun initEngine(): Boolean {
        if (!_isAvailable) return false
        if (isInitialized) return true

        return try {
            val success = nativeInit()
            if (success) {
                isInitialized = true
                startPolling()
                startProgressPolling()
            }
            success
        } catch (e: Throwable) {
            logger.log(Level.SEVERE, "Failed to initialize Desktop Hi-Res Engine", e)
            false
        }
    }

    /**
     * 读取引擎当前进度（位置 / 时长 / 缓冲水位）。
     *
     * ⚠️ 这是宿主获取进度的**唯一**途径：Rust 引擎从不构造 `AudioEvent::Progress`，
     * `nativePollEvent` 的事件流里永远没有 Progress，必须按节拍轮询本方法
     * （与 `reference/cp-player-legacy` 的 `FlickPlayer.startPositionPolling` 同一机制）。
     *
     * 返回 null = 引擎当前没有活动音源（`get_progress` 返回 `null`）。
     */
    /** 旧 DLL 缺 `nativeGetProgress` 导出时置 true，只打一条日志后停止轮询。 */
    private var progressUnavailableLogged = false

    fun getProgress(): DesktopAudioEvent.Progress? {
        if (!isInitialized) return null
        return try {
            parseDesktopProgressJson(nativeGetProgress() ?: return null)
        } catch (e: UnsatisfiedLinkError) {
            // 旧 DLL 没有 DesktopHiResEngine 的 nativeGetProgress 导出。
            // 停止轮询避免每 100 ms 刷一条 UnsatisfiedLinkError。
            if (!progressUnavailableLogged) {
                progressUnavailableLogged = true
                logger.log(
                    Level.WARNING,
                    "nativeGetProgress not available in loaded DLL (stale build?); progress polling disabled",
                    e,
                )
            }
            progressJob?.cancel()
            null
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "nativeGetProgress failed", t)
            null
        }
    }

    /**
     * 进度轮询：把 [getProgress] 的结果按节拍转成 [DesktopAudioEvent.Progress] 事件。
     *
     * 为什么在桥接层合成事件而不是让上层直接轮询：[DesktopHiResPlatformPlayer] 的
     * 时长/位置/seek 对账（`PendingSeekTracker`）全部挂在 `audioEvents` 上，合成事件
     * 让它一行不改就恢复工作；将来引擎真开始推送 Progress 事件时两条路径自然合并
     * （同值重复提交无害）。
     */
    private fun startProgressPolling() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                try {
                    val progress = getProgress()
                    if (progress != null) {
                        _engineProgress.value = progress
                        _audioEvents.tryEmit(progress)
                    }
                } catch (_: Throwable) {
                    // 单次拉取失败不影响下一轮（引擎切换/释放瞬间的竞态）。
                }
                delay(PROGRESS_POLL_INTERVAL_MS)
            }
        }
    }

    fun play(path: String): Boolean {
        if (!isInitialized) return false
        return try {
            nativePlay(path)
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "nativePlay failed for $path", e)
            false
        }
    }

    fun pause(): Boolean {
        if (!isInitialized) return false
        return try {
            nativePause()
        } catch (e: Throwable) {
            false
        }
    }

    fun resume(): Boolean {
        if (!isInitialized) return false
        return try {
            nativeResume()
        } catch (e: Throwable) {
            false
        }
    }

    fun stop(): Boolean {
        if (!isInitialized) return false
        return try {
            nativeStop()
        } catch (e: Throwable) {
            false
        }
    }

    fun seek(positionSecs: Double): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSeek(positionSecs)
        } catch (e: Throwable) {
            false
        }
    }

    fun setVolume(volume: Float): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetVolume(volume.coerceIn(0f, 1f))
        } catch (e: Throwable) {
            false
        }
    }

    fun setEqualizer(enabled: Boolean, freqs: FloatArray, gains: FloatArray, qs: FloatArray): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetEqualizer(enabled, freqs, gains, qs)
        } catch (e: Throwable) {
            false
        }
    }

    fun setFx(
        enabled: Boolean,
        balance: Float,
        tempo: Float = 1.0f,
        damp: Float = 0.35f,
        filterHz: Float = 6800f,
        delayMs: Float = 240f,
        size: Float = 0.5f,
        mix: Float = 0.2f,
        feedback: Float = 0.35f,
        width: Float = 1.0f,
    ): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetFx(enabled, balance, tempo, damp, filterHz, delayMs, size, mix, feedback, width)
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 当前是否以硬件独占模式打开设备。
     *
     * 默认 **`false`**（共享）：独占只在用户显式开启且 VIP 有效时才成立。
     * 见 [setExclusiveMode] 与 `PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE` 的说明。
     */
    private var _isExclusiveMode: Boolean = false
    val isExclusiveMode: Boolean get() = _isExclusiveMode

    /**
     * 设置是否开启硬件独占模式 (WASAPI Exclusive / Bit-Perfect 源码输出)。
     * 若为 false，则以 WASAPI 共享模式运行，兼容系统混音器。
     *
     * 环境变量 `CP_WASAPI_EXCLUSIVE` 是 Kotlin 设置与 cpal 打开声卡之间**唯一**的桥
     * （vendored cpal 在 `build_output_stream_raw_inner` 里读它决定
     * `AUDCLNT_SHAREMODE_EXCLUSIVE/SHARED`）。两条写入通道都要走：
     * 1. **JNI（可靠）**：`audio_set_exclusive_mode` 只做 `std::env::set_var` +
     *    bit-perfect 标志，**不要求引擎已创建** —— 必须无条件调用。
     *    早期这里写成 `if (isInitialized)`：冷启动时本方法先于 `initEngine()` 执行，
     *    这条可靠通道被跳过，只剩 FFM 那条路，而那条路当时整条是坏的
     *    （见 [setProcessEnv]），环境变量从未写成 ⇒ 设置明明是共享、声卡照样独占。
     * 2. **FFM（兜底）**：为旧 DLL（无 `nativeSetExclusiveMode` 导出）准备，
     *    失败只打日志，不阻断。
     */
    fun setExclusiveMode(enabled: Boolean): Boolean {
        _isExclusiveMode = enabled
        runCatching { nativeSetExclusiveMode(enabled) }
        setProcessEnv("CP_WASAPI_EXCLUSIVE", if (enabled) "1" else "0")
        return true
    }

    /**
     * 设置 DSD 输出模式：0 = PCM Decimation (软解到 PCM), 1 = DoP, 2 = Native DSD, 3 = Auto。
     */
    fun setDsdOutputMode(mode: Int): Boolean {
        if (!isInitialized) return false
        return runCatching { nativeSetDsdOutputMode(mode) }.getOrDefault(false)
    }

    private fun setProcessEnv(key: String, value: String) {
        try {
            System.setProperty(key, value)
            val os = System.getProperty("os.name", "").lowercase()
            if (os.contains("win")) {
                val linker = java.lang.foreign.Linker.nativeLinker()
                val kernel32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", java.lang.foreign.Arena.global())
                val setEnvHandle = kernel32.find("SetEnvironmentVariableW").map {
                    linker.downcallHandle(
                        it,
                        java.lang.foreign.FunctionDescriptor.of(
                            java.lang.foreign.ValueLayout.JAVA_INT,
                            java.lang.foreign.ValueLayout.ADDRESS,
                            java.lang.foreign.ValueLayout.ADDRESS,
                        )
                    )
                }.orElse(null)
                if (setEnvHandle != null) {
                    java.lang.foreign.Arena.ofConfined().use { arena ->
                        val k = allocWideCString(arena, key)
                        val v = allocWideCString(arena, value)
                        // ⚠️ 这里**不能**用 `invokeExact`：Kotlin 会把调用点描述符编译成
                        // `(MemorySegment, MemorySegment)Object`（返回类型取自声明擦除），
                        // 而 downcall handle 的类型是 `(MemorySegment, MemorySegment)int`
                        // —— invokeExact 要求调用点描述符与 handle 类型完全一致，
                        // 返回类型差一个就抛 WrongMethodTypeException。早期版本就是这么写的，
                        // 异常被下面的 catch 静默吞掉 ⇒ `CP_WASAPI_EXCLUSIVE` 从未写成 ⇒
                        // vendored cpal 按其缺省（当时=独占）打开声卡。
                        // `invokeWithArguments` 走 asType 适配，返回值装箱，语义正确。
                        setEnvHandle.invokeWithArguments(k, v)
                    }
                }
            }
        } catch (t: Throwable) {
            logger.warning("setProcessEnv failed for $key: ${t.message}")
        }
    }

    /**
     * 手工铺 UTF-16LE 宽字符 + NUL 结尾的 C 串。
     *
     * 不用 `Arena.allocateFrom(String, Charset)`：那是 **JDK 22+** 的 API，而本项目
     * 生产运行时钉在 JBR 21（`app/build.gradle.kts` 的 `jbrVersion`），运行到那行是
     * `NoSuchMethodError`（同样会被 [setProcessEnv] 的 catch 吞掉）。JDK 21 的
     * `allocateUtf8String` 只支持 UTF-8，`SetEnvironmentVariableW` 要 UTF-16LE，
     * 所以按字节自己铺 —— `Arena.allocate(long)` 与 `asByteBuffer()` 两个版本都有。
     */
    private fun allocWideCString(
        arena: java.lang.foreign.Arena,
        s: String,
    ): java.lang.foreign.MemorySegment {
        val bytes = (s + "\u0000").toByteArray(java.nio.charset.StandardCharsets.UTF_16LE)
        val seg = arena.allocate(bytes.size.toLong())
        seg.asByteBuffer().put(bytes)
        return seg
    }

    fun stopEngine() {
        pollJob?.cancel()
        pollJob = null
        progressJob?.cancel()
        progressJob = null
        if (isInitialized) {
            try {
                nativeStop()
            } catch (_: Throwable) {
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val eventStr = nativePollEvent()
                    if (!eventStr.isNullOrEmpty() && eventStr != "null") {
                        parseAndEmitEvent(eventStr)
                    } else {
                        delay(40)
                    }
                } catch (_: Throwable) {
                    delay(500)
                }
            }
        }
    }

    private fun parseAndEmitEvent(jsonStr: String) {
        // 轻量手动解析常用 JSON，避免非必要反射
        try {
            when {
                jsonStr.contains("\"StateChanged\"") -> {
                    val state = extractJsonString(jsonStr, "state") ?: "Idle"
                    val path = extractJsonString(jsonStr, "path")
                    _engineState.value = state
                    _audioEvents.tryEmit(DesktopAudioEvent.StateChanged(state, path))
                }
                jsonStr.contains("\"Progress\"") -> {
                    val pos = extractJsonDouble(jsonStr, "position_secs") ?: 0.0
                    val dur = extractJsonDouble(jsonStr, "duration_secs") ?: 0.0
                    val buf = extractJsonDouble(jsonStr, "buffer_level") ?: 0.0
                    val p = DesktopAudioEvent.Progress(pos, dur, buf)
                    _engineProgress.value = p
                    _audioEvents.tryEmit(p)
                }
                jsonStr.contains("\"TrackEnded\"") -> {
                    val path = extractJsonString(jsonStr, "path") ?: ""
                    _audioEvents.tryEmit(DesktopAudioEvent.TrackEnded(path))
                }
                jsonStr.contains("\"Error\"") -> {
                    val msg = extractJsonString(jsonStr, "message") ?: "Unknown error"
                    _audioEvents.tryEmit(DesktopAudioEvent.Error(msg))
                }
                jsonStr.contains("\"FormatChanged\"") -> {
                    val rate = extractJsonInt(jsonStr, "sample_rate") ?: 0
                    val depth = extractJsonInt(jsonStr, "bit_depth") ?: 16
                    val channels = extractJsonInt(jsonStr, "channels") ?: 2
                    val codec = extractJsonString(jsonStr, "codec_name") ?: "PCM"
                    val bitrate = extractJsonInt(jsonStr, "bitrate") ?: 0
                    _audioEvents.tryEmit(DesktopAudioEvent.FormatChanged(rate, bitrate, depth, channels, codec))
                }
            }
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "Failed to parse desktop audio event: $jsonStr", e)
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonDouble(json: String, key: String): Double? {
        val pattern = "\"$key\"\\s*:\\s*([0-9.]+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val pattern = "\"$key\"\\s*:\\s*([0-9]+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 释放底层原生音频引擎，彻底关闭并释放 WASAPI 硬件独占流与声卡设备句柄。
     * 在切换回标准引擎、退出应用或销毁播放器时调用。
     */
    fun shutdownEngine(): Boolean {
        if (!_isAvailable) return true
        return try {
            val ok = nativeShutdown()
            isInitialized = false
            pollJob?.cancel()
            pollJob = null
            progressJob?.cancel()
            progressJob = null
            _engineState.value = "Idle"
            println("[DesktopHiResEngine] Native audio engine shutdown and hardware exclusive device released: $ok")
            ok
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "Failed to shutdown native engine", t)
            false
        }
    }

    /**
     * 当前 DLL 的 API 等级。`1` = 2026-11-10 之前发布的旧库（只会播本地路径），
     * `2` = 带 HTTP 源 / 变速 / gapless / 动态处理的版本。
     *
     * ⚠️ 不能靠「方法调一下看抛不抛异常」去逐个猜：那会把探测动作本身变成一次
     * 真实的播放/配置下发。用一个专门的、无副作用的导出做版本握手最省事。
     */
    val apiLevel: Int by lazy {
        if (!isInitialized) {
            1
        } else {
            try {
                nativeGetEngineApiLevel().coerceAtLeast(1)
            } catch (t: Throwable) {
                logger.info("Legacy FlickEngine DLL detected (no nativeGetEngineApiLevel); extended features disabled")
                1
            }
        }
    }

    /** 是否具备本次新增的全部引擎能力（宿主据此决定走直连还是落盘）。 */
    val supportsExtendedFeatures: Boolean get() = apiLevel >= 2

    // ======================== 扩展能力（FlickEngine 有实现、旧 DLL 未导出） ========================

    /**
     * 把 Kotlin 的 `Map<String,String>` 摊平成 `[k1, v1, k2, v2, ...]`。
     *
     * JNI 侧刻意不解析 JSON：请求头个数个位数，扁平数组零依赖、零歧义，
     * 也不会因为 header 值里带引号而被 JSON 解析坑到。
     */
    private fun flattenHeaders(headers: Map<String, String>): Array<String> {
        if (headers.isEmpty()) return emptyArray()
        val out = ArrayList<String>(headers.size * 2)
        for ((key, value) in headers) {
            out.add(key)
            out.add(value)
        }
        return out.toTypedArray()
    }

    /** 直连播放 HTTP(S) 流：边下边播、支持 Range seek，不再要求先整首落盘。 */
    fun playUrl(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        if (!isInitialized) return false
        return try {
            nativePlayUrl(url, flattenHeaders(headers))
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "nativePlayUrl failed for $url", t)
            false
        }
    }

    /** 把下一首（本地路径）排进引擎的无缝队列。 */
    fun queueNext(path: String): Boolean {
        if (!isInitialized) return false
        return try {
            nativeQueueNext(path)
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "nativeQueueNext failed for $path", t)
            false
        }
    }

    /** 把下一首（HTTP 流）排进引擎的无缝队列。 */
    fun queueNextUrl(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        if (!isInitialized) return false
        return try {
            nativeQueueNextUrl(url, flattenHeaders(headers))
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "nativeQueueNextUrl failed for $url", t)
            false
        }
    }

    /** 立刻切到已排队的下一首（由宿主在需要「跳过」但保持 gapless 时用）。 */
    fun skipToNext(): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSkipToNext()
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "nativeSkipToNext failed", t)
            false
        }
    }

    /**
     * 引擎侧真交叉淡化（双源重叠）。
     *
     * ⚠️ 与 `PlaybackFadeController` 的音量斜坡**互斥**：两边同时改音量会互相打架。
     * 宿主在启用本能力时不再启动 FadeRunner，见 [PlaybackFadeController]。
     */
    fun setCrossfade(enabled: Boolean, durationSecs: Float): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetCrossfade(enabled, durationSecs.coerceAtLeast(0f))
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "nativeSetCrossfade failed", t)
            false
        }
    }

    /** 0 = 等功率（默认），1 = 线性，2 = 平方根，3 = S 曲线。 */
    fun setCrossfadeCurve(curve: Int): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetCrossfadeCurve(curve.coerceIn(0, 3))
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "nativeSetCrossfadeCurve failed", t)
            false
        }
    }

    fun setPlaybackSpeed(speed: Float): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetPlaybackSpeed(speed.coerceIn(0.25f, 4.0f))
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "nativeSetPlaybackSpeed failed", t)
            false
        }
    }

    fun getPlaybackSpeed(): Float {
        if (!isInitialized) return 1f
        return try {
            nativeGetPlaybackSpeed().takeIf { it.isFinite() && it > 0f } ?: 1f
        } catch (t: Throwable) {
            1f
        }
    }

    /**
     * 响度均衡：压缩器。阈值/比率/启动/释放/补偿增益，单位与 Rust `set_compressor` 一致。
     */
    fun setCompressor(
        enabled: Boolean,
        thresholdDb: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float,
        makeupGainDb: Float,
    ): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetCompressor(enabled, thresholdDb, ratio, attackMs, releaseMs, makeupGainDb)
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "nativeSetCompressor failed", t)
            false
        }
    }

    /** 防爆音：硬限幅器。 */
    fun setLimiter(
        enabled: Boolean,
        inputGainDb: Float,
        ceilingDb: Float,
        releaseMs: Float,
    ): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetLimiter(enabled, inputGainDb, ceilingDb, releaseMs)
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "nativeSetLimiter failed", t)
            false
        }
    }

    // Native JNI Declarations（对齐 C/Rust 导出契约）
    private external fun nativeInit(): Boolean
    private external fun nativeShutdown(): Boolean
    private external fun nativePlay(path: String): Boolean
    private external fun nativePause(): Boolean
    private external fun nativeResume(): Boolean
    private external fun nativeStop(): Boolean
    private external fun nativeSeek(secs: Double): Boolean
    private external fun nativeSetVolume(vol: Float): Boolean
    private external fun nativeSetEqualizer(enabled: Boolean, freqs: FloatArray, gains: FloatArray, qs: FloatArray): Boolean
    private external fun nativeSetFx(
        enabled: Boolean, balance: Float, tempo: Float, damp: Float,
        filterHz: Float, delayMs: Float, size: Float, mix: Float,
        feedback: Float, width: Float,
    ): Boolean
    private external fun nativePollEvent(): String?
    private external fun nativeGetProgress(): String?
    private external fun nativeSetExclusiveMode(enabled: Boolean): Boolean
    private external fun nativeSetDsdOutputMode(mode: Int): Boolean

    // 扩展能力：这些符号在 2026-11-10 之前发布的 DLL 里**不存在**。
    // 上面的包装方法都带 try/catch，老 DLL 上会静默返回 false / 默认值，不会崩。
    private external fun nativeGetEngineApiLevel(): Int
    private external fun nativePlayUrl(url: String, headers: Array<String>): Boolean
    private external fun nativeQueueNext(path: String): Boolean
    private external fun nativeQueueNextUrl(url: String, headers: Array<String>): Boolean
    private external fun nativeSkipToNext(): Boolean
    private external fun nativeSetCrossfade(enabled: Boolean, durationSecs: Float): Boolean
    private external fun nativeSetCrossfadeCurve(curve: Int): Boolean
    private external fun nativeSetPlaybackSpeed(speed: Float): Boolean
    private external fun nativeGetPlaybackSpeed(): Float
    private external fun nativeSetCompressor(
        enabled: Boolean,
        thresholdDb: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float,
        makeupGainDb: Float,
    ): Boolean
    private external fun nativeSetLimiter(
        enabled: Boolean,
        inputGainDb: Float,
        ceilingDb: Float,
        releaseMs: Float,
    ): Boolean
}

/**
 * 解析 `nativeGetProgress` 返回的 JSON
 * （`{"position_secs":…,"duration_secs":…,"buffer_level":…}`）。
 *
 * `"null"` / 空串 / 无 `position_secs` 键都返回 null —— 引擎当前没有活动音源。
 *
 * `duration_secs` 在 Rust 侧是 `Option<f64>`：时长未知时序列化为 `null`，
 * 这里解析成 0.0。调用方约定「> 0 才采纳」（见 `DesktopHiResPlatformPlayer.handleProgress`），
 * 0 不会覆盖已有时长。
 */
internal fun parseDesktopProgressJson(json: String): DesktopAudioEvent.Progress? {
    val trimmed = json.trim()
    if (trimmed.isEmpty() || trimmed == "null" || trimmed == "{}") return null
    if (!trimmed.contains("\"position_secs\"")) return null
    return DesktopAudioEvent.Progress(
        positionSecs = jsonNumber(trimmed, "position_secs") ?: 0.0,
        durationSecs = jsonNumber(trimmed, "duration_secs") ?: 0.0,
        bufferLevel = jsonNumber(trimmed, "buffer_level") ?: 0.0,
    )
}

/** 提取 `"key": <number>` 的数值；缺失 / `null` / 非数值都返回 null。 */
private fun jsonNumber(json: String, key: String): Double? {
    val number = "-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
    return Regex("\"$key\"\\s*:\\s*($number)")
        .find(json)
        ?.groupValues
        ?.get(1)
        ?.toDoubleOrNull()
}
