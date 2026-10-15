package cp.player.core.playback

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
}
