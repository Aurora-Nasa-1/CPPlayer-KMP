package cp.player.app.platform

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32Util
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Windows 原生 System Media Transport Controls (SMTC) 桥接。
 *
 * ## 架构与实现
 *
 * 彻底替换破损的上游 `JavaMediaTransportControls` (JMTC 0.0.3)：
 * 1. JMTC 的 C++ 桥接将 `MediaPlayer` 放在局部栈变量上，返回即析构导致 Windows
 *    Media Foundation 立即拆毁 SMTC 会话；
 * 2. 现代 Windows 10 (1809+) / Windows 11 对未打包（unpackaged classic Win32）桌面应用，
 *    要求通过 [ISystemMediaTransportControlsInterop.GetForWindow] 明确绑定顶层主窗口 HWND；
 * 3. JMTC 硬编码 MTA 初始化，在 AWT EDT 上直接抛访问违例崩溃；
 *
 * 本实现采用纯 JNA COM 驱动 WinRT 接口，无需任何额外动态链接库，直接与 Windows 系统的
 * `combase.dll` / `Windows.Media.dll` 通信：
 * - 绑定主窗口 HWND，实现系统级媒体会话与音量/快捷键悬浮窗；
 * - 监听硬件媒体键与悬浮窗按钮（播放/暂停/上一曲/下一曲）；
 * - 动态推送曲目名、艺术家、专辑名与专辑封面图片；
 * - 精确推送媒体时间轴（TimelineProperties）与播放位置，并去抖节流。
 */
