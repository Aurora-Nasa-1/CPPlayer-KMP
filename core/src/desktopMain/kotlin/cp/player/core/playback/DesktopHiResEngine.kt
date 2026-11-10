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

        // (d) 常规工作区与持久化目录
        searchCandidates += File(System.getProperty("user.home"), ".cpplayer/native/$libFileName")
        val userDir = File(System.getProperty("user.dir"))
        searchCandidates += File(userDir, libFileName)
        searchCandidates += File(userDir, "native/$libFileName")
        searchCandidates += File(userDir, "../native/$libFileName")
        searchCandidates += File(userDir, "app/native/$libFileName")
        searchCandidates += File(userDir, "native/flick-player-engine/target/release/$libFileName")
        searchCandidates += File(userDir, "../native/flick-player-engine/target/release/$libFileName")

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
            }
            success
        } catch (e: Throwable) {
            logger.log(Level.SEVERE, "Failed to initialize Desktop Hi-Res Engine", e)
            false
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
     * 进程环境变量与 JNI 两侧都要写：Rust 引擎在**创建引擎之前**就会读
     * `CP_WASAPI_EXCLUSIVE` 决定输出签名，只设 JNI 那一半来不及。
     */
    fun setExclusiveMode(enabled: Boolean): Boolean {
        _isExclusiveMode = enabled
        setProcessEnv("CP_WASAPI_EXCLUSIVE", if (enabled) "1" else "0")
        if (isInitialized) {
            runCatching { nativeSetExclusiveMode(enabled) }
        }
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
                        val k = arena.allocateFrom(key, java.nio.charset.StandardCharsets.UTF_16LE)
                        val v = arena.allocateFrom(value, java.nio.charset.StandardCharsets.UTF_16LE)
                        setEnvHandle.invokeExact(k, v)
                    }
                }
            }
        } catch (t: Throwable) {
            logger.fine("setProcessEnv failed for $key: ${t.message}")
        }
    }

    fun stopEngine() {
        pollJob?.cancel()
        pollJob = null
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
