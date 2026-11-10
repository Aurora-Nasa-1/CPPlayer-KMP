package cp.player.app.platform

import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState
import cp.player.core.util.VirtualThreadDispatcher
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
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
 * Linux 原生 MPRIS 2 (Media Player Remote Interfacing Specification) 媒体播控。
 *
 * 全程基于 JDK 22+ Foreign Function & Memory API (FFM) 动态链接 `libdbus-1.so.3`，
 * 无需 JNA、JMTC 或外部 Java D-Bus 依赖库。
 */
internal class LinuxMprisControls private constructor(
    private val controller: PlaybackController,
) : PlatformMediaControlsInstance {

    @Volatile
    private var started = false

    @Volatile
    private var stopRequested = false

    private val mprisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cpplayer-mpris-linux").apply { isDaemon = true }
    }

    private val coverScope = CoroutineScope(SupervisorJob() + VirtualThreadDispatcher.dispatcher)
    private var coverCacheDir: File? = null
    private var latestTrack: TrackSummary? = null

    private var connection: MemorySegment? = null
    private var rootArena: Arena? = null

    // D-Bus C API 方法句柄
    private var dbusErrorInit: MethodHandle? = null
    private var dbusErrorFree: MethodHandle? = null
    private var dbusBusGetPrivate: MethodHandle? = null
    private var dbusBusRequestName: MethodHandle? = null
    private var dbusBusReleaseName: MethodHandle? = null
    private var dbusConnReadWrite: MethodHandle? = null
    private var dbusConnPopMsg: MethodHandle? = null
    private var dbusConnSend: MethodHandle? = null
    private var dbusConnFlush: MethodHandle? = null
    private var dbusConnClose: MethodHandle? = null
    private var dbusConnUnref: MethodHandle? = null

    private var dbusMsgGetType: MethodHandle? = null
    private var dbusMsgGetInterface: MethodHandle? = null
    private var dbusMsgGetMember: MethodHandle? = null
    private var dbusMsgGetPath: MethodHandle? = null
    private var dbusMsgNewMethodReturn: MethodHandle? = null
    private var dbusMsgNewSignal: MethodHandle? = null
    private var dbusMsgUnref: MethodHandle? = null

    private var dbusIterInit: MethodHandle? = null
    private var dbusIterInitAppend: MethodHandle? = null
    private var dbusIterAppendBasic: MethodHandle? = null
    private var dbusIterOpenContainer: MethodHandle? = null
    private var dbusIterCloseContainer: MethodHandle? = null
    private var dbusIterGetBasic: MethodHandle? = null
    private var dbusIterNext: MethodHandle? = null

    // 状态缓存与去抖
    private var pushedTrackId: String? = null
    private var pushedDurationMs = -1L
    private var pushedPlayingStatus = "Paused"
    private var pushedPositionMs = -1L
    private var lastPositionPushAt = 0L

    override fun start() {
        if (started || mprisExecutor.isShutdown) return
        stopRequested = false
        mprisExecutor.execute {
            if (started || stopRequested) return@execute
            initDbus()
        }
    }

    private fun initDbus() {
        runCatching {
            val arena = Arena.ofShared()
            rootArena = arena
            val linker = Linker.nativeLinker()

            val libLookup = runCatching { SymbolLookup.libraryLookup("libdbus-1.so.3", arena) }
                .recoverCatching { SymbolLookup.libraryLookup("libdbus-1.so", arena) }
                .recoverCatching { SymbolLookup.libraryLookup("libdbus-1", arena) }
                .getOrNull()

            if (libLookup == null) {
                System.err.println("[LinuxMPRIS] 未找到 libdbus-1 动态库，跳过 MPRIS 播控初始化")
                arena.close()
                rootArena = null
                return@runCatching
            }

            // 初始化函数句柄
            dbusErrorInit = downcall(linker, libLookup, "dbus_error_init", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
            dbusErrorFree = downcall(linker, libLookup, "dbus_error_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
            dbusBusGetPrivate = downcall(linker, libLookup, "dbus_bus_get_private", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
            dbusBusRequestName = downcall(linker, libLookup, "dbus_bus_request_name", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
            dbusBusReleaseName = downcall(linker, libLookup, "dbus_bus_release_name", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusConnReadWrite = downcall(linker, libLookup, "dbus_connection_read_write", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
            dbusConnPopMsg = downcall(linker, libLookup, "dbus_connection_pop_message", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusConnSend = downcall(linker, libLookup, "dbus_connection_send", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusConnFlush = downcall(linker, libLookup, "dbus_connection_flush", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
            dbusConnClose = downcall(linker, libLookup, "dbus_connection_close", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
            dbusConnUnref = downcall(linker, libLookup, "dbus_connection_unref", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))

            dbusMsgGetType = downcall(linker, libLookup, "dbus_message_get_type", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
            dbusMsgGetInterface = downcall(linker, libLookup, "dbus_message_get_interface", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusMsgGetMember = downcall(linker, libLookup, "dbus_message_get_member", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusMsgGetPath = downcall(linker, libLookup, "dbus_message_get_path", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusMsgNewMethodReturn = downcall(linker, libLookup, "dbus_message_new_method_return", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusMsgNewSignal = downcall(linker, libLookup, "dbus_message_new_signal", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusMsgUnref = downcall(linker, libLookup, "dbus_message_unref", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))

            dbusIterInit = downcall(linker, libLookup, "dbus_message_iter_init", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusIterInitAppend = downcall(linker, libLookup, "dbus_message_iter_init_append", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusIterAppendBasic = downcall(linker, libLookup, "dbus_message_iter_append_basic", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
            dbusIterOpenContainer = downcall(linker, libLookup, "dbus_message_iter_open_container", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusIterCloseContainer = downcall(linker, libLookup, "dbus_message_iter_close_container", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusIterGetBasic = downcall(linker, libLookup, "dbus_message_iter_get_basic", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            dbusIterNext = downcall(linker, libLookup, "dbus_message_iter_next", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))

            // 连接 Session Bus
            val err = arena.allocate(64)
            dbusErrorInit?.invokeWithArguments(err)

            val conn = dbusBusGetPrivate?.invokeWithArguments(DBUS_BUS_SESSION, err) as? MemorySegment
            if (conn == null || conn == MemorySegment.NULL || conn.address() == 0L) {
                System.err.println("[LinuxMPRIS] 无法连接到 D-Bus Session 总线")
                dbusErrorFree?.invokeWithArguments(err)
                arena.close()
                rootArena = null
                return@runCatching
            }
            connection = conn

            // 请求 MPRIS 总线名
            val busName = arena.allocateFrom("org.mpris.MediaPlayer2.cpplayer", StandardCharsets.UTF_8)
            val reqCode = dbusBusRequestName?.invokeWithArguments(conn, busName, DBUS_NAME_FLAG_REPLACE_EXISTING or DBUS_NAME_FLAG_DO_NOT_QUEUE, err) as? Int ?: 0
            if (reqCode != 1 && reqCode != 4) { // DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER or ALREADY_OWNER
                System.err.println("[LinuxMPRIS] 请求 D-Bus 接口名失败: code=$reqCode")
            }

            started = true
            println("[LinuxMPRIS] 成功通过 FFM 初始化 MPRIS 2 播控服务")

            // 启动消息循环
            startMessageLoop(conn)
        }.onFailure {
            System.err.println("[LinuxMPRIS] 初始化失败: ${it.stackTraceToString()}")
        }
    }

    private fun startMessageLoop(conn: MemorySegment) {
        val loopThread = Thread({
            val iterArena = Arena.ofShared()
            while (started && !stopRequested) {
                try {
                    dbusConnReadWrite?.invokeWithArguments(conn, 100)
                    while (true) {
                        val msg = dbusConnPopMsg?.invokeWithArguments(conn) as? MemorySegment ?: break
                        if (msg == MemorySegment.NULL || msg.address() == 0L) break
                        handleIncomingMessage(conn, msg)
                        dbusMsgUnref?.invokeWithArguments(msg)
                    }
                } catch (_: Throwable) {
                    break
                }
            }
            iterArena.close()
        }, "cpplayer-mpris-loop").apply { isDaemon = true }
        loopThread.start()
    }

    private fun handleIncomingMessage(conn: MemorySegment, msg: MemorySegment) {
        val msgType = dbusMsgGetType?.invokeWithArguments(msg) as? Int ?: return
        if (msgType != DBUS_MESSAGE_TYPE_METHOD_CALL) return

        val ifaceSeg = dbusMsgGetInterface?.invokeWithArguments(msg) as? MemorySegment
        val memberSeg = dbusMsgGetMember?.invokeWithArguments(msg) as? MemorySegment
        val iface = if (ifaceSeg != null && ifaceSeg != MemorySegment.NULL && ifaceSeg.address() != 0L) {
            ifaceSeg.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8)
        } else ""
        val member = if (memberSeg != null && memberSeg != MemorySegment.NULL && memberSeg.address() != 0L) {
            memberSeg.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8)
        } else ""

        when (iface) {
            "org.freedesktop.DBus.Introspectable" -> {
                if (member == "Introspect") replyIntrospect(conn, msg)
            }
            "org.freedesktop.DBus.Properties" -> {
                when (member) {
                    "GetAll" -> replyGetAllProperties(conn, msg)
                    "Get" -> replyGetProperty(conn, msg)
                    else -> replyEmpty(conn, msg)
                }
            }
            "org.mpris.MediaPlayer2" -> {
                when (member) {
                    "Raise", "Quit" -> replyEmpty(conn, msg)
                }
            }
            "org.mpris.MediaPlayer2.Player" -> {
                when (member) {
                    "PlayPause" -> {
                        if (controller.state.value.isPlaying) controller.pause() else controller.resume()
                        replyEmpty(conn, msg)
                    }
                    "Play" -> { controller.resume(); replyEmpty(conn, msg) }
                    "Pause", "Stop" -> { controller.pause(); replyEmpty(conn, msg) }
                    "Next" -> { controller.skipNext(); replyEmpty(conn, msg) }
                    "Previous" -> { controller.skipPrevious(); replyEmpty(conn, msg) }
                    "Seek" -> { handleSeek(msg); replyEmpty(conn, msg) }
                    "SetPosition" -> { handleSetPosition(msg); replyEmpty(conn, msg) }
                    else -> replyEmpty(conn, msg)
                }
            }
        }
    }

    private fun replyEmpty(conn: MemorySegment, msg: MemorySegment) {
        val reply = dbusMsgNewMethodReturn?.invokeWithArguments(msg) as? MemorySegment ?: return
        if (reply != MemorySegment.NULL && reply.address() != 0L) {
            dbusConnSend?.invokeWithArguments(conn, reply, MemorySegment.NULL)
            dbusConnFlush?.invokeWithArguments(conn)
            dbusMsgUnref?.invokeWithArguments(reply)
        }
    }

    private fun replyIntrospect(conn: MemorySegment, msg: MemorySegment) {
        Arena.ofConfined().use { temp ->
            val reply = dbusMsgNewMethodReturn?.invokeWithArguments(msg) as? MemorySegment ?: return
            if (reply != MemorySegment.NULL && reply.address() != 0L) {
                val iter = temp.allocate(128)
                dbusIterInitAppend?.invokeWithArguments(reply, iter)
                appendString(iter, INTROSPECTION_XML, temp)
                dbusConnSend?.invokeWithArguments(conn, reply, MemorySegment.NULL)
                dbusConnFlush?.invokeWithArguments(conn)
                dbusMsgUnref?.invokeWithArguments(reply)
            }
        }
    }

    private fun replyGetAllProperties(conn: MemorySegment, msg: MemorySegment) {
        Arena.ofConfined().use { temp ->
            val reply = dbusMsgNewMethodReturn?.invokeWithArguments(msg) as? MemorySegment ?: return
            if (reply != MemorySegment.NULL && reply.address() != 0L) {
                val iter = temp.allocate(128)
                dbusIterInitAppend?.invokeWithArguments(reply, iter)

                val dictIter = temp.allocate(128)
                val sigDict = temp.allocateFrom("{sv}", StandardCharsets.UTF_8)
                dbusIterOpenContainer?.invokeWithArguments(iter, DBUS_TYPE_ARRAY, sigDict, dictIter)

                val track = latestTrack
                val status = pushedPlayingStatus
                val durUs = pushedDurationMs * 1000L
                val posUs = pushedPositionMs * 1000L

                appendDictEntryString(dictIter, "PlaybackStatus", status, temp)
                appendDictEntryString(dictIter, "LoopStatus", "None", temp)
                appendDictEntryDouble(dictIter, "Rate", 1.0, temp)
                appendDictEntryBoolean(dictIter, "Shuffle", false, temp)
                appendDictEntryDouble(dictIter, "Volume", 1.0, temp)
                appendDictEntryInt64(dictIter, "Position", posUs, temp)
                appendDictEntryBoolean(dictIter, "CanGoNext", true, temp)
                appendDictEntryBoolean(dictIter, "CanGoPrevious", true, temp)
                appendDictEntryBoolean(dictIter, "CanPlay", true, temp)
                appendDictEntryBoolean(dictIter, "CanPause", true, temp)
                appendDictEntryBoolean(dictIter, "CanSeek", true, temp)
                appendDictEntryBoolean(dictIter, "CanControl", true, temp)

                // Metadata a{sv}
                appendMetadataDict(dictIter, track, durUs, temp)

                dbusIterCloseContainer?.invokeWithArguments(iter, dictIter)

                dbusConnSend?.invokeWithArguments(conn, reply, MemorySegment.NULL)
                dbusConnFlush?.invokeWithArguments(conn)
                dbusMsgUnref?.invokeWithArguments(reply)
            }
        }
    }

    private fun replyGetProperty(conn: MemorySegment, msg: MemorySegment) {
        Arena.ofConfined().use { temp ->
            val iter = temp.allocate(128)
            if (dbusIterInit?.invokeWithArguments(msg, iter) as? Int != 0) {
                val ifaceRef = temp.allocate(ValueLayout.ADDRESS)
                dbusIterGetBasic?.invokeWithArguments(iter, ifaceRef)
                if (dbusIterNext?.invokeWithArguments(iter) as? Int != 0) {
                    val propRef = temp.allocate(ValueLayout.ADDRESS)
                    dbusIterGetBasic?.invokeWithArguments(iter, propRef)
                    val propName = propRef.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8)

                    val reply = dbusMsgNewMethodReturn?.invokeWithArguments(msg) as? MemorySegment
                    if (reply != null && reply != MemorySegment.NULL && reply.address() != 0L) {
                        val replyIter = temp.allocate(128)
                        dbusIterInitAppend?.invokeWithArguments(reply, replyIter)

                        when (propName) {
                            "PlaybackStatus" -> appendVariantString(replyIter, pushedPlayingStatus, temp)
                            "Position" -> appendVariantInt64(replyIter, pushedPositionMs * 1000L, temp)
                            "CanControl" -> appendVariantBoolean(replyIter, true, temp)
                            else -> appendVariantString(replyIter, "", temp)
                        }

                        dbusConnSend?.invokeWithArguments(conn, reply, MemorySegment.NULL)
                        dbusConnFlush?.invokeWithArguments(conn)
                        dbusMsgUnref?.invokeWithArguments(reply)
                    }
                }
            }
        }
    }

    private fun handleSeek(msg: MemorySegment) {
        Arena.ofConfined().use { temp ->
            val iter = temp.allocate(128)
            if (dbusIterInit?.invokeWithArguments(msg, iter) as? Int != 0) {
                val offsetRef = temp.allocate(ValueLayout.JAVA_LONG)
                dbusIterGetBasic?.invokeWithArguments(iter, offsetRef)
                val offsetUs = offsetRef.get(ValueLayout.JAVA_LONG, 0)
                val targetMs = (controller.state.value.positionMs + offsetUs / 1000L).coerceAtLeast(0L)
                controller.seekTo(targetMs)
            }
        }
    }

    private fun handleSetPosition(msg: MemorySegment) {
        Arena.ofConfined().use { temp ->
            val iter = temp.allocate(128)
            if (dbusIterInit?.invokeWithArguments(msg, iter) as? Int != 0) {
                if (dbusIterNext?.invokeWithArguments(iter) as? Int != 0) {
                    val posRef = temp.allocate(ValueLayout.JAVA_LONG)
                    dbusIterGetBasic?.invokeWithArguments(iter, posRef)
                    val posUs = posRef.get(ValueLayout.JAVA_LONG, 0)
                    controller.seekTo(posUs / 1000L)
                }
            }
        }
    }

    override fun update(state: PlaybackUiState) {
        try {
            mprisExecutor.execute { pushState(state) }
        } catch (_: RejectedExecutionException) {
            // stop() 残留
        }
    }

    private fun pushState(state: PlaybackUiState) {
        val conn = connection ?: return
        val track = state.currentTrack
        latestTrack = track

        val status = when {
            state.isPlaying -> "Playing"
            state.isBuffering -> "Playing"
            else -> "Paused"
        }
        val duration = state.durationMs.coerceAtLeast(track?.durationMs ?: 0L).coerceAtLeast(0L)
        val position = state.positionMs.coerceIn(0L, duration)
        val now = System.currentTimeMillis()

        var trackChanged = false
        if (pushedTrackId != track?.id) {
            pushedTrackId = track?.id
            pushedDurationMs = duration
            pushedPositionMs = position
            lastPositionPushAt = now
            trackChanged = true
        }

        var statusChanged = false
        if (pushedPlayingStatus != status) {
            pushedPlayingStatus = status
            statusChanged = true
        }

        val jumped = abs(position - pushedPositionMs) > POSITION_JUMP_TOLERANCE_MS
        if (jumped || now - lastPositionPushAt >= POSITION_PUSH_INTERVAL_MS) {
            pushedPositionMs = position
            lastPositionPushAt = now
        }

        if (trackChanged || statusChanged) {
            emitPropertiesChangedSignal(conn, track)
        }
    }

    private fun emitPropertiesChangedSignal(conn: MemorySegment, track: TrackSummary?) {
        Arena.ofConfined().use { temp ->
            val path = temp.allocateFrom("/org/mpris/MediaPlayer2", StandardCharsets.UTF_8)
            val iface = temp.allocateFrom("org.freedesktop.DBus.Properties", StandardCharsets.UTF_8)
            val signalName = temp.allocateFrom("PropertiesChanged", StandardCharsets.UTF_8)

            val signal = dbusMsgNewSignal?.invokeWithArguments(path, iface, signalName) as? MemorySegment ?: return
            if (signal == MemorySegment.NULL || signal.address() == 0L) return

            val iter = temp.allocate(128)
            dbusIterInitAppend?.invokeWithArguments(signal, iter)

            // 1. 接口名 "org.mpris.MediaPlayer2.Player"
            appendString(iter, "org.mpris.MediaPlayer2.Player", temp)

            // 2. changed_properties: a{sv}
            val dictIter = temp.allocate(128)
            val sigDict = temp.allocateFrom("{sv}", StandardCharsets.UTF_8)
            dbusIterOpenContainer?.invokeWithArguments(iter, DBUS_TYPE_ARRAY, sigDict, dictIter)

            appendDictEntryString(dictIter, "PlaybackStatus", pushedPlayingStatus, temp)
            appendMetadataDict(dictIter, track, pushedDurationMs * 1000L, temp)

            dbusIterCloseContainer?.invokeWithArguments(iter, dictIter)

            // 3. invalidated_properties: as (空列表)
            val emptyArrayIter = temp.allocate(128)
            val sigStr = temp.allocateFrom("s", StandardCharsets.UTF_8)
            dbusIterOpenContainer?.invokeWithArguments(iter, DBUS_TYPE_ARRAY, sigStr, emptyArrayIter)
            dbusIterCloseContainer?.invokeWithArguments(iter, emptyArrayIter)

            dbusConnSend?.invokeWithArguments(conn, signal, MemorySegment.NULL)
            dbusConnFlush?.invokeWithArguments(conn)
            dbusMsgUnref?.invokeWithArguments(signal)
        }
    }

    private fun appendMetadataDict(dictIter: MemorySegment, track: TrackSummary?, durUs: Long, temp: Arena) {
        val entryIter = temp.allocate(128)
        dbusIterOpenContainer?.invokeWithArguments(dictIter, DBUS_TYPE_DICT_ENTRY, MemorySegment.NULL, entryIter)
        appendString(entryIter, "Metadata", temp)

        val metaVarIter = temp.allocate(128)
        val sigMeta = temp.allocateFrom("a{sv}", StandardCharsets.UTF_8)
        dbusIterOpenContainer?.invokeWithArguments(entryIter, DBUS_TYPE_VARIANT, sigMeta, metaVarIter)

        val metaDictIter = temp.allocate(128)
        val sigEntry = temp.allocateFrom("{sv}", StandardCharsets.UTF_8)
        dbusIterOpenContainer?.invokeWithArguments(metaVarIter, DBUS_TYPE_ARRAY, sigEntry, metaDictIter)

        if (track != null) {
            appendDictEntryString(metaDictIter, "mpris:trackid", "/org/mpris/MediaPlayer2/track/${track.id.filter { it.isLetterOrDigit() }}", temp)
            appendDictEntryInt64(metaDictIter, "mpris:length", durUs, temp)
            appendDictEntryString(metaDictIter, "xesam:title", track.name.ifBlank { "CPPlayer" }, temp)
            appendDictEntryString(metaDictIter, "xesam:album", track.album.orEmpty(), temp)
            appendDictEntryStringArray(metaDictIter, "xesam:artist", listOf(track.artist), temp)

            val cover = coverFile(track.id, track.coverUrl)
            if (cover != null && cover.isFile) {
                appendDictEntryString(metaDictIter, "mpris:artUrl", cover.toURI().toASCIIString(), temp)
            }
        }

        dbusIterCloseContainer?.invokeWithArguments(metaVarIter, metaDictIter)
        dbusIterCloseContainer?.invokeWithArguments(entryIter, metaVarIter)
        dbusIterCloseContainer?.invokeWithArguments(dictIter, entryIter)
    }

    private fun appendString(iter: MemorySegment, str: String, arena: Arena) {
        val strPtr = arena.allocateFrom(str, StandardCharsets.UTF_8)
        val strRef = arena.allocate(ValueLayout.ADDRESS)
        strRef.set(ValueLayout.ADDRESS, 0, strPtr)
        dbusIterAppendBasic?.invokeWithArguments(iter, DBUS_TYPE_STRING, strRef)
    }

    private fun appendDictEntryString(dictIter: MemorySegment, key: String, value: String, arena: Arena) {
        val entryIter = arena.allocate(128)
        dbusIterOpenContainer?.invokeWithArguments(dictIter, DBUS_TYPE_DICT_ENTRY, MemorySegment.NULL, entryIter)
        appendString(entryIter, key, arena)
        appendVariantString(entryIter, value, arena)
        dbusIterCloseContainer?.invokeWithArguments(dictIter, entryIter)
    }

    private fun appendDictEntryInt64(dictIter: MemorySegment, key: String, value: Long, arena: Arena) {
        val entryIter = arena.allocate(128)
        dbusIterOpenContainer?.invokeWithArguments(dictIter, DBUS_TYPE_DICT_ENTRY, MemorySegment.NULL, entryIter)
        appendString(entryIter, key, arena)
        appendVariantInt64(entryIter, value, arena)
        dbusIterCloseContainer?.invokeWithArguments(dictIter, entryIter)
    }

    private fun appendDictEntryDouble(dictIter: MemorySegment, key: String, value: Double, arena: Arena) {
        val entryIter = arena.allocate(128)
        dbusIterOpenContainer?.invokeWithArguments(dictIter, DBUS_TYPE_DICT_ENTRY, MemorySegment.NULL, entryIter)
        appendString(entryIter, key, arena)
        val varIter = arena.allocate(128)
        val sig = arena.allocateFrom("d", StandardCharsets.UTF_8)
        dbusIterOpenContainer?.invokeWithArguments(entryIter, DBUS_TYPE_VARIANT, sig, varIter)
        val dMem = arena.allocate(ValueLayout.JAVA_DOUBLE)
        dMem.set(ValueLayout.JAVA_DOUBLE, 0, value)
        dbusIterAppendBasic?.invokeWithArguments(varIter, DBUS_TYPE_DOUBLE, dMem)
        dbusIterCloseContainer?.invokeWithArguments(entryIter, varIter)
        dbusIterCloseContainer?.invokeWithArguments(dictIter, entryIter)
    }

    private fun appendDictEntryBoolean(dictIter: MemorySegment, key: String, value: Boolean, arena: Arena) {
        val entryIter = arena.allocate(128)
        dbusIterOpenContainer?.invokeWithArguments(dictIter, DBUS_TYPE_DICT_ENTRY, MemorySegment.NULL, entryIter)
        appendString(entryIter, key, arena)
        appendVariantBoolean(entryIter, value, arena)
        dbusIterCloseContainer?.invokeWithArguments(dictIter, entryIter)
    }

    private fun appendDictEntryStringArray(dictIter: MemorySegment, key: String, values: List<String>, arena: Arena) {
        val entryIter = arena.allocate(128)
        dbusIterOpenContainer?.invokeWithArguments(dictIter, DBUS_TYPE_DICT_ENTRY, MemorySegment.NULL, entryIter)
        appendString(entryIter, key, arena)

        val varIter = arena.allocate(128)
        val sig = arena.allocateFrom("as", StandardCharsets.UTF_8)
        dbusIterOpenContainer?.invokeWithArguments(entryIter, DBUS_TYPE_VARIANT, sig, varIter)

        val arrIter = arena.allocate(128)
        val sigS = arena.allocateFrom("s", StandardCharsets.UTF_8)
        dbusIterOpenContainer?.invokeWithArguments(varIter, DBUS_TYPE_ARRAY, sigS, arrIter)
        for (v in values) {
            appendString(arrIter, v, arena)
        }
        dbusIterCloseContainer?.invokeWithArguments(varIter, arrIter)
        dbusIterCloseContainer?.invokeWithArguments(entryIter, varIter)
        dbusIterCloseContainer?.invokeWithArguments(dictIter, entryIter)
    }

    private fun appendVariantString(iter: MemorySegment, value: String, arena: Arena) {
        val varIter = arena.allocate(128)
        val sig = arena.allocateFrom("s", StandardCharsets.UTF_8)
        dbusIterOpenContainer?.invokeWithArguments(iter, DBUS_TYPE_VARIANT, sig, varIter)
        appendString(varIter, value, arena)
        dbusIterCloseContainer?.invokeWithArguments(iter, varIter)
    }

    private fun appendVariantInt64(iter: MemorySegment, value: Long, arena: Arena) {
        val varIter = arena.allocate(128)
        val sig = arena.allocateFrom("x", StandardCharsets.UTF_8)
        dbusIterOpenContainer?.invokeWithArguments(iter, DBUS_TYPE_VARIANT, sig, varIter)
        val lMem = arena.allocate(ValueLayout.JAVA_LONG)
        lMem.set(ValueLayout.JAVA_LONG, 0, value)
        dbusIterAppendBasic?.invokeWithArguments(varIter, DBUS_TYPE_INT64, lMem)
        dbusIterCloseContainer?.invokeWithArguments(iter, varIter)
    }

    private fun appendVariantBoolean(iter: MemorySegment, value: Boolean, arena: Arena) {
        val varIter = arena.allocate(128)
        val sig = arena.allocateFrom("b", StandardCharsets.UTF_8)
        dbusIterOpenContainer?.invokeWithArguments(iter, DBUS_TYPE_VARIANT, sig, varIter)
        val bMem = arena.allocate(ValueLayout.JAVA_INT)
        bMem.set(ValueLayout.JAVA_INT, 0, if (value) 1 else 0)
        dbusIterAppendBasic?.invokeWithArguments(varIter, DBUS_TYPE_BOOLEAN, bMem)
        dbusIterCloseContainer?.invokeWithArguments(iter, varIter)
    }

    override fun stop() {
        stopRequested = true
        started = false
        coverScope.cancel()

        runCatching {
            mprisExecutor.execute {
                connection?.let { conn ->
                    Arena.ofConfined().use { temp ->
                        val busName = temp.allocateFrom("org.mpris.MediaPlayer2.cpplayer", StandardCharsets.UTF_8)
                        val err = temp.allocate(64)
                        dbusErrorInit?.invokeWithArguments(err)
                        dbusBusReleaseName?.invokeWithArguments(conn, busName, err)
                        dbusConnClose?.invokeWithArguments(conn)
                        dbusConnUnref?.invokeWithArguments(conn)
                    }
                }
                connection = null
                runCatching { rootArena?.close() }
                rootArena = null

                resetPushedState()
                latestTrack = null
                coverCacheDir?.deleteRecursively()
                coverCacheDir = null
            }
        }
        mprisExecutor.shutdown()
    }

    private fun resetPushedState() {
        pushedTrackId = null
        pushedDurationMs = -1L
        pushedPlayingStatus = "Paused"
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
            mprisExecutor.execute {
                if (pushedTrackId != trackId) return@execute
                val conn = connection ?: return@execute
                val track = latestTrack ?: return@execute
                emitPropertiesChangedSignal(conn, track)
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
        private const val DBUS_BUS_SESSION = 1
        private const val DBUS_NAME_FLAG_REPLACE_EXISTING = 2
        private const val DBUS_NAME_FLAG_DO_NOT_QUEUE = 4

        private const val DBUS_MESSAGE_TYPE_METHOD_CALL = 1

        private const val DBUS_TYPE_STRING = 115
        private const val DBUS_TYPE_BOOLEAN = 98
        private const val DBUS_TYPE_INT64 = 120
        private const val DBUS_TYPE_DOUBLE = 100
        private const val DBUS_TYPE_VARIANT = 118
        private const val DBUS_TYPE_ARRAY = 97
        private const val DBUS_TYPE_DICT_ENTRY = 101

        private const val POSITION_PUSH_INTERVAL_MS = 1_000L
        private const val POSITION_JUMP_TOLERANCE_MS = 3_000L
        private const val MAX_COVER_BYTES = 20L * 1024 * 1024

        private const val INTROSPECTION_XML = """<!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
<node>
  <interface name="org.freedesktop.DBus.Introspectable">
    <method name="Introspect">
      <arg name="data" direction="out" type="s"/>
    </method>
  </interface>
  <interface name="org.freedesktop.DBus.Properties">
    <method name="Get">
      <arg name="interface_name" direction="in" type="s"/>
      <arg name="property_name" direction="in" type="s"/>
      <arg name="value" direction="out" type="v"/>
    </method>
    <method name="GetAll">
      <arg name="interface_name" direction="in" type="s"/>
      <arg name="properties" direction="out" type="a{sv}"/>
    </method>
    <signal name="PropertiesChanged">
      <arg name="interface_name" type="s"/>
      <arg name="changed_properties" type="a{sv}"/>
      <arg name="invalidated_properties" type="as"/>
    </signal>
  </interface>
  <interface name="org.mpris.MediaPlayer2">
    <method name="Raise"/>
    <method name="Quit"/>
    <property name="CanQuit" type="b" access="read"/>
    <property name="CanRaise" type="b" access="read"/>
    <property name="HasTrackList" type="b" access="read"/>
    <property name="Identity" type="s" access="read"/>
    <property name="SupportedUriSchemes" type="as" access="read"/>
    <property name="SupportedMimeTypes" type="as" access="read"/>
  </interface>
  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="Next"/>
    <method name="Previous"/>
    <method name="Pause"/>
    <method name="PlayPause"/>
    <method name="Stop"/>
    <method name="Play"/>
    <method name="Seek">
      <arg name="Offset" direction="in" type="x"/>
    </method>
    <method name="SetPosition">
      <arg name="TrackId" direction="in" type="o"/>
      <arg name="Position" direction="in" type="x"/>
    </method>
    <property name="PlaybackStatus" type="s" access="read"/>
    <property name="LoopStatus" type="s" access="read"/>
    <property name="Rate" type="d" access="read"/>
    <property name="Shuffle" type="b" access="read"/>
    <property name="Metadata" type="a{sv}" access="read"/>
    <property name="Volume" type="d" access="read"/>
    <property name="Position" type="x" access="read"/>
    <property name="CanGoNext" type="b" access="read"/>
    <property name="CanGoPrevious" type="b" access="read"/>
    <property name="CanPlay" type="b" access="read"/>
    <property name="CanPause" type="b" access="read"/>
    <property name="CanSeek" type="b" access="read"/>
    <property name="CanControl" type="b" access="read"/>
  </interface>
</node>"""

        fun create(controller: PlaybackController): PlatformMediaControlsInstance =
            LinuxMprisControls(controller)

        private fun downcall(linker: Linker, lookup: SymbolLookup, name: String, desc: FunctionDescriptor): MethodHandle? {
            return lookup.find(name).map { linker.downcallHandle(it, desc) }.orElse(null)
        }
    }
}
