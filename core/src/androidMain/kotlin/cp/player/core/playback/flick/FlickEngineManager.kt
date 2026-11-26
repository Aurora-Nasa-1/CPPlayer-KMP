package cp.player.core.playback.flick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import cp.player.core.license.VipLicenseManager
import cp.player.core.playback.AudioEffectCapabilities
import cp.player.core.playback.AudioEffectConfig
import cp.player.core.playback.AudioFormatInfo
import cp.player.core.playback.Media3PlatformPlayer
import cp.player.core.playback.NativeTrackTransition
import cp.player.core.playback.PlatformPlaybackState
import cp.player.core.playback.PlatformPlayer
import cp.player.core.playback.PlaybackMetadata
import cp.player.core.playback.PlaybackSessionSettings
import cp.player.core.playback.SeekFailure
import cp.player.core.playback.UsbReconnectStateMachine
import cp.player.core.util.SettingsStorage
import cp.player.core.util.defaultSettingsStorage
import cp.player.engine.RustEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 手机端播放引擎类型。
 */
enum class AndroidPlayerEngineType {
    /** 标准系统引擎（基于 AndroidX Media3 ExoPlayer），网络流鲁棒性高，兼容性好。 */
    STANDARD_MEDIA3,

    /**
     * 高级发烧引擎（基于 Rust Oboe / AAudio + Symphonia + 按需 UAC2 USB 独占 + DSD 软解）。
     * 适合用于后续「VIP / 发烧友 Hi-Res 专业版」付费特权功能。
     */
    HIRES_FLICK,
}

/**
 * 播放器引擎管理器，支持动态切换标准 Media3 (ExoPlayer) 与 Flick Hi-Res 引擎。
 */
object FlickEngineManager {
    private const val TAG = "FlickEngineManager"

    /** 检查当前设备与安装包是否支持 Flick Hi-Res 原生引擎 */
    val isHiResEngineSupported: Boolean
        get() = RustEngine.isAvailable

    private var activeDynamicPlayer: DynamicAndroidPlatformPlayer? = null

    /**
     * 根据偏好设置与底层可用性创建对应的平台播放器（返回支持动态热切换的路由代理）。
     */
    fun createPlayer(
        context: Context,
        preferredEngine: AndroidPlayerEngineType? = null,
        storage: SettingsStorage = defaultSettingsStorage(),
    ): PlatformPlayer {
        val player = DynamicAndroidPlatformPlayer(context, storage, preferredEngine)
        activeDynamicPlayer = player
        return player
    }

    /** 主动通知当前播放器重载/热切换引擎 */
    fun notifyEngineChanged() {
        activeDynamicPlayer?.checkAndSwitchEngine(force = true)
    }

    /** 切换 Android USB 独占模式时通知当前播放器即时重绑或释放 USB 声卡。 */
    fun notifyUsbExclusiveChanged(enabled: Boolean) {
        activeDynamicPlayer?.onUsbExclusiveModeChanged(enabled)
    }

    /** 若当前不在播放中，立即释放 USB 独占（供 Service 退出或划掉任务卡片时兜底调用）。 */
    fun releaseUsbIfIdle() {
        activeDynamicPlayer?.releaseUsbIfIdle()
    }
}

/**
 * 安卓端动态切换代理播放器：在标准 ExoPlayer 与发烧 FlickEngine 之间无缝热切换，
 * 并统一承担「USB 音频断开后 3 秒重连自动恢复播放」（跨引擎、跨 USB 独占开关一致生效）。
 */
