package cp.player.app.platform

import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState
import cp.player.core.util.VirtualThreadDispatcher
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Windows 原生 System Media Transport Controls (SMTC) 桥接。
 *
 * ## 架构与实现
 *
 * 全程基于 JDK 22+ Foreign Function & Memory API (FFM) 驱动 WinRT COM 接口，
 * 无需 JNA、JMTC 或任何第三方动态链接库，直接与 Windows 系统的 `combase.dll` / `Windows.Media.dll` 通信：
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

    private val coverScope = CoroutineScope(SupervisorJob() + VirtualThreadDispatcher.dispatcher)
    private var coverCacheDir: File? = null
    private var latestTrack: TrackSummary? = null

    // FFM Arena 与 WinRT COM 句柄
    private var rootArena: Arena? = null
    private var callbackArena: Arena? = null

    private var smtcPtr: MemorySegment? = null
    private var smtc2Ptr: MemorySegment? = null
    private var displayUpdaterPtr: MemorySegment? = null
    private var musicPropertiesPtr: MemorySegment? = null
    private var musicProperties2Ptr: MemorySegment? = null
    private var uriFactoryPtr: MemorySegment? = null
    private var streamStaticsPtr: MemorySegment? = null

    private var callbackObj: MemorySegment? = null
    private var buttonEventToken: Long = 0L

    // 推送去抖
    private var pushedTrackId: String? = null
    private var pushedDurationMs = -1L
    private var pushedPlayingStatus = -1
    private var pushedPositionMs = -1L
    private var lastPositionPushAt = 0L

    private val iidIUnknownBytes = ByteArray(16)
    private val iidAgileObjectBytes = ByteArray(16)
    private val iidButtonHandlerBytes = ByteArray(16)

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

            val arena = Arena.ofShared()
            rootArena = arena

            // 预解析常用 GUID 字节
            parseGuid(IID_IUNKNOWN, iidIUnknownBytes, arena)
            parseGuid(IID_AGILE_OBJECT, iidAgileObjectBytes, arena)
            parseGuid(IID_BUTTON_EVENT_HANDLER, iidButtonHandlerBytes, arena)

            // 2. 初始化 COM 套间 (MTA)
            val combaseRoInit = downcallCombase("RoInitialize", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), arena)
            combaseRoInit.invokeWithArguments(RO_INIT_MULTITHREADED)

            // 3. 解析主窗口 HWND
            var hwndLong = resolveHwndLong(arena)
            var attempts = 0
            while (hwndLong == 0L && attempts < 20) {
                Thread.sleep(100)
                hwndLong = resolveHwndLong(arena)
                attempts++
            }
            if (hwndLong == 0L) {
                System.err.println("[WindowsSMTC] 未能获取窗口 HWND，放弃初始化")
                arena.close()
                rootArena = null
                return@runCatching
            }

            // 4. 获取 ISystemMediaTransportControlsInterop 工厂
            val hClassName = createHString("Windows.Media.SystemMediaTransportControls", arena)
            val iidInterop = arena.allocate(16)
            parseGuid(IID_INTEROP, iidInterop)
            val interopRef = arena.allocate(ValueLayout.ADDRESS)

            val roGetActivationFactory = downcallCombase(
                "RoGetActivationFactory",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                arena,
            )
            val hrFact = roGetActivationFactory.invokeWithArguments(hClassName, iidInterop, interopRef) as Int
            deleteHString(hClassName, arena)
            check(hrFact == 0) { "RoGetActivationFactory(SMTC) failed: 0x${Integer.toHexString(hrFact)}" }
            val interop = interopRef.get(ValueLayout.ADDRESS, 0)

            // 5. GetForWindow(hwnd) -> ISystemMediaTransportControls
            val iidSmtc = arena.allocate(16)
            parseGuid(IID_SMTC, iidSmtc)
            val smtcRef = arena.allocate(ValueLayout.ADDRESS)
            val hrGet = callCom(interop, SLOT_INTEROP_GET_FOR_WINDOW, hwndLong, iidSmtc, smtcRef)
            release(interop)
            check(hrGet == 0) { "GetForWindow failed: 0x${Integer.toHexString(hrGet)}" }
            val smtc = smtcRef.get(ValueLayout.ADDRESS, 0)
            smtcPtr = smtc

            // 6. 激活控制按钮与状态
            callCom(smtc, SLOT_SMTC_PUT_IS_ENABLED, (1).toByte())
            callCom(smtc, SLOT_SMTC_PUT_IS_PLAY_ENABLED, (1).toByte())
            callCom(smtc, SLOT_SMTC_PUT_IS_PAUSE_ENABLED, (1).toByte())
            callCom(smtc, SLOT_SMTC_PUT_IS_NEXT_ENABLED, (1).toByte())
            callCom(smtc, SLOT_SMTC_PUT_IS_PREV_ENABLED, (1).toByte())

            // 7. 注册按钮回调
            registerButtonCallback(smtc)

            // 8. 获取 DisplayUpdater 与 MusicProperties
            val updaterRef = arena.allocate(ValueLayout.ADDRESS)
            val hrUpdater = callCom(smtc, SLOT_SMTC_GET_DISPLAY_UPDATER, updaterRef)
            if (hrUpdater == 0) {
                val updater = updaterRef.get(ValueLayout.ADDRESS, 0)
                if (isValidPtr(updater)) {
                    displayUpdaterPtr = updater
                    callCom(updater, SLOT_UPDATER_PUT_TYPE, MEDIA_TYPE_MUSIC)

                    val musicRef = arena.allocate(ValueLayout.ADDRESS)
                    if (callCom(updater, SLOT_UPDATER_GET_MUSIC_PROPS, musicRef) == 0) {
                        val music = musicRef.get(ValueLayout.ADDRESS, 0)
                        if (isValidPtr(music)) {
                            musicPropertiesPtr = music

                            val iidMusic2 = arena.allocate(16)
                            parseGuid(IID_MUSIC_PROPERTIES2, iidMusic2)
                            val music2Ref = arena.allocate(ValueLayout.ADDRESS)
                            if (queryInterface(music, iidMusic2, music2Ref) == 0) {
                                val music2 = music2Ref.get(ValueLayout.ADDRESS, 0)
                                if (isValidPtr(music2)) musicProperties2Ptr = music2
                            }
                        }
                    }
                }
            }

            // 9. 获取 ISystemMediaTransportControls2 (用于 Timeline)
            val iidSmtc2 = arena.allocate(16)
            parseGuid(IID_SMTC2, iidSmtc2)
            val smtc2Ref = arena.allocate(ValueLayout.ADDRESS)
            if (queryInterface(smtc, iidSmtc2, smtc2Ref) == 0) {
                val smtc2 = smtc2Ref.get(ValueLayout.ADDRESS, 0)
                if (isValidPtr(smtc2)) smtc2Ptr = smtc2
            }

            // 10. 初始化封面 Uri / Stream 激活工厂
            initCoverFactories(arena, roGetActivationFactory)

            started = true
            println("[WindowsSMTC] 成功通过 FFM 初始化并绑定窗口 HWND=$hwndLong")
        }.onFailure {
            System.err.println("[WindowsSMTC] 初始化失败: ${it.stackTraceToString()}")
        }
    }

    private fun initCoverFactories(arena: Arena, roGetActivationFactory: MethodHandle) {
        runCatching {
            val hUriClass = createHString("Windows.Foundation.Uri", arena)
            val iidUriFact = arena.allocate(16)
            parseGuid(IID_URI_FACTORY, iidUriFact)
            val uriFactRef = arena.allocate(ValueLayout.ADDRESS)
            if (roGetActivationFactory.invokeWithArguments(hUriClass, iidUriFact, uriFactRef) as Int == 0) {
                val uriFact = uriFactRef.get(ValueLayout.ADDRESS, 0)
                if (isValidPtr(uriFact)) uriFactoryPtr = uriFact
            }
            deleteHString(hUriClass, arena)

            val hStreamClass = createHString("Windows.Storage.Streams.RandomAccessStreamReference", arena)
            val iidStreamStatics = arena.allocate(16)
            parseGuid(IID_STREAM_REF_STATICS, iidStreamStatics)
            val streamStaticsRef = arena.allocate(ValueLayout.ADDRESS)
            if (roGetActivationFactory.invokeWithArguments(hStreamClass, iidStreamStatics, streamStaticsRef) as Int == 0) {
                val streamStatics = streamStaticsRef.get(ValueLayout.ADDRESS, 0)
                if (isValidPtr(streamStatics)) streamStaticsPtr = streamStatics
            }
            deleteHString(hStreamClass, arena)
        }
    }

    private fun registerButtonCallback(smtc: MemorySegment) {
        val arena = Arena.ofShared()
        callbackArena = arena

        val lookup = MethodHandles.lookup()
        val qiHandle = lookup.findSpecial(
            WindowsSmtcControls::class.java,
            "onQueryInterface",
            MethodType.methodType(Int::class.java, MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java),
            WindowsSmtcControls::class.java,
        ).bindTo(this)

        val addRefHandle = lookup.findSpecial(
            WindowsSmtcControls::class.java,
            "onAddRef",
            MethodType.methodType(Int::class.java, MemorySegment::class.java),
            WindowsSmtcControls::class.java,
        ).bindTo(this)

        val releaseHandle = lookup.findSpecial(
            WindowsSmtcControls::class.java,
            "onRelease",
            MethodType.methodType(Int::class.java, MemorySegment::class.java),
            WindowsSmtcControls::class.java,
        ).bindTo(this)

        val invokeHandle = lookup.findSpecial(
            WindowsSmtcControls::class.java,
            "onInvoke",
            MethodType.methodType(Int::class.java, MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java),
            WindowsSmtcControls::class.java,
        ).bindTo(this)

        val linker = Linker.nativeLinker()
        val qiStub = linker.upcallStub(
            qiHandle,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            arena,
        )
        val addRefStub = linker.upcallStub(
            addRefHandle,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            arena,
        )
        val releaseStub = linker.upcallStub(
            releaseHandle,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            arena,
        )
        val invokeStub = linker.upcallStub(
            invokeHandle,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            arena,
        )

        val vtbl = arena.allocate(ValueLayout.ADDRESS, 4)
        vtbl.setAtIndex(ValueLayout.ADDRESS, 0, qiStub)
        vtbl.setAtIndex(ValueLayout.ADDRESS, 1, addRefStub)
        vtbl.setAtIndex(ValueLayout.ADDRESS, 2, releaseStub)
        vtbl.setAtIndex(ValueLayout.ADDRESS, 3, invokeStub)

        val obj = arena.allocate(ValueLayout.ADDRESS)
        obj.set(ValueLayout.ADDRESS, 0, vtbl)
        callbackObj = obj

        val tokenRef = arena.allocate(ValueLayout.JAVA_LONG)
        val hr = callCom(smtc, SLOT_SMTC_ADD_BUTTON_PRESSED, obj, tokenRef)
        if (hr == 0) {
            buttonEventToken = tokenRef.get(ValueLayout.JAVA_LONG, 0)
        } else {
            System.err.println("[WindowsSMTC] add_ButtonPressed 失败: 0x${Integer.toHexString(hr)}")
        }
    }

    private fun onQueryInterface(thisPtr: MemorySegment, riid: MemorySegment, ppv: MemorySegment): Int {
        val bytes = riid.reinterpret(16).toArray(ValueLayout.JAVA_BYTE)
        if (bytes.contentEquals(iidIUnknownBytes) ||
            bytes.contentEquals(iidAgileObjectBytes) ||
            bytes.contentEquals(iidButtonHandlerBytes)
        ) {
            ppv.reinterpret(ValueLayout.ADDRESS.byteSize()).set(ValueLayout.ADDRESS, 0, callbackObj ?: thisPtr)
            return 0 // S_OK
        }
        ppv.reinterpret(ValueLayout.ADDRESS.byteSize()).set(ValueLayout.ADDRESS, 0, MemorySegment.NULL)
        return E_NOINTERFACE
    }

    private fun onAddRef(thisPtr: MemorySegment): Int = 1

    private fun onRelease(thisPtr: MemorySegment): Int = 1

    private fun onInvoke(thisPtr: MemorySegment, sender: MemorySegment, args: MemorySegment): Int {
        if (isValidPtr(args)) {
            Arena.ofConfined().use { tempArena ->
                val btnMem = tempArena.allocate(ValueLayout.JAVA_INT)
                if (callCom(args, SLOT_EVENT_ARGS_GET_BUTTON, btnMem) == 0) {
                    when (btnMem.get(ValueLayout.JAVA_INT, 0)) {
                        BUTTON_PLAY -> controller.resume()
                        BUTTON_PAUSE, BUTTON_STOP -> controller.pause()
                        BUTTON_NEXT -> controller.skipNext()
                        BUTTON_PREVIOUS -> controller.skipPrevious()
                    }
                }
            }
        }
        return 0 // S_OK
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
                    callCom(smtc, SLOT_SMTC_PUT_PLAYBACK_STATUS, STATUS_CLOSED)
                    callCom(updater, SLOT_UPDATER_CLEAR_ALL)
                    callCom(updater, SLOT_UPDATER_UPDATE)
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
            callCom(smtc, SLOT_SMTC_PUT_PLAYBACK_STATUS, playingStatus)
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
            callCom(updater, SLOT_UPDATER_UPDATE)
        }
    }

    private fun updateMetadata(track: TrackSummary, coverFile: File?) {
        val music = musicPropertiesPtr ?: return
        val updater = displayUpdaterPtr ?: return

        Arena.ofConfined().use { tempArena ->
            val hTitle = createHString(track.name.ifBlank { "CPPlayer" }, tempArena)
            callCom(music, SLOT_MUSIC_PUT_TITLE, hTitle)
            deleteHString(hTitle, tempArena)

            val hArtist = createHString(track.artist, tempArena)
            callCom(music, SLOT_MUSIC_PUT_ARTIST, hArtist)
            deleteHString(hArtist, tempArena)

            musicProperties2Ptr?.let { music2 ->
                val hAlbum = createHString(track.album.orEmpty(), tempArena)
                callCom(music2, SLOT_MUSIC2_PUT_ALBUM_TITLE, hAlbum)
                deleteHString(hAlbum, tempArena)
            }
        }

        applyCoverThumbnail(updater, coverFile)
    }

    private fun applyCoverThumbnail(updater: MemorySegment, coverFile: File?) {
        val uriFact = uriFactoryPtr ?: return
        val streamStatics = streamStaticsPtr ?: return
        if (coverFile == null || !coverFile.isFile) return

        runCatching {
            Arena.ofConfined().use { tempArena ->
                val uriStr = coverFile.toURI().toASCIIString()
                val hUriStr = createHString(uriStr, tempArena)
                val uriRef = tempArena.allocate(ValueLayout.ADDRESS)
                val hrUri = callCom(uriFact, SLOT_URI_FACT_CREATE_URI, hUriStr, uriRef)
                deleteHString(hUriStr, tempArena)
                if (hrUri == 0) {
                    val uri = uriRef.get(ValueLayout.ADDRESS, 0)
                    if (isValidPtr(uri)) {
                        val streamRef = tempArena.allocate(ValueLayout.ADDRESS)
                        val hrStream = callCom(streamStatics, SLOT_STREAM_STATICS_CREATE_FROM_URI, uri, streamRef)
                        release(uri)
                        if (hrStream == 0) {
                            val stream = streamRef.get(ValueLayout.ADDRESS, 0)
                            if (isValidPtr(stream)) {
                                callCom(updater, SLOT_UPDATER_PUT_THUMBNAIL, stream)
                                release(stream)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateTimeline(durationMs: Long, positionMs: Long) {
        val smtc2 = smtc2Ptr ?: return
        runCatching {
            Arena.ofConfined().use { tempArena ->
                val hTimelineClass = createHString("Windows.Media.SystemMediaTransportControlsTimelineProperties", tempArena)
                val propsRef = tempArena.allocate(ValueLayout.ADDRESS)
                val roActivateInstance = downcallCombase(
                    "RoActivateInstance",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    tempArena,
                )
                val hrAct = roActivateInstance.invokeWithArguments(hTimelineClass, propsRef) as Int
                deleteHString(hTimelineClass, tempArena)
                if (hrAct == 0) {
                    val props = propsRef.get(ValueLayout.ADDRESS, 0)
                    if (isValidPtr(props)) {
                        val dur100ns = durationMs.coerceAtLeast(0L) * 10_000L
                        val pos100ns = positionMs.coerceIn(0L, durationMs) * 10_000L

                        callCom(props, SLOT_TIMELINE_PUT_START, 0L)
                        callCom(props, SLOT_TIMELINE_PUT_END, dur100ns)
                        callCom(props, SLOT_TIMELINE_PUT_MIN_SEEK, 0L)
                        callCom(props, SLOT_TIMELINE_PUT_MAX_SEEK, dur100ns)
                        callCom(props, SLOT_TIMELINE_PUT_POSITION, pos100ns)

                        callCom(smtc2, SLOT_SMTC2_UPDATE_TIMELINE, props)
                        release(props)
                    }
                }
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
                        callCom(smtc, SLOT_SMTC_REMOVE_BUTTON_PRESSED, buttonEventToken)
                        buttonEventToken = 0L
                    }
                    callCom(smtc, SLOT_SMTC_PUT_IS_ENABLED, (0).toByte())
                }
                displayUpdaterPtr?.let { updater ->
                    callCom(updater, SLOT_UPDATER_CLEAR_ALL)
                    callCom(updater, SLOT_UPDATER_UPDATE)
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

                callbackObj = null
                runCatching { callbackArena?.close() }
                callbackArena = null
                runCatching { rootArena?.close() }
                rootArena = null

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
                callCom(updater, SLOT_UPDATER_UPDATE)
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

    companion object {
        @Volatile
        var primaryWindow: java.awt.Window? = null

        fun create(controller: PlaybackController): PlatformMediaControlsInstance =
            WindowsSmtcControls(controller)

        private fun isValidPtr(ptr: MemorySegment?): Boolean =
            ptr != null && ptr != MemorySegment.NULL && ptr.address() != 0L

        private fun resolveHwndLong(arena: Arena): Long {
            return findWindowByPid(arena)
        }

        private fun findWindowByPid(arena: Arena): Long {
            val linker = Linker.nativeLinker()
            val user32 = SymbolLookup.libraryLookup("user32", arena)
            val getTopWindow = linker.downcallHandle(user32.find("GetTopWindow").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG))
            val getWindow = linker.downcallHandle(user32.find("GetWindow").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT))
            val isWindowVisible = linker.downcallHandle(user32.find("IsWindowVisible").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG))
            val getWindowThreadProcessId = linker.downcallHandle(user32.find("GetWindowThreadProcessId").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))

            val myPid = ProcessHandle.current().pid().toInt()
            val pidMem = arena.allocate(ValueLayout.JAVA_INT)
            var hwnd = getTopWindow.invokeWithArguments(0L) as Long
            while (hwnd != 0L) {
                val visible = (isWindowVisible.invokeWithArguments(hwnd) as Int) != 0
                if (visible) {
                    getWindowThreadProcessId.invokeWithArguments(hwnd, pidMem)
                    if (pidMem.get(ValueLayout.JAVA_INT, 0) == myPid) {
                        return hwnd
                    }
                }
                hwnd = getWindow.invokeWithArguments(hwnd, 2 /* GW_HWNDNEXT */) as Long
            }
            return 0L
        }

        private fun downcallCombase(name: String, desc: FunctionDescriptor, arena: Arena): MethodHandle {
            val linker = Linker.nativeLinker()
            val combase = SymbolLookup.libraryLookup("combase", arena)
            return linker.downcallHandle(combase.find(name).orElseThrow(), desc)
        }

        private fun parseGuid(guidStr: String, target: MemorySegment) {
            Arena.ofConfined().use { temp ->
                val ole32 = SymbolLookup.libraryLookup("ole32", temp)
                val iidFromString = Linker.nativeLinker().downcallHandle(
                    ole32.find("IIDFromString").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                )
                val wide = temp.allocateFrom(guidStr, StandardCharsets.UTF_16LE)
                iidFromString.invokeWithArguments(wide, target)
            }
        }

        private fun parseGuid(guidStr: String, outBytes: ByteArray, arena: Arena) {
            val mem = arena.allocate(16)
            parseGuid(guidStr, mem)
            val bytes = mem.toArray(ValueLayout.JAVA_BYTE)
            System.arraycopy(bytes, 0, outBytes, 0, 16)
        }

        private fun createHString(str: String, arena: Arena): MemorySegment {
            val linker = Linker.nativeLinker()
            val combase = SymbolLookup.libraryLookup("combase", arena)
            val createStr = linker.downcallHandle(
                combase.find("WindowsCreateString").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            )
            val wideStr = arena.allocateFrom(str, StandardCharsets.UTF_16LE)
            val outRef = arena.allocate(ValueLayout.ADDRESS)
            val hr = createStr.invokeWithArguments(wideStr, str.length, outRef) as Int
            check(hr == 0) { "WindowsCreateString failed: 0x${Integer.toHexString(hr)}" }
            return outRef.get(ValueLayout.ADDRESS, 0)
        }

        private fun deleteHString(hstr: MemorySegment?, arena: Arena) {
            if (isValidPtr(hstr)) {
                val linker = Linker.nativeLinker()
                val combase = SymbolLookup.libraryLookup("combase", arena)
                val delStr = linker.downcallHandle(
                    combase.find("WindowsDeleteString").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                )
                delStr.invokeWithArguments(hstr!!)
            }
        }

        private fun callCom(iface: MemorySegment, slot: Int, vararg args: Any): Int {
            val vtable = iface.reinterpret(ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, 0)
                .reinterpret((slot + 1).toLong() * ValueLayout.ADDRESS.byteSize())
            val funcPtr = vtable.get(ValueLayout.ADDRESS, slot.toLong() * ValueLayout.ADDRESS.byteSize())

            val paramLayouts = ArrayList<MemoryLayout>(args.size + 1)
            paramLayouts.add(ValueLayout.ADDRESS) // this
            for (arg in args) {
                when (arg) {
                    is MemorySegment -> paramLayouts.add(ValueLayout.ADDRESS)
                    is Int -> paramLayouts.add(ValueLayout.JAVA_INT)
                    is Long -> paramLayouts.add(ValueLayout.JAVA_LONG)
                    is Short -> paramLayouts.add(ValueLayout.JAVA_SHORT)
                    is Byte -> paramLayouts.add(ValueLayout.JAVA_BYTE)
                    else -> error("Unsupported COM arg: ${arg::class}")
                }
            }

            val desc = FunctionDescriptor.of(ValueLayout.JAVA_INT, *paramLayouts.toTypedArray())
            val handle = Linker.nativeLinker().downcallHandle(funcPtr, desc)
            val invokeArgs = arrayOfNulls<Any>(args.size + 1)
            invokeArgs[0] = iface
            args.copyInto(invokeArgs, 1)
            return handle.invokeWithArguments(*invokeArgs) as Int
        }

        private fun queryInterface(obj: MemorySegment, riid: MemorySegment, ppv: MemorySegment): Int =
            callCom(obj, 0 /* QueryInterface */, riid, ppv)

        private fun release(obj: MemorySegment?) {
            if (isValidPtr(obj)) {
                callCom(obj!!, 2 /* Release */)
            }
        }

        // 常量 GUIDs
        private const val IID_INTEROP = "{ddb0472d-c911-4a1f-86d9-dc3d71a95f5a}"
        private const val IID_SMTC = "{99fa3ff4-1742-42a6-902e-087d41f965ec}"
        private const val IID_SMTC2 = "{ea98d2f6-7f3c-4af2-a586-72889808efb1}"
        private const val IID_MUSIC_PROPERTIES2 = "{00368462-97d3-44b9-b00f-008afcefaf18}"
        private const val IID_URI_FACTORY = "{44a9796f-723e-4fdf-a218-033e75b0c084}"
        private const val IID_STREAM_REF_STATICS = "{857309dc-3fbf-4e7d-986f-ef3b1a07a964}"

        private const val IID_IUNKNOWN = "{00000000-0000-0000-c000-000000000046}"
        private const val IID_AGILE_OBJECT = "{94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90}"
        private const val IID_BUTTON_EVENT_HANDLER = "{0557e996-7b23-5bae-aa81-ea0d671143a4}"

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
