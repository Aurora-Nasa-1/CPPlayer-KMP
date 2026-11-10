package cp.player.engine

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Rust 底层事件通知。
 */
sealed class AudioEvent {
    data class StateChanged(val state: String, val path: String? = null) : AudioEvent()
    data class Progress(
        val positionSecs: Double,
        val durationSecs: Double,
        val bufferLevel: Double,
    ) : AudioEvent()
    data class TrackEnded(val path: String) : AudioEvent()
    data class CrossfadeStarted(val fromPath: String, val toPath: String) : AudioEvent()
    data class Error(val message: String) : AudioEvent()
    data class NextTrackReady(val path: String) : AudioEvent()
    data class FormatChanged(
        val sampleRate: Int,
        val bitrate: Int,
        val bitDepth: Int = 0,
        val channels: Int = 0,
        val codecName: String = "",
    ) : AudioEvent()
}

/**
 * FlickPlayer 原生 Rust 引擎 JNI 桥接单例。
 *
 * 对应底层原生库 `rust_lib_flick_player`（Symphonia 解码 + Oboe / CPAL 低延迟输出 + DSD + PEQ）。
 * JNI 符号严格保持 `Java_cp_player_engine_RustEngine_*` 契约，与底层 .so / C 导出兼容。
 *
 * 在 native 库未打包（开发调试或普通包未带 so）时，[isAvailable] 为 false 并提供安全的无操作保护。
 */
object RustEngine {
    private const val TAG = "RustEngine"
    private const val LIB_NAME = "rust_lib_flick_player"

    /**
     * 进度轮询间隔（ms）。
     *
     * 引擎**不推送** Progress 事件（`AudioEvent::Progress` 在 Rust 侧从不被构造），
     * 进度只能由宿主按节拍拉取 [getProgress] —— 漏掉这层轮询的表现是
     * 「无总时长 + 进度条不动 + 拖动进度条无效」。与桌面
     * `DesktopHiResEngine.startProgressPolling` 同一机制、同一节拍。
     */
    private const val PROGRESS_POLL_INTERVAL_MS = 100L

    private var _isAvailable = false
    val isAvailable: Boolean get() = _isAvailable

    private var isInitialized = false