internal class DynamicAndroidPlatformPlayer(
    private val context: Context,
    private val storage: SettingsStorage,
    private val preferredEngine: AndroidPlayerEngineType?,
) : PlatformPlayer {

    companion object {
        private const val TAG = "DynamicAndroidPlatformPlayer"
        private const val USB_RECONNECT_SETTLE_MS = 380L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbReconnectStateMachine = UsbReconnectStateMachine()
    private var usbAutoResumeJob: Job? = null

    private val _state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
    override val state: StateFlow<PlatformPlaybackState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _formatInfo = MutableStateFlow<AudioFormatInfo?>(null)
    override val formatInfo: StateFlow<AudioFormatInfo?> = _formatInfo.asStateFlow()

    private val _seekFailures = MutableSharedFlow<SeekFailure>(extraBufferCapacity = 8)
    override val seekFailures: SharedFlow<SeekFailure> = _seekFailures.asSharedFlow()

    override val supportsExclusiveAudio: Boolean
        get() = activePlayer.supportsExclusiveAudio

    override val audioEffectCapabilities: AudioEffectCapabilities
        get() = activePlayer.audioEffectCapabilities

    /** Used during the initial route decision as well as during subsequent loads. */
    @Volatile private var drmPinned = false
    private var currentEngineType: AndroidPlayerEngineType = resolveDesiredEngine()
    private var activePlayer: PlatformPlayer = createUnderlyingPlayer(currentEngineType)
    private var forwardJob: Job? = null

    private var lastVolume: Float = 1.0f
    private var lastEffectConfig: AudioEffectConfig? = null
    private var lastPlaybackSpeed: Float? = null
    private var lastCrossfade: Pair<Boolean, Long>? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var usbReceiverRegistered = false
    private var audioDeviceCallbackRegistered = false

    private val usbHardwareReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    usbReconnectStateMachine.onBecomingNoisy()
                    val current = activePlayer
                    if (current is FlickPlatformPlayer) {
                        current.pauseForHardwareDisconnect()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.extractUsbDevice() ?: return
                    if (AndroidAudioLifecycleManager.isUsbAudioDevice(device)) {
                        handleUsbAudioDisconnected()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.extractUsbDevice() ?: return
                    if (AndroidAudioLifecycleManager.isUsbAudioDevice(device)) {
                        handleUsbAudioConnected()
                    }
                }
            }
        }
    }

    private val audioDeviceCallback: AudioDeviceCallback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    if (removedDevices?.any(::isUsbAudioDeviceInfo) == true) {
                        handleUsbAudioDisconnected()
                    }
                }

                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    if (addedDevices?.any(::isUsbAudioDeviceInfo) == true) {
                        handleUsbAudioConnected()
                    }
                }
            }
        } else {
            null
        }

    override val supportsPlaybackSpeed: Boolean get() = activePlayer.supportsPlaybackSpeed
    override val playbackSpeed: Float get() = activePlayer.playbackSpeed
    override val supportsNativeCrossfade: Boolean get() = activePlayer.supportsNativeCrossfade
    override val supportsGaplessQueue: Boolean get() = activePlayer.supportsGaplessQueue
    private val _nativeTrackTransitions = MutableStateFlow<NativeTrackTransition?>(null)
    override val nativeTrackTransitions: StateFlow<NativeTrackTransition?> = _nativeTrackTransitions.asStateFlow()
    override val supportsSeekableRemoteStreams: Boolean get() = activePlayer.supportsSeekableRemoteStreams
    /** 即使当前是 Flick，也能在 [loadDrm] 时强制切到 Media3，因此对上游声称可用。 */
    override val supportsDrm: Boolean get() = true

    init {
        bindUnderlyingPlayer(activePlayer)
        registerUsbMonitors()
        scope.launch {
            VipLicenseManager.licenseState.map { it.isActivated }.distinctUntilChanged().collect { active ->
                if (active) {
                    checkAndSwitchEngine()
                } else if (currentEngineType == AndroidPlayerEngineType.HIRES_FLICK) {
                    switchEngine(AndroidPlayerEngineType.STANDARD_MEDIA3)
                }
            }
        }
    }

    private fun registerUsbMonitors() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbHardwareReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ContextCompat.registerReceiver(
                    context,
                    usbHardwareReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }
            usbReceiverRegistered = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            runCatching {
                audioManager?.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
                audioDeviceCallbackRegistered = true
            }
        }
    }

    private fun unregisterUsbMonitors() {
        if (usbReceiverRegistered) {
            runCatching { context.unregisterReceiver(usbHardwareReceiver) }
            usbReceiverRegistered = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallbackRegistered && audioDeviceCallback != null) {
            runCatching { audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback) }
            audioDeviceCallbackRegistered = false
        }
    }

    private fun handleUsbAudioDisconnected() {
        usbAutoResumeJob?.cancel()
        val autoResumeEnabled = PlaybackSessionSettings.androidUsbAutoResume(storage)
        val shouldPause = usbReconnectStateMachine.onUsbAudioDisconnected(autoResumeEnabled)
        val current = activePlayer
        if (current is FlickPlatformPlayer) {
            current.onUsbAudioDisconnected(shouldPause)
        } else if (shouldPause) {
            current.pause()
        }
    }

    private fun handleUsbAudioConnected() {
        (activePlayer as? FlickPlatformPlayer)?.onUsbAudioRouteChanged()
        val autoResumeEnabled = PlaybackSessionSettings.androidUsbAutoResume(storage)
        if (!usbReconnectStateMachine.onUsbAudioConnected(autoResumeEnabled)) return

        usbAutoResumeJob?.cancel()
        usbAutoResumeJob = scope.launch {
            delay(USB_RECONNECT_SETTLE_MS)
            val currentState = _state.value
            if (currentState != PlatformPlaybackState.Playing &&
                currentState != PlatformPlaybackState.Buffering
            ) {
                Log.i(TAG, "USB audio reconnected within 3s; resuming playback on $currentEngineType")
                usbReconnectStateMachine.onPlayRequested()
                activePlayer.play()
            }
        }
    }

    private fun isUsbAudioDeviceInfo(info: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return info.isSink && when (info.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> true
            else -> false
        }
    }

    private fun Intent.extractUsbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun resolveDesiredEngine(): AndroidPlayerEngineType {
        if (drmPinned) return AndroidPlayerEngineType.STANDARD_MEDIA3
        val wantsFlick = preferredEngine?.let { it == AndroidPlayerEngineType.HIRES_FLICK }
            ?: (storage.getString(PlaybackSessionSettings.KEY_AUDIO_ENGINE) == PlaybackSessionSettings.ENGINE_FLICK_HIRES)
        return if (wantsFlick && VipLicenseManager.isVipActive && FlickEngineManager.isHiResEngineSupported) {
            AndroidPlayerEngineType.HIRES_FLICK
        } else {
            AndroidPlayerEngineType.STANDARD_MEDIA3
        }
    }

    private fun createUnderlyingPlayer(type: AndroidPlayerEngineType): PlatformPlayer {
        return if (type == AndroidPlayerEngineType.HIRES_FLICK) {
            if (FlickEngineManager.isHiResEngineSupported) {
                Log.i(TAG, "Active: FlickPlatformPlayer (Rust Oboe / AAudio / UAC2 USB)")
                runCatching {
                    FlickPlatformPlayer(
                        context = context,
                        storage = storage,
                        onExternalFocusLoss = { usbReconnectStateMachine.onUserPauseOrStop(stopped = false) },
                    )
                }.getOrElse { err ->
                    Log.w(TAG, "FlickPlatformPlayer creation failed ($err), falling back to ExoPlayer.")
                    Media3PlatformPlayer(context)
                }
            } else {
                Log.w(TAG, "Flick Hi-Res Engine unavailable (.so missing). Falling back to ExoPlayer.")
                Media3PlatformPlayer(context)
            }
        } else {
            Log.i(TAG, "Active: Media3PlatformPlayer (ExoPlayer)")
            Media3PlatformPlayer(context)
        }
    }

    private fun bindUnderlyingPlayer(player: PlatformPlayer) {
        forwardJob?.cancel()
        forwardJob = scope.launch {
            launch {
                player.state.collect { st ->
                    _state.value = st
                    usbReconnectStateMachine.onPlaybackStateChanged(st)
                    // 仅原生库不可用时才降级回 ExoPlayer；单曲解码错误或焦点占用不静默杀掉用户所选引擎。
                    if (st is PlatformPlaybackState.Error &&
                        currentEngineType == AndroidPlayerEngineType.HIRES_FLICK &&
                        st.message.contains("native library not available", ignoreCase = true)
                    ) {
                        Log.w(TAG, "FlickEngine native library unavailable (${st.message}). Failing over to ExoPlayer!")
                        switchEngine(AndroidPlayerEngineType.STANDARD_MEDIA3)
                    }
                }
            }
            launch { player.positionMs.collect { _positionMs.value = it } }
            launch { player.durationMs.collect { _durationMs.value = it } }
            launch { player.formatInfo.collect { _formatInfo.value = it } }
            launch { player.seekFailures.collect { _seekFailures.tryEmit(it) } }
            launch { player.nativeTrackTransitions.collect { _nativeTrackTransitions.value = it } }
        }
        player.setVolume(lastVolume)
        lastEffectConfig?.let { player.applyAudioEffect(it) }
        lastPlaybackSpeed?.let { player.setPlaybackSpeed(it) }
        lastCrossfade?.let { player.setNativeCrossfade(it.first, it.second) }
    }

    private fun switchEngine(target: AndroidPlayerEngineType) {
        if (target == currentEngineType) return
        Log.i(TAG, "Switching active engine from $currentEngineType to $target")
        usbAutoResumeJob?.cancel()
        val oldPlayer = activePlayer
        try {
            oldPlayer.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing old player: ${t.message}")
        }
        currentEngineType = target
        activePlayer = createUnderlyingPlayer(target)
        bindUnderlyingPlayer(activePlayer)
    }

    fun checkAndSwitchEngine(force: Boolean = false) {
        val desired = resolveDesiredEngine()
        if (desired != currentEngineType || force) {
            Log.i(TAG, "Switching engine from $currentEngineType to $desired")
            usbAutoResumeJob?.cancel()
            val oldPlayer = activePlayer
            try {
                oldPlayer.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Error releasing player: ${t.message}")
            }
            currentEngineType = desired
            activePlayer = createUnderlyingPlayer(desired)
            bindUnderlyingPlayer(activePlayer)
        }
    }

    fun onUsbExclusiveModeChanged(enabled: Boolean) {
        (activePlayer as? FlickPlatformPlayer)?.onUsbExclusiveModeChanged(enabled)
    }

    fun releaseUsbIfIdle() {
        (activePlayer as? FlickPlatformPlayer)?.releaseUsbIfIdle()
    }

    override suspend fun load(
        url: String,
        startPositionMs: Long,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ) {
        drmPinned = false
        usbAutoResumeJob?.cancel()
        usbReconnectStateMachine.onTrackLoaded()
        checkAndSwitchEngine()
        activePlayer.load(url, startPositionMs, headers, metadata)
    }

    override suspend fun loadDrm(
        url: String,
        drm: cp.player.core.music.DrmPlaybackInfo,
        startPositionMs: Long,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ) {
        drmPinned = true
        usbAutoResumeJob?.cancel()
        usbReconnectStateMachine.onTrackLoaded()
        if (currentEngineType != AndroidPlayerEngineType.STANDARD_MEDIA3) {
            switchEngine(AndroidPlayerEngineType.STANDARD_MEDIA3)
        }
        if (!activePlayer.supportsDrm) {
            throw UnsupportedOperationException("The active Android engine does not support DRM")
        }
        activePlayer.loadDrm(url, drm, startPositionMs, headers, metadata)
    }

    override fun play() {
        usbAutoResumeJob?.cancel()
        usbReconnectStateMachine.onPlayRequested()
        checkAndSwitchEngine()
        activePlayer.play()
    }

    override fun pause() {
        usbAutoResumeJob?.cancel()
        usbReconnectStateMachine.onUserPauseOrStop(stopped = false)
        activePlayer.pause()
    }

    override fun stop() {
        usbAutoResumeJob?.cancel()
        usbReconnectStateMachine.onUserPauseOrStop(stopped = true)
        activePlayer.stop()
    }

    override fun seekTo(positionMs: Long) {
        activePlayer.seekTo(positionMs)
    }

    override fun setVolume(volume: Float) {
        lastVolume = volume
        activePlayer.setVolume(volume)
    }

    override fun getVolume(): Float = activePlayer.getVolume()

    override fun release() {
        usbAutoResumeJob?.cancel()
        usbReconnectStateMachine.reset()
        unregisterUsbMonitors()
        scope.cancel()
        activePlayer.release()
    }

    override fun applyAudioEffect(config: AudioEffectConfig) {
        lastEffectConfig = config
        activePlayer.applyAudioEffect(config)
    }

    override fun setPlaybackSpeed(speed: Float) {
        lastPlaybackSpeed = speed
        activePlayer.setPlaybackSpeed(speed)
    }

    override fun setNativeCrossfade(enabled: Boolean, durationMs: Long) {
        lastCrossfade = enabled to durationMs
        activePlayer.setNativeCrossfade(enabled, durationMs)
    }

    override fun queueNext(mediaId: String, source: String, headers: Map<String, String>) {
        activePlayer.queueNext(mediaId, source, headers)
    }

    override fun reloadEngine() {
        checkAndSwitchEngine(force = true)
    }

    override fun reloadOutputRouting() {
        onUsbExclusiveModeChanged(PlaybackSessionSettings.androidUsbExclusive(storage))
    }

    override fun releaseExclusiveAudioIfIdle() {
        releaseUsbIfIdle()
    }
}