internal class WindowsSmtcControls private constructor(
    private val controller: PlaybackController,
) : PlatformMediaControlsInstance {

    @Volatile
    private var started = false

    @Volatile
    private var stopRequested = false

    private val smtcExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cpplayer-smtc-win").apply { isDaemon = true }
    }

    private val coverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var coverCacheDir: File? = null
    private var latestTrack: TrackSummary? = null

    // WinRT COM 句柄
    private var smtcPtr: Pointer? = null
    private var smtc2Ptr: Pointer? = null
    private var displayUpdaterPtr: Pointer? = null
    private var musicPropertiesPtr: Pointer? = null
    private var musicProperties2Ptr: Pointer? = null
    private var uriFactoryPtr: Pointer? = null
    private var streamStaticsPtr: Pointer? = null

    // 回调对象内存防 GC
    private var callbackVtbl: ComVtbl? = null
    private var callbackMemory: Memory? = null
    private var buttonEventToken: Long = 0L

    // 推送去抖
    private var pushedTrackId: String? = null
    private var pushedDurationMs = -1L
    private var pushedPlayingStatus = -1
    private var pushedPositionMs = -1L
    private var lastPositionPushAt = 0L

    override fun start() {
        if (started || smtcExecutor.isShutdown) return
        stopRequested = false
        smtcExecutor.execute {
            if (started || stopRequested) return@execute
            initWinRtSmtc()
        }
    }

    private fun initWinRtSmtc() {
        runCatching {
            // 1. 设置进程 AUMID 并同步开始菜单快捷方式
            WindowsSmtcIdentity.ensure()

            // 2. 初始化 COM 套间 (MTA)
            combase("RoInitialize").invokeInt(arrayOf(RO_INIT_MULTITHREADED))

            // 3. 解析主窗口 HWND
            var hwndPtr = resolveHwnd()
            var attempts = 0
            while ((hwndPtr == null || Pointer.nativeValue(hwndPtr) == 0L) && attempts < 20) {
                Thread.sleep(100)
                hwndPtr = resolveHwnd()
                attempts++
            }
            if (hwndPtr == null || Pointer.nativeValue(hwndPtr) == 0L) {
                System.err.println("[WindowsSMTC] 未能获取窗口 HWND，放弃初始化")
                return@runCatching
            }

            // 4. 获取 ISystemMediaTransportControlsInterop 工厂
            val hClassName = createHString("Windows.Media.SystemMediaTransportControls")
            val iidInterop = Ole32Util.getGUIDFromString(IID_INTEROP)
            val interopRef = PointerByReference()
            val hrFact = combase("RoGetActivationFactory").invokeInt(arrayOf(
                hClassName, iidInterop.pointer, interopRef,
            ))
            deleteHString(hClassName)
            check(hrFact == 0) { "RoGetActivationFactory(SMTC) failed: 0x${Integer.toHexString(hrFact)}" }
            val interop = interopRef.value ?: error("ISystemMediaTransportControlsInterop is null")

            // 5. GetForWindow(hwnd) -> ISystemMediaTransportControls
            val iidSmtc = Ole32Util.getGUIDFromString(IID_SMTC)
            val smtcRef = PointerByReference()
            val hrGet = method(interop, SLOT_INTEROP_GET_FOR_WINDOW).invokeInt(arrayOf(
                interop, hwndPtr, iidSmtc.pointer, smtcRef,
            ))
            release(interop)
            check(hrGet == 0) { "GetForWindow failed: 0x${Integer.toHexString(hrGet)}" }
            val smtc = smtcRef.value ?: error("SMTC instance is null")
            smtcPtr = smtc

            // 6. 激活控制按钮与状态
            call(smtc, SLOT_SMTC_PUT_IS_ENABLED, (1).toByte())
            call(smtc, SLOT_SMTC_PUT_IS_PLAY_ENABLED, (1).toByte())
            call(smtc, SLOT_SMTC_PUT_IS_PAUSE_ENABLED, (1).toByte())
            call(smtc, SLOT_SMTC_PUT_IS_NEXT_ENABLED, (1).toByte())
            call(smtc, SLOT_SMTC_PUT_IS_PREV_ENABLED, (1).toByte())

            // 7. 注册按钮回调 (Play, Pause, Next, Previous)
            registerButtonCallback(smtc)

            // 8. 获取 DisplayUpdater 与 MusicProperties
            val updaterRef = PointerByReference()
            val hrUpdater = method(smtc, SLOT_SMTC_GET_DISPLAY_UPDATER).invokeInt(arrayOf(smtc, updaterRef))
            if (hrUpdater == 0 && updaterRef.value != null) {
                val updater = updaterRef.value
                displayUpdaterPtr = updater
                call(updater, SLOT_UPDATER_PUT_TYPE, MEDIA_TYPE_MUSIC)

                val musicRef = PointerByReference()
                if (method(updater, SLOT_UPDATER_GET_MUSIC_PROPS).invokeInt(arrayOf(updater, musicRef)) == 0) {
                    val music = musicRef.value
                    musicPropertiesPtr = music

                    val iidMusic2 = Ole32Util.getGUIDFromString(IID_MUSIC_PROPERTIES2)
                    val music2Ref = PointerByReference()
                    if (queryInterface(music, iidMusic2.pointer, music2Ref) == 0) {
                        musicProperties2Ptr = music2Ref.value
                    }
                }
            }

            // 9. 获取 ISystemMediaTransportControls2 (用于 Timeline)
            val iidSmtc2 = Ole32Util.getGUIDFromString(IID_SMTC2)
            val smtc2Ref = PointerByReference()
            if (queryInterface(smtc, iidSmtc2.pointer, smtc2Ref) == 0) {
                smtc2Ptr = smtc2Ref.value
            }

            // 10. 初始化封面 Uri / Stream 激活工厂
            initCoverFactories()

            started = true
            println("[WindowsSMTC] 成功初始化并绑定窗口 HWND=${Pointer.nativeValue(hwndPtr)}")
        }.onFailure {
            System.err.println("[WindowsSMTC] 初始化失败: ${it.stackTraceToString()}")
        }
    }

    private fun initCoverFactories() {
        runCatching {
            // Windows.Foundation.Uri
            val hUriClass = createHString("Windows.Foundation.Uri")
            val iidUriFact = Ole32Util.getGUIDFromString(IID_URI_FACTORY)
            val uriFactRef = PointerByReference()
            if (combase("RoGetActivationFactory").invokeInt(arrayOf(hUriClass, iidUriFact.pointer, uriFactRef)) == 0) {
                uriFactoryPtr = uriFactRef.value
            }
            deleteHString(hUriClass)

            // Windows.Storage.Streams.RandomAccessStreamReference
            val hStreamClass = createHString("Windows.Storage.Streams.RandomAccessStreamReference")
            val iidStreamStatics = Ole32Util.getGUIDFromString(IID_STREAM_REF_STATICS)
            val streamStaticsRef = PointerByReference()
            if (combase("RoGetActivationFactory").invokeInt(arrayOf(hStreamClass, iidStreamStatics.pointer, streamStaticsRef)) == 0) {
                streamStaticsPtr = streamStaticsRef.value
            }
            deleteHString(hStreamClass)
        }
    }

    private fun registerButtonCallback(smtc: Pointer) {
        val vtbl = ComVtbl()
        val mem = Memory(Native.POINTER_SIZE.toLong())

        vtbl.QueryInterface = QueryInterfaceCallback { _, riidPtr, ppv ->
            val guidStr = GUID(riidPtr).toGuidString().lowercase()
            if (guidStr.contains(IID_IUNKNOWN) ||
                guidStr.contains(IID_AGILE_OBJECT) ||
                guidStr.contains(IID_BUTTON_EVENT_HANDLER)
            ) {
                ppv.setValue(mem)
                0 // S_OK
            } else {
                ppv.setValue(Pointer.NULL)
                E_NOINTERFACE
            }
        }
        vtbl.AddRef = AddRefCallback { 1 }
        vtbl.Release = ReleaseCallback { 1 }
        vtbl.Invoke = InvokeCallback { _, _, argsPtr ->
            if (argsPtr != null) {
                val btnMem = Memory(4)
                if (method(argsPtr, SLOT_EVENT_ARGS_GET_BUTTON).invokeInt(arrayOf(argsPtr, btnMem)) == 0) {
                    when (btnMem.getInt(0)) {
                        BUTTON_PLAY -> controller.resume()
                        BUTTON_PAUSE, BUTTON_STOP -> controller.pause()
                        BUTTON_NEXT -> controller.skipNext()
                        BUTTON_PREVIOUS -> controller.skipPrevious()
                    }
                }
            }
            0 // S_OK
        }
        vtbl.write()
        mem.setPointer(0, vtbl.pointer)

        callbackVtbl = vtbl
        callbackMemory = mem

        val tokenRef = LongByReference()
        val hr = method(smtc, SLOT_SMTC_ADD_BUTTON_PRESSED).invokeInt(arrayOf(smtc, mem, tokenRef))
        if (hr == 0) {
            buttonEventToken = tokenRef.value
        } else {
            System.err.println("[WindowsSMTC] add_ButtonPressed 失败: 0x${Integer.toHexString(hr)}")
        }
    }

    override fun update(state: PlaybackUiState) {
        try {
            smtcExecutor.execute { pushState(state) }
        } catch (_: RejectedExecutionException) {
            // stop() 之后的残留提交
        }
    }

    private fun pushState(state: PlaybackUiState) {
        val smtc = smtcPtr ?: return
        val updater = displayUpdaterPtr ?: return
        val track = state.currentTrack
        latestTrack = track

        if (track == null) {
            if (pushedTrackId != null) {
                resetPushedState()
                runCatching {
                    call(smtc, SLOT_SMTC_PUT_PLAYBACK_STATUS, STATUS_CLOSED)
                    call(updater, SLOT_UPDATER_CLEAR_ALL)
                    call(updater, SLOT_UPDATER_UPDATE)
                }
            }
            return
        }

        val duration = state.durationMs.coerceAtLeast(track.durationMs).coerceAtLeast(0L)
        val playingStatus = when {
            state.isBuffering -> STATUS_CHANGING
            state.isPlaying -> STATUS_PLAYING
            else -> STATUS_PAUSED
        }
        val position = state.positionMs.coerceIn(0L, duration)
        val now = System.currentTimeMillis()
        var dirty = false

        // 1) 换歌时更新元数据与封面
        if (pushedTrackId != track.id) {
            pushedTrackId = track.id
            pushedDurationMs = duration
            pushedPositionMs = position
            lastPositionPushAt = now
            updateMetadata(track, coverFile(track.id, track.coverUrl))
            updateTimeline(duration, position)
            dirty = true
        } else if (pushedDurationMs != duration) {
            // 2) 时长后探出：仅补推时间轴
            pushedDurationMs = duration
            updateTimeline(duration, position)
        }

        // 3) 播放状态翻转
        if (pushedPlayingStatus != playingStatus) {
            pushedPlayingStatus = playingStatus
            call(smtc, SLOT_SMTC_PUT_PLAYBACK_STATUS, playingStatus)
            dirty = true
        }

        // 4) 位置节流更新
        val jumped = abs(position - pushedPositionMs) > POSITION_JUMP_TOLERANCE_MS
        if (jumped || now - lastPositionPushAt >= POSITION_PUSH_INTERVAL_MS) {
            pushedPositionMs = position
            lastPositionPushAt = now
            updateTimeline(duration, position)
        }

        // 5) 提交刷新
        if (dirty) {
            call(updater, SLOT_UPDATER_UPDATE)
        }
    }

    private fun updateMetadata(track: TrackSummary, coverFile: File?) {
        val music = musicPropertiesPtr ?: return
        val updater = displayUpdaterPtr ?: return

        // 标题与艺术家
        val hTitle = createHString(track.name.ifBlank { "CPPlayer" })
        method(music, SLOT_MUSIC_PUT_TITLE).invokeInt(arrayOf(music, hTitle))
        deleteHString(hTitle)

        val hArtist = createHString(track.artist)
        method(music, SLOT_MUSIC_PUT_ARTIST).invokeInt(arrayOf(music, hArtist))
        deleteHString(hArtist)

        // 专辑名
        musicProperties2Ptr?.let { music2 ->
            val hAlbum = createHString(track.album.orEmpty())
            method(music2, SLOT_MUSIC2_PUT_ALBUM_TITLE).invokeInt(arrayOf(music2, hAlbum))
            deleteHString(hAlbum)
        }

        // 封面设置
        applyCoverThumbnail(updater, coverFile)
    }

    private fun applyCoverThumbnail(updater: Pointer, coverFile: File?) {
        val uriFact = uriFactoryPtr ?: return
        val streamStatics = streamStaticsPtr ?: return
        if (coverFile == null || !coverFile.isFile) return

        runCatching {
            val uriStr = coverFile.toURI().toASCIIString()
            val hUriStr = createHString(uriStr)
            val uriRef = PointerByReference()
            val hrUri = method(uriFact, SLOT_URI_FACT_CREATE_URI).invokeInt(arrayOf(uriFact, hUriStr, uriRef))
            deleteHString(hUriStr)
            if (hrUri == 0 && uriRef.value != null) {
                val uri = uriRef.value
                val streamRef = PointerByReference()
                val hrStream = method(streamStatics, SLOT_STREAM_STATICS_CREATE_FROM_URI).invokeInt(arrayOf(
                    streamStatics, uri, streamRef,
                ))
                release(uri)
                if (hrStream == 0 && streamRef.value != null) {
                    val stream = streamRef.value
                    method(updater, SLOT_UPDATER_PUT_THUMBNAIL).invokeInt(arrayOf(updater, stream))
                    release(stream)
                }
            }
        }
    }

    private fun updateTimeline(durationMs: Long, positionMs: Long) {
        val smtc2 = smtc2Ptr ?: return
        runCatching {
            val hTimelineClass = createHString("Windows.Media.SystemMediaTransportControlsTimelineProperties")
            val propsRef = PointerByReference()
            val hrAct = combase("RoActivateInstance").invokeInt(arrayOf(hTimelineClass, propsRef))
            deleteHString(hTimelineClass)
            if (hrAct == 0 && propsRef.value != null) {
                val props = propsRef.value
                val dur100ns = durationMs.coerceAtLeast(0L) * 10_000L
                val pos100ns = positionMs.coerceIn(0L, durationMs) * 10_000L

                call(props, SLOT_TIMELINE_PUT_START, 0L)
                call(props, SLOT_TIMELINE_PUT_END, dur100ns)
                call(props, SLOT_TIMELINE_PUT_MIN_SEEK, 0L)
                call(props, SLOT_TIMELINE_PUT_MAX_SEEK, dur100ns)
                call(props, SLOT_TIMELINE_PUT_POSITION, pos100ns)

                method(smtc2, SLOT_SMTC2_UPDATE_TIMELINE).invokeInt(arrayOf(smtc2, props))
                release(props)
            }
        }
    }

    override fun stop() {
        stopRequested = true
        started = false
        coverScope.cancel()

        runCatching {
            smtcExecutor.execute {
                smtcPtr?.let { smtc ->
                    if (buttonEventToken != 0L) {
                        method(smtc, SLOT_SMTC_REMOVE_BUTTON_PRESSED).invokeInt(arrayOf(smtc, buttonEventToken))
                        buttonEventToken = 0L
                    }
                    call(smtc, SLOT_SMTC_PUT_IS_ENABLED, (0).toByte())
                }
                displayUpdaterPtr?.let { updater ->
                    call(updater, SLOT_UPDATER_CLEAR_ALL)
                    call(updater, SLOT_UPDATER_UPDATE)
                }

                release(musicProperties2Ptr)
                release(musicPropertiesPtr)
                release(displayUpdaterPtr)
                release(smtc2Ptr)
                release(smtcPtr)
                release(uriFactoryPtr)
                release(streamStaticsPtr)

                musicProperties2Ptr = null
                musicPropertiesPtr = null
                displayUpdaterPtr = null
                smtc2Ptr = null
                smtcPtr = null
                uriFactoryPtr = null
                streamStaticsPtr = null

                callbackVtbl = null
                callbackMemory = null

                resetPushedState()
                latestTrack = null
                coverCacheDir?.deleteRecursively()
                coverCacheDir = null
            }
        }
        smtcExecutor.shutdown()
    }

    private fun resetPushedState() {
        pushedTrackId = null
        pushedDurationMs = -1L
        pushedPlayingStatus = -1
        pushedPositionMs = -1L
        lastPositionPushAt = 0L
    }

    private fun coverFile(trackId: String, url: String?): File? {
        val dir = ensureCoverCacheDir() ?: return null
        if (url == null) {
            return LocalArtwork.extract(trackId, dir)
        }
        if (url.startsWith("file:")) {
            return runCatching { File(java.net.URI(url)) }.getOrNull()
        }
        if (!url.startsWith("http")) return null
        val cached = File(dir, "${sha256(url)}.img")
        if (cached.isFile) return cached
        requestRemoteCover(trackId, url, cached)
        return null
    }

    private fun requestRemoteCover(trackId: String, url: String, target: File) {
        coverScope.launch {
            val downloaded = runCatching { downloadCover(url, target); target }
                .onFailure { runCatching { partialOf(target).delete() } }
                .getOrNull() ?: return@launch
            applyRemoteCover(trackId, downloaded)
        }
    }

    private fun applyRemoteCover(trackId: String, cover: File) {
        try {
            smtcExecutor.execute {
                if (pushedTrackId != trackId) return@execute
                val updater = displayUpdaterPtr ?: return@execute
                applyCoverThumbnail(updater, cover)
                call(updater, SLOT_UPDATER_UPDATE)
            }
        } catch (_: RejectedExecutionException) {
            // stop() 后到达
        }
    }

    private fun downloadCover(url: String, target: File) {
        val connection = (java.net.URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 10_000
            instanceFollowRedirects = true
        }
        if (connection.responseCode !in 200..299) {
            error("cover download HTTP ${connection.responseCode}")
        }
        val partial = partialOf(target)
        connection.inputStream.use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_COVER_BYTES) error("cover too large: $total bytes")
                    output.write(buffer, 0, read)
                }
            }
        }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun ensureCoverCacheDir(): File? {
        coverCacheDir?.let { return it }
        val created = runCatching { Files.createTempDirectory("cpplayer-cover-").toFile() }.getOrNull() ?: return null
        coverCacheDir = created
        return created
    }

    private fun partialOf(target: File) = File(target.parentFile, "${target.name}.part")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // COM 结构体与回调
    class ComVtbl : Structure() {
        @JvmField var QueryInterface: QueryInterfaceCallback? = null
        @JvmField var AddRef: AddRefCallback? = null
        @JvmField var Release: ReleaseCallback? = null
        @JvmField var Invoke: InvokeCallback? = null

        override fun getFieldOrder(): List<String> = listOf("QueryInterface", "AddRef", "Release", "Invoke")
    }

    fun interface QueryInterfaceCallback : Callback {
        fun invoke(thisPtr: Pointer?, riid: Pointer?, ppv: PointerByReference): Int
    }

    fun interface AddRefCallback : Callback {
        fun invoke(thisPtr: Pointer?): Int
    }

    fun interface ReleaseCallback : Callback {
        fun invoke(thisPtr: Pointer?): Int
    }

    fun interface InvokeCallback : Callback {
        fun invoke(thisPtr: Pointer?, sender: Pointer?, args: Pointer?): Int
    }

    companion object {
        @Volatile
        var primaryWindow: java.awt.Window? = null

        fun create(controller: PlaybackController): PlatformMediaControlsInstance =
            WindowsSmtcControls(controller)

        fun resolveHwnd(): Pointer? {
            primaryWindow?.let { win ->
                val ptr = Native.getWindowPointer(win)
                if (ptr != null && Pointer.nativeValue(ptr) != 0L) {
                    return ptr
                }
            }
            val hwndLong = findWindowByPid()
            if (hwndLong != 0L) {
                return Pointer(hwndLong)
            }
            return null
        }

        private fun findWindowByPid(): Long {
            val getTopWindow = Function.getFunction("user32", "GetTopWindow")
            val getWindow = Function.getFunction("user32", "GetWindow")
            val isWindowVisible = Function.getFunction("user32", "IsWindowVisible")
            val getWindowThreadProcessId = Function.getFunction("user32", "GetWindowThreadProcessId")

            val myPid = ProcessHandle.current().pid().toInt()
            val pidMem = Memory(4)
            var hwnd = (getTopWindow.invoke(Long::class.java, arrayOf(0L)) as? Long) ?: 0L
            while (hwnd != 0L) {
                val visible = ((isWindowVisible.invoke(Int::class.java, arrayOf(hwnd)) as? Int) ?: 0) != 0
                if (visible) {
                    getWindowThreadProcessId.invoke(arrayOf(hwnd, pidMem))
                    if (pidMem.getInt(0) == myPid) {
                        return hwnd
                    }
                }
                hwnd = (getWindow.invoke(Long::class.java, arrayOf(hwnd, 2 /* GW_HWNDNEXT */)) as? Long) ?: 0L
            }
            return 0L
        }

        private fun combase(name: String): Function = Function.getFunction("combase", name)

        private fun createHString(str: String): Pointer {
            val ref = PointerByReference()
            val hr = combase("WindowsCreateString").invokeInt(arrayOf(
                WString(str), str.length, ref,
            ))
            check(hr == 0) { "WindowsCreateString failed: 0x${Integer.toHexString(hr)}" }
            return ref.value
        }

        private fun deleteHString(hstring: Pointer?) {
            if (hstring != null) {
                combase("WindowsDeleteString").invokeInt(arrayOf(hstring))
            }
        }

        private fun method(obj: Pointer, slot: Int): Function {
            val vtbl = obj.getPointer(0)
            val funcPtr = vtbl.getPointer(slot.toLong() * Native.POINTER_SIZE)
            return Function.getFunction(funcPtr)
        }

        private fun call(obj: Pointer, slot: Int, vararg args: Any?): Int =
            method(obj, slot).invokeInt(arrayOf(obj, *args))

        private fun queryInterface(obj: Pointer, riid: Pointer, ppv: PointerByReference): Int =
            method(obj, 0).invokeInt(arrayOf(obj, riid, ppv))

        private fun release(obj: Pointer?) {
            if (obj != null && Pointer.nativeValue(obj) != 0L) {
                method(obj, 2).invokeInt(arrayOf(obj))
            }
        }

        // 常量 GUIDs
        private const val IID_INTEROP = "{ddb0472d-c911-4a1f-86d9-dc3d71a95f5a}"
        private const val IID_SMTC = "{99fa3ff4-1742-42a6-902e-087d41f965ec}"
        private const val IID_SMTC2 = "{ea98d2f6-7f3c-4af2-a586-72889808efb1}"
        private const val IID_MUSIC_PROPERTIES2 = "{00368462-97d3-44b9-b00f-008afcefaf18}"
        private const val IID_URI_FACTORY = "{44a9796f-723e-4fdf-a218-033e75b0c084}"
        private const val IID_STREAM_REF_STATICS = "{857309dc-3fbf-4e7d-986f-ef3b1a07a964}"

        private const val IID_IUNKNOWN = "00000000-0000-0000-c000-000000000046"
        private const val IID_AGILE_OBJECT = "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"
        private const val IID_BUTTON_EVENT_HANDLER = "0557e996-7b23-5bae-aa81-ea0d671143a4"

        // vtable 槽位
        private const val SLOT_INTEROP_GET_FOR_WINDOW = 6
        private const val SLOT_SMTC_PUT_PLAYBACK_STATUS = 7
        private const val SLOT_SMTC_GET_DISPLAY_UPDATER = 8
        private const val SLOT_SMTC_PUT_IS_ENABLED = 11
        private const val SLOT_SMTC_PUT_IS_PLAY_ENABLED = 13
        private const val SLOT_SMTC_PUT_IS_PAUSE_ENABLED = 17
        private const val SLOT_SMTC_PUT_IS_PREV_ENABLED = 25
        private const val SLOT_SMTC_PUT_IS_NEXT_ENABLED = 27
        private const val SLOT_SMTC_ADD_BUTTON_PRESSED = 32
        private const val SLOT_SMTC_REMOVE_BUTTON_PRESSED = 33

        private const val SLOT_UPDATER_PUT_TYPE = 7
        private const val SLOT_UPDATER_PUT_THUMBNAIL = 11
        private const val SLOT_UPDATER_GET_MUSIC_PROPS = 12
        private const val SLOT_UPDATER_CLEAR_ALL = 16
        private const val SLOT_UPDATER_UPDATE = 17

        private const val SLOT_MUSIC_PUT_TITLE = 7
        private const val SLOT_MUSIC_PUT_ARTIST = 11
        private const val SLOT_MUSIC2_PUT_ALBUM_TITLE = 7

        private const val SLOT_SMTC2_UPDATE_TIMELINE = 12
        private const val SLOT_TIMELINE_PUT_START = 7
        private const val SLOT_TIMELINE_PUT_END = 9
        private const val SLOT_TIMELINE_PUT_MIN_SEEK = 11
        private const val SLOT_TIMELINE_PUT_MAX_SEEK = 13
        private const val SLOT_TIMELINE_PUT_POSITION = 15

        private const val SLOT_URI_FACT_CREATE_URI = 6
        private const val SLOT_STREAM_STATICS_CREATE_FROM_URI = 7
        private const val SLOT_EVENT_ARGS_GET_BUTTON = 6

        // 枚举常量
        private const val RO_INIT_MULTITHREADED = 1
        private const val E_NOINTERFACE = -0x7fffbffe // 0x80004002

        private const val MEDIA_TYPE_MUSIC = 1

        private const val STATUS_CLOSED = 0
        private const val STATUS_CHANGING = 1
        private const val STATUS_STOPPED = 2
        private const val STATUS_PLAYING = 3
        private const val STATUS_PAUSED = 4

        private const val BUTTON_PLAY = 0
        private const val BUTTON_PAUSE = 1
        private const val BUTTON_STOP = 2
        private const val BUTTON_NEXT = 6
        private const val BUTTON_PREVIOUS = 7

        private const val POSITION_PUSH_INTERVAL_MS = 1_000L
        private const val POSITION_JUMP_TOLERANCE_MS = 3_000L
        private const val MAX_COVER_BYTES = 20L * 1024 * 1024
    }
}
