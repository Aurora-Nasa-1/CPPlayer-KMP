package cp.player.core.playback.flick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import cp.player.engine.RustEngine

/**
 * Android 平台发烧音频生命周期与硬件外设管理器。
 *
 * 负责四大核心系统级交互：
 * 1. **音频焦点（Audio Focus）**：来电、微信语音、其他音乐软件抢占时的暂停、恢复与优雅降级；
 * 2. **耳机防漏音（Becoming Noisy）**：拔掉耳机或转接线瞬间立即暂停，防止公共场合外放；
 * 3. **USB DAC 热插拔与异常熔断**：外接“小尾巴”或 USB 解码器拔出瞬间熔断底层 UAC2 等时管道，避免 Native Crash；
 * 4. **USB 权限管理**：免驱硬件直通模式下的 USB 设备发现与权限握手。
 */
class AndroidAudioLifecycleManager(
    private val context: Context,
    private val onPauseRequested: () -> Unit,
    private val onResumeRequested: () -> Unit,
) {

    companion object {
        private const val TAG = "AndroidAudioLifecycle"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var wasPlayingBeforeFocusLoss = false

    private var isReceiverRegistered = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "Audio focus lost permanently (e.g. another media app started). Pausing.")
                hasAudioFocus = false
                wasPlayingBeforeFocusLoss = false
                onPauseRequested()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Audio focus lost transiently (e.g. incoming call or notification). Pausing.")
                hasAudioFocus = false
                wasPlayingBeforeFocusLoss = true
                onPauseRequested()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "Audio focus transient can duck. In Hi-Res bit-perfect mode, pausing instead of ducking.")
                hasAudioFocus = false
                wasPlayingBeforeFocusLoss = true
                onPauseRequested()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio focus gained/restored.")
                hasAudioFocus = true
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    onResumeRequested()
                }
            }
        }
    }

    private val hardwareBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    Log.i(TAG, "Audio becoming noisy (headphone unplugged). Pausing playback immediately.")
                    onPauseRequested()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Log.i(TAG, "USB Device detached: ${device?.productName ?: "unknown"}")
                    handleUsbDeviceDetached(device)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Log.i(TAG, "USB Device attached: ${device?.productName ?: "unknown"}")
                    handleUsbDeviceAttached(device)
                }
            }
        }
    }

    /** 请求音频焦点 */
    fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val am = audioManager ?: return true

        val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }

        hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Log.i(TAG, "requestAudioFocus result: $hasAudioFocus")
        return hasAudioFocus
    }

    /** 放弃音频焦点 */
    fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
        wasPlayingBeforeFocusLoss = false
        Log.i(TAG, "abandonAudioFocus completed")
    }

    /** 开始监听硬件热插拔与声音外泄广播 */
    fun startListening() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(hardwareBroadcastReceiver, filter)
        isReceiverRegistered = true
        Log.i(TAG, "Hardware lifecycle listener registered")
    }

    /** 停止监听 */
    fun stopListening() {
        abandonAudioFocus()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(hardwareBroadcastReceiver)
            } catch (_: Throwable) {
            }
            isReceiverRegistered = false
            Log.i(TAG, "Hardware lifecycle listener unregistered")
        }
    }

    private fun handleUsbDeviceDetached(device: UsbDevice?) {
        // 熔断 Rust 底层 UAC2 等时传输会话，避免往关闭的描述符写数据引发 Native Crash
        try {
            if (RustEngine.isRustDirectUsbSessionActive()) {
                Log.w(TAG, "Active Direct USB session detected on detach! Executing emergency circuit breaker.")
                RustEngine.clearDirectUsbPlayback()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error during USB detach fallback", e)
        }
        onPauseRequested()
    }

    private fun handleUsbDeviceAttached(device: UsbDevice?) {
        if (device == null) return
        val um = usbManager ?: return
        if (um.hasPermission(device)) {
            tryRegisterUsbDevice(device)
        } else {
            Log.i(TAG, "USB attached but permission not yet granted for: ${device.productName}")
        }
    }

    /** 尝试将已授权的 USB DAC 注册到底层 Rust 驱动 */
    fun tryRegisterUsbDevice(device: UsbDevice): Boolean {
        val um = usbManager ?: return false
        if (!um.hasPermission(device)) return false

        val conn = um.openDevice(device) ?: run {
            Log.w(TAG, "Failed to open USB device connection: ${device.deviceName}")
            return false
        }

        val fd = conn.fileDescriptor
        val ok = RustEngine.registerDirectUsbDevice(
            fd = fd,
            vendorId = device.vendorId,
            productId = device.productId,
            productName = device.productName,
            manufacturer = device.manufacturerName,
            serial = device.serialNumber,
            deviceName = device.deviceName,
        )
        Log.i(TAG, "RustEngine.registerDirectUsbDevice result: $ok (fd=$fd, name=${device.productName})")
        return ok
    }
}