    /**
     * 专用引擎线程调度器。
     * 所有 JNI 调用通过此调度器串行执行，避免主线程阻塞与死锁。
     */
    val engineDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "RustEngine-JNI").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private val _engineState = MutableStateFlow("Idle")
    val engineState: StateFlow<String> = _engineState.asStateFlow()

    private val _engineProgress = MutableStateFlow(AudioEvent.Progress(0.0, 0.0, 0.0))
    val engineProgress: StateFlow<AudioEvent.Progress> = _engineProgress.asStateFlow()

    private val _audioEvents = MutableSharedFlow<AudioEvent>(extraBufferCapacity = 64)
    val audioEvents: SharedFlow<AudioEvent> = _audioEvents.asSharedFlow()

    private var pollJob: Job? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        try {
            System.loadLibrary(LIB_NAME)
            _isAvailable = true
            Log.i(TAG, "Rust audio library '$LIB_NAME' loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            _isAvailable = false
            Log.w(TAG, "Rust audio library '$LIB_NAME' not found in apk: ${e.message}. Fallback mode active.")
        } catch (e: Throwable) {
            _isAvailable = false
            Log.e(TAG, "Failed to load rust audio library: ${e.message}", e)
        }
    }

    fun initEngine(context: Context): Boolean {
        if (!_isAvailable) return false
        if (isInitialized) return true

        return try {
            val contextSuccess = nativeInitRustAndroidContext(context)
            Log.i(TAG, "nativeInitRustAndroidContext: $contextSuccess")
            val success = nativeInit()
            Log.i(TAG, "nativeInit: $success")
            if (success) {
                isInitialized = true
                startPolling()
                startProgressPolling()
            }
            success
        } catch (e: Throwable) {
            Log.e(TAG, "Error during initEngine", e)
            false
        }
    }

    fun play(path: String): Boolean {
        if (!isInitialized) return false
        return try {
            nativePlay(path)
        } catch (e: Throwable) {
            Log.e(TAG, "nativePlay failed for $path", e)
            false
        }
    }

    fun playUrl(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        if (!isInitialized) return false
        return try {
            nativePlayUrl(url, flattenHeaders(headers))
        } catch (e: Throwable) {
            Log.e(TAG, "nativePlayUrl failed", e)
            false
        }
    }

    fun queueNext(path: String): Boolean {
        if (!isInitialized) return false
        return try { nativeQueueNext(path) } catch (e: Throwable) {
            Log.e(TAG, "nativeQueueNext failed", e)
            false
        }
    }

    fun queueNextUrl(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        if (!isInitialized) return false
        return try { nativeQueueNextUrl(url, flattenHeaders(headers)) } catch (e: Throwable) {
            Log.e(TAG, "nativeQueueNextUrl failed", e)
            false
        }
    }

    fun setPlaybackSpeed(speed: Float): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetPlaybackSpeed(speed.coerceIn(0.25f, 4.0f))
        } catch (e: Throwable) {
            false
        }
    }

    fun getPlaybackSpeed(): Float {
        if (!isInitialized) return 1f
        return try {
            nativeGetPlaybackSpeed().takeIf { it.isFinite() && it > 0f } ?: 1f
        } catch (e: Throwable) {
            1f
        }
    }

    fun setCrossfade(enabled: Boolean, durationSecs: Float): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetCrossfade(enabled, durationSecs.coerceAtLeast(0f))
        } catch (e: Throwable) {
            false
        }
    }

    fun pause(): Boolean {
        if (!isInitialized) return false
        return try {
            nativePause()
        } catch (e: Throwable) {
            Log.e(TAG, "nativePause failed", e)
            false
        }
    }

    fun resume(): Boolean {
        if (!isInitialized) return false
        return try {
            nativeResume()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeResume failed", e)
            false
        }
    }

    fun stop(): Boolean {
        if (!isInitialized) return false
        return try {
            nativeStop()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeStop failed", e)
            false
        }
    }

    fun seek(positionSecs: Double): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSeek(positionSecs)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSeek failed", e)
            false
        }
    }

    fun setVolume(volume: Float): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetVolume(volume.coerceIn(0f, 1f))
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSetVolume failed", e)
            false
        }
    }

    fun setEqualizer(enabled: Boolean, freqs: FloatArray, gains: FloatArray, qs: FloatArray): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetEqualizer(enabled, freqs, gains, qs)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSetEqualizer failed", e)
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
            Log.e(TAG, "nativeSetFx failed", e)
            false
        }
    }

    fun getState(): String {
        if (!isInitialized) return "Uninitialized"
        return try {
            nativeGetState()
        } catch (e: Throwable) {
            "Error"
        }
    }

    fun getProgress(): AudioEvent.Progress? {
        if (!isInitialized) return null
        return try {
            val jsonStr = nativeGetProgress() ?: return null
            if (jsonStr.isEmpty() || jsonStr == "null" || jsonStr == "{}") return null
            val json = JSONObject(jsonStr)
            AudioEvent.Progress(
                positionSecs = json.optDouble("position_secs", 0.0),
                durationSecs = json.optDouble("duration_secs", 0.0),
                bufferLevel = json.optDouble("buffer_level", 0.0),
            )
        } catch (e: Throwable) {
            null
        }
    }

    fun stopEngine() {
        pollJob?.cancel()
        pollJob = null
        progressJob?.cancel()
        progressJob = null
        if (isInitialized) {
            try {
                nativeStop()
            } catch (e: Throwable) {
                Log.w(TAG, "nativeStop error on stopEngine", e)
            }
        }
    }

    // ═══════════════════════════════════════════════
    // DSD (Direct Stream Digital) / DAP Bit-Perfect 支持
    // ═══════════════════════════════════════════════

    /**
     * DSD 输出模式: 0=PCM 软解, 1=DoP (DSD over PCM), 2=Native DSD 直通, 3=Auto
     */
    fun setDsdOutputMode(mode: Int): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetDsdOutputMode(mode)
        } catch (e: Throwable) {
            false
        }
    }

    /** 设置 DAP (数字音频播放器专用) Bit-Perfect 位完美模式 */
    fun setDapBitPerfectEnabled(enabled: Boolean): Boolean {
        if (!isInitialized) return false
        return try {
            nativeSetDapBitPerfectEnabled(enabled)
        } catch (e: Throwable) {
            false
        }
    }

    // ═══════════════════════════════════════════════
    // USB Audio Class 2.0 (UAC2) 直通输出支持
    // ═══════════════════════════════════════════════

    fun registerDirectUsbDevice(
        fd: Int,
        vendorId: Int,
        productId: Int,
        productName: String?,
        manufacturer: String?,
        serial: String?,
        deviceName: String?,
    ): Boolean {
        if (!isInitialized) return false
        return try {
            nativeRegisterRustDirectUsbDevice(fd, vendorId, productId, productName, manufacturer, serial, deviceName)
        } catch (e: Throwable) {
            false
        }
    }

    fun clearDirectUsbPlayback(): Boolean {
        if (!isInitialized) return false
        return try {
            nativeClearRustDirectUsbPlayback()
        } catch (e: Throwable) {
            false
        }
    }

    fun waitDirectUsbSessionStopped(timeoutMs: Int): Boolean {
        if (!isInitialized) return true
        return try {
            nativeWaitRustDirectUsbSessionStopped(timeoutMs)
        } catch (e: Throwable) {
            false
        }
    }

    fun isRustDirectUsbSessionActive(): Boolean {
        if (!isInitialized) return false
        return try {
            nativeIsRustDirectUsbSessionActive()
        } catch (e: Throwable) {
            false
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val eventStr = nativePollEvent()
                    if (!eventStr.isNullOrEmpty() && eventStr != "null") {
                        handleEventJson(eventStr)
                    } else {
                        delay(40)
                    }
                } catch (e: Throwable) {
                    delay(500)
                }
            }
        }
    }

    /**
     * 进度轮询：把 [getProgress] 的结果按节拍转成 [AudioEvent.Progress] 事件。
     *
     * 引擎的事件流里**永远没有** Progress（Rust 侧从不构造），`FlickPlatformPlayer`
     * 的时长/位置/seek 对账又全挂在 `audioEvents` 上 —— 必须在这里合成事件，
     * 与桌面 `DesktopHiResEngine.startProgressPolling` 同一机制。
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

    private fun handleEventJson(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            when (json.optString("type")) {
                "StateChanged" -> {
                    val state = json.optString("state", "Idle")
                    val path = json.optString("path", "").ifEmpty { null }
                    _engineState.value = state
                    _audioEvents.tryEmit(AudioEvent.StateChanged(state, path))
                }
                "Progress" -> {
                    val progress = AudioEvent.Progress(
                        positionSecs = json.optDouble("position_secs", 0.0),
                        durationSecs = json.optDouble("duration_secs", 0.0),
                        bufferLevel = json.optDouble("buffer_level", 0.0),
                    )
                    _engineProgress.value = progress
                    _audioEvents.tryEmit(progress)
                }
                "TrackEnded" -> {
                    _audioEvents.tryEmit(AudioEvent.TrackEnded(json.optString("path")))
                }
                "CrossfadeStarted" -> {
                    _audioEvents.tryEmit(
                        AudioEvent.CrossfadeStarted(
                            fromPath = json.optString("from_path"),
                            toPath = json.optString("to_path"),
                        )
                    )
                }
                "Error" -> {
                    _audioEvents.tryEmit(AudioEvent.Error(json.optString("message")))
                }
                "NextTrackReady" -> {
                    _audioEvents.tryEmit(AudioEvent.NextTrackReady(json.optString("path")))
                }
                "FormatChanged" -> {
                    val sampleRate = json.optInt("sample_rate", 0)
                    val bitrate = json.optInt("bitrate", 0)
                    val bitDepth = json.optInt("bit_depth", 0)
                    val channels = json.optInt("channels", 0)
                    val codecName = json.optString("codec_name", "")
                    _audioEvents.tryEmit(
                        AudioEvent.FormatChanged(sampleRate, bitrate, bitDepth, channels, codecName)
                    )
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse Rust event: $jsonStr", e)
        }
    }

    // Native JNI Declarations
    private external fun nativeInit(): Boolean
    private external fun nativeInitRustAndroidContext(context: Context): Boolean
    private external fun nativePlay(path: String): Boolean
    private external fun nativePlayUrl(url: String, headers: Array<String>): Boolean
    private external fun nativeQueueNext(path: String): Boolean
    private external fun nativeQueueNextUrl(url: String, headers: Array<String>): Boolean
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
    private external fun nativeGetState(): String
    private external fun nativeGetProgress(): String?
    private external fun nativePollEvent(): String?
    private external fun nativeSetPlaybackSpeed(speed: Float): Boolean
    private external fun nativeGetPlaybackSpeed(): Float
    private external fun nativeSetCrossfade(enabled: Boolean, durationSecs: Float): Boolean

    private external fun nativeSetDsdOutputMode(mode: Int): Boolean
    private external fun nativeSetDapBitPerfectEnabled(enabled: Boolean): Boolean

    private external fun nativeRegisterRustDirectUsbDevice(
        fd: Int, vendorId: Int, productId: Int, productName: String?,
        manufacturer: String?, serial: String?, deviceName: String?,
    ): Boolean
    private external fun nativeClearRustDirectUsbPlayback(): Boolean
    private external fun nativeWaitRustDirectUsbSessionStopped(timeoutMs: Int): Boolean
    private external fun nativeIsRustDirectUsbSessionActive(): Boolean

    private fun flattenHeaders(headers: Map<String, String>): Array<String> =
        headers.flatMap { (key, value) -> listOf(key, value) }.toTypedArray()
}
