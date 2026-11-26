package cp.player.core.playback.flick

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import cp.player.engine.RustEngine
import kotlinx.coroutines.launch

/**
 * Android 平台发烧音频生命周期与硬件外设管理器。
 *
 * 负责两大核心系统级交互（USB 断开/3 秒重连监听已上移至 [DynamicAndroidPlatformPlayer]，
 * 确保标准 Media3 与 FlickEngine 在任意输出模式下表现一致）：
 * 1. **音频焦点（Audio Focus）**：来电、语音、其它媒体抢占焦点时暂停并释放 USB 独占，焦点恢复后按需续播；
 * 2. **按需 USB DAC 独占接管与彻底释放**：仅在用户开启 USB 独占且处于实际播放期间打开并注册
 *    `UsbDeviceConnection`，在暂停/停止/切回 AAudio/退出/拔线瞬间立刻释放内核与 libusb 接口，
 *    避免「退出软件后其它应用无声」或「播不了也占着声卡」。
 */
class AndroidAudioLifecycleManager(
    private val context: Context,
    private val onAudioFocusLossPause: (transient: Boolean) -> Unit,
    private val onResumeRequested: () -> Unit,
    private val onUsbPermissionGranted: () -> Unit = {},
) {

    companion object {
        private const val TAG = "AndroidAudioLifecycle"
        private const val ACTION_USB_PERMISSION = "cp.player.core.USB_PERMISSION"

        fun isUsbAudioDevice(device: UsbDevice): Boolean =
            device.deviceClass == UsbConstants.USB_CLASS_AUDIO ||
                (0 until device.interfaceCount).any {
                    device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
                }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var wasPlayingBeforeFocusLoss = false

    private var isReceiverRegistered = false
    @Volatile
    private var registeredDeviceName: String? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var pendingUsbDevice: String? = null
    private val permissionRequestedDevices = mutableSetOf<String>()

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "Audio focus lost permanently (e.g. another media app started). Pausing & releasing USB.")
                hasAudioFocus = false
                wasPlayingBeforeFocusLoss = false
                onAudioFocusLossPause(false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Audio focus lost transiently (e.g. incoming call or notification). Pausing & releasing USB.")
                hasAudioFocus = false
                wasPlayingBeforeFocusLoss = true
                onAudioFocusLossPause(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "Audio focus transient can duck. Pausing cleanly to avoid mixing/clipping.")
                hasAudioFocus = false
                wasPlayingBeforeFocusLoss = true
                onAudioFocusLossPause(true)
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

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDevice() ?: return
            val granted: Boolean
            synchronized(this@AndroidAudioLifecycleManager) {
                if (pendingUsbDevice != device.deviceName) return
                pendingUsbDevice = null
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            }
            if (granted) {
                onUsbPermissionGranted()
            } else {
                Log.w(TAG, "USB audio permission denied by user for ${device.productName}; falling back to AAudio.")
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

    /** 注册 USB 权限回调（不在初始化时自动抢占 USB 声卡）。 */
    @Synchronized
    fun startListening() {
        if (isReceiverRegistered) return
        ContextCompat.registerReceiver(
            context, usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isReceiverRegistered = true
        Log.i(TAG, "Audio lifecycle listener registered (USB idle until exclusive playback)")
    }

    /** 停止监听并彻底释放音频焦点与 USB 独占句柄。 */
    @Synchronized
    fun stopListening() {
        abandonAudioFocus()
        releaseUsbDevice()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbPermissionReceiver)
            } catch (_: Throwable) {
            }
            isReceiverRegistered = false
            pendingUsbDevice = null
            permissionRequestedDevices.clear()
            Log.i(TAG, "Audio lifecycle listener unregistered")
        }
    }

    fun hasUsbExclusiveActive(): Boolean = usbConnection != null

    fun hasConnectedUsbAudioDevice(): Boolean {
        val um = usbManager ?: return false
        return um.deviceList.values.any(::isUsbAudioDevice)
    }

    @Synchronized
    fun onUsbAudioDetached(deviceName: String?) {
        if (deviceName != null) {
            permissionRequestedDevices.remove(deviceName)
            if (pendingUsbDevice == deviceName) pendingUsbDevice = null
        } else {
            permissionRequestedDevices.clear()
            pendingUsbDevice = null
        }
        if (deviceName == null || deviceName == registeredDeviceName) {
            releaseUsbDevice()
        }
    }

    /**
     * 仅在开启 USB 独占且即将播放时接管 USB DAC。
     * 无设备、未授权（异步弹窗授权）或打开失败时返回 false，由上层无感回退 AAudio 共享输出。
     */
    @Synchronized
    fun acquireUsbDeviceForPlayback(
        sampleRate: Int = 48_000,
        bitDepth: Int = 24,
        channels: Int = 2,
        isDop: Boolean = false,
        isNativeDsd: Boolean = false,
    ): Boolean {
        val um = usbManager ?: return false
        if (!isReceiverRegistered) return false
        val devices = um.deviceList.values.filter(::isUsbAudioDevice).sortedBy { it.deviceName }
        if (devices.isEmpty()) {
            releaseUsbDevice()
            return false
        }

        val currentConn = usbConnection
        val currentName = registeredDeviceName
        if (currentConn != null && currentName != null) {
            if (devices.any { it.deviceName == currentName }) {
                RustEngine.setDirectUsbEnabled(true)
                RustEngine.setDirectUsbLockEnabled(false)
                RustEngine.setDirectUsbPlaybackFormat(sampleRate, bitDepth, channels, isDop, isNativeDsd)
                return true
            }
            // 已记录设备实际已经掉线，清理旧句柄后再尝试新设备。
            releaseUsbDevice()
        }

        val grantedDevice = devices.firstOrNull { um.hasPermission(it) }
        if (grantedDevice != null) {
            return tryRegisterUsbDevice(
                device = grantedDevice,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                channels = channels,
                isDop = isDop,
                isNativeDsd = isNativeDsd,
            )
        }

        devices.firstOrNull { !um.hasPermission(it) && it.deviceName !in permissionRequestedDevices }
            ?.let(::requestUsbPermission)
        return false
    }

    /** 彻底释放 USB DAC：终止等时传输会话、关闭 idle lock、关闭 UsbDeviceConnection 让内核收回声卡。 */
    @Synchronized
    fun releaseUsbDevice() {
        val hadConnection = usbConnection != null
        try {
            RustEngine.setDirectUsbLockEnabled(false)
            RustEngine.setDirectUsbEnabled(false)
            RustEngine.clearDirectUsbPlayback()
            if (hadConnection || RustEngine.isRustDirectUsbSessionActive()) {
                RustEngine.waitDirectUsbSessionStopped(1500)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error during USB release", e)
        } finally {
            runCatching { usbConnection?.close() }
            usbConnection = null
            registeredDeviceName = null
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        if (pendingUsbDevice != null || !isUsbAudioDevice(device)) return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        pendingUsbDevice = device.deviceName
        permissionRequestedDevices.add(device.deviceName)
        runCatching { usbManager?.requestPermission(device, permissionIntent) }.onFailure {
            pendingUsbDevice = null
            Log.w(TAG, "Unable to request USB audio permission", it)
        }
    }

    /** 尝试将已授权的 USB DAC 注册到底层 Rust 驱动 */
    @Synchronized
    private fun tryRegisterUsbDevice(
        device: UsbDevice,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        isDop: Boolean,
        isNativeDsd: Boolean,
    ): Boolean {
        val um = usbManager ?: return false
        if (!isReceiverRegistered || !isUsbAudioDevice(device) || !um.hasPermission(device)) return false
        if (usbConnection != null) return registeredDeviceName == device.deviceName

        val conn = um.openDevice(device) ?: run {
            Log.w(TAG, "Failed to open USB device connection: ${device.deviceName}")
            return false
        }

        try {
            RustEngine.setDirectUsbLockEnabled(false)
            RustEngine.setDirectUsbEnabled(true)
            RustEngine.setDirectUsbPlaybackFormat(sampleRate, bitDepth, channels, isDop, isNativeDsd)
            // 先踢掉内核音频驱动再释放 Java claim；libusb 才能独占音频接口。
            for (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    conn.claimInterface(usbInterface, true)
                ) {
                    conn.releaseInterface(usbInterface)
                }
            }
            val fd = conn.fileDescriptor
            val ok = fd >= 0 && RustEngine.registerDirectUsbDevice(
                fd = fd,
                vendorId = device.vendorId,
                productId = device.productId,
                productName = device.productName,
                manufacturer = device.manufacturerName,
                serial = device.serialNumber,
                deviceName = device.deviceName,
            )
            if (ok) {
                usbConnection = conn
                registeredDeviceName = device.deviceName
            } else {
                RustEngine.clearDirectUsbPlayback()
                RustEngine.setDirectUsbEnabled(false)
                conn.close()
            }
            Log.i(TAG, "RustEngine.registerDirectUsbDevice result: $ok (fd=$fd, name=${device.productName})")
            return ok
        } catch (error: Throwable) {
            runCatching {
                RustEngine.clearDirectUsbPlayback()
                RustEngine.setDirectUsbEnabled(false)
            }
            conn.close()
            Log.e(TAG, "Failed to register USB DAC", error)
            return false
        }
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
