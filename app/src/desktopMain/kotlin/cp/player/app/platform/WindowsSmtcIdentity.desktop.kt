package cp.player.app.platform

import java.io.ByteArrayInputStream
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO

/**
 * Windows SMTC 的应用身份注册（「来源应用」名字 + 图标）。
 *
 * ## 为什么需要
 *
 * SMTC 面板角落的应用名字和图标不是播放器自己报的：系统按
 * `进程 AUMID → 开始菜单应用列表` 解析。没有注册 AUMID 的进程
 * （java.exe、或快捷方式缺 `System.AppUserModel.ID` 属性的 jpackage 安装）
 * 一律显示「未知应用」+ 空白图标。
 * Chrome / Spotify 的做法相同：设显式 AUMID + 在开始菜单放一个带该 AUMID 属性的快捷方式。
 *
 * ## 实现要点
 *
 * - 全程原生 COM 基于 JDK 22+ Foreign Function & Memory API (FFM)，无需 JNA。
 * - 每个 COM 调用都可能失败（权限 / 精简系统 / 快捷方式被安全软件拦），
 *   所以 [ensure] 设计为永不抛异常：身份只是锦上添花，失败打日志、
 *   SMTC 的按钮控制照常工作，只是名字还是「未知应用」。
 */
internal object WindowsSmtcIdentity {

    /** AUMID 与快捷方式属性一致即可；层级式命名避免与系统内裸 "CPPlayer" 撞名。 */
    private const val APP_ID = "cp.player.CPPlayer"

    private const val SHORTCUT_NAME = "CPPlayer.lnk"

    /**
     * marker 格式版本。v1 的 marker 只记 exe 路径、快捷方式图标借的是 java.exe 的
     * Java 图标；v2 起图标改为真应用图标（见 [writeAppIconIco]），版本号变化会使
     * 旧 marker 失配，下次启动自动重写快捷方式。
     */
    private const val MARKER_VERSION = "v2"

    /** 与窗口图标同源的启动图标（复用 Android 启动资源）。 */
    private const val ICON_RESOURCE = "/cpplayer/icon.png"

    // ---- COM 标识 ----
    private const val CLSID_SHELL_LINK = "{00021401-0000-0000-C000-000000000046}"
    private const val IID_ISHELL_LINK_W = "{000214F9-0000-0000-C000-000000000046}"
    private const val IID_IPERSIST_FILE = "{0000010B-0000-0000-C000-000000000046}"
    private const val IID_IPROPERTY_STORE = "{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}"
    private const val FMTID_APP_USER_MODEL_ID = "{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}"
    private const val PID_APP_USER_MODEL_ID = 5

    private const val CLSCTX_INPROC_SERVER = 1
    private const val VT_LPWSTR: Short = 31

    // vtable 槽位
    private const val SLOT_RELEASE = 2
    private const val SL_SET_WORKING_DIRECTORY = 9
    private const val SL_SET_ICON_LOCATION = 17
    private const val SL_SET_PATH = 20

    private const val PF_SAVE = 6
    private const val PS_SET_VALUE = 6
    private const val PS_COMMIT = 7

    private val linker: Linker by lazy { Linker.nativeLinker() }

    /**
     * 注册进程身份；幂等，永不抛异常。只在 Windows 上有意义。
     */
    fun ensure() {
        if (!System.getProperty("os.name", "").lowercase().contains("win")) return
        runCatching {
            Arena.ofConfined().use { arena ->
                val shell32 = SymbolLookup.libraryLookup("shell32", arena)
                val setAumid = linker.downcallHandle(
                    shell32.find("SetCurrentProcessExplicitAppUserModelID").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                )
                val wideAppId = arena.allocateFrom(APP_ID, StandardCharsets.UTF_16LE)
                val hr = setAumid.invokeWithArguments(wideAppId) as Int
                if (hr != 0) {
                    System.err.println("[SMTC] SetCurrentProcessExplicitAppUserModelID hr=0x${Integer.toHexString(hr)}")
                }
            }
        }.onFailure { System.err.println("[SMTC] set AUMID failed: ${it.message}") }

        runCatching { ensureShortcut() }
            .onFailure { System.err.println("[SMTC] shortcut registration failed: ${it.message}") }
    }

    /** 快捷方式已存在且指向同一可执行文件时跳过（marker 记录上次注册的 exe 路径）。 */
    private fun ensureShortcut() {
        val exePath = ProcessHandle.current().info().command().orElse(null) ?: return
        val exe = File(exePath)
        if (!exe.isFile) return

        val appData = System.getenv("APPDATA") ?: return
        val localAppData = System.getenv("LOCALAPPDATA") ?: return
        val programsDir = File(appData, "Microsoft\\Windows\\Start Menu\\Programs")
        val lnk = File(programsDir, SHORTCUT_NAME)
        val markerDir = File(localAppData, "CPPlayer")
        val marker = File(markerDir, "smtc-identity.txt")
        val ico = runCatching { writeAppIconIco(markerDir) }.getOrNull()
        val expectedMarker = "$MARKER_VERSION\n$exePath"

        if (lnk.isFile && marker.isFile && marker.readText().trim() == expectedMarker) return

        createShortcut(lnk, exe, ico)
        if (lnk.isFile) {
            markerDir.mkdirs()
            marker.writeText(expectedMarker)
            println("[SMTC] identity shortcut registered: ${lnk.absolutePath} (AUMID=$APP_ID)")
        }
    }

    /**
     * 把资源里的 PNG 打包成 ICO 写到 [dir]，返回 ICO 文件；拿不到资源就返回 null。
     */
    private fun writeAppIconIco(dir: File): File? {
        val png = WindowsSmtcIdentity::class.java
            .getResourceAsStream(ICON_RESOURCE)
            ?.use { it.readBytes() }
            ?: return null
        val side = runCatching {
            ImageIO.read(ByteArrayInputStream(png))?.let { minOf(it.width, it.height) } ?: 192
        }.getOrDefault(192).coerceIn(1, 256)
        val sideByte = if (side >= 256) 0 else side
        val ico = File(dir, "app.ico")
        ico.outputStream().use { out ->
            out.write(byteArrayOf(0, 0, 1, 0, 1, 0))
            out.write(sideByte)
            out.write(sideByte)
            out.write(0)
            out.write(0)
            out.write(byteArrayOf(1, 0))
            out.write(byteArrayOf(32, 0))
            var len = png.size
            repeat(4) { out.write(len and 0xFF); len = len shr 8 }
            out.write(22); out.write(0); out.write(0); out.write(0)
            out.write(png)
        }
        return ico
    }

    private fun createShortcut(lnk: File, exe: File, ico: File?) {
        Arena.ofConfined().use { arena ->
            val ole32 = SymbolLookup.libraryLookup("ole32", arena)
            val coInitializeEx = linker.downcallHandle(
                ole32.find("CoInitializeEx").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            )
            val coUninitialize = linker.downcallHandle(
                ole32.find("CoUninitialize").orElseThrow(),
                FunctionDescriptor.ofVoid(),
            )
            val coCreateInstance = linker.downcallHandle(
                ole32.find("CoCreateInstance").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ),
            )
            val iidFromString = linker.downcallHandle(
                ole32.find("IIDFromString").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            )

            // COINIT_MULTITHREADED = 0
            val coInit = coInitializeEx.invokeWithArguments(MemorySegment.NULL, 0) as Int
            val coInitialized = coInit == 0 || coInit == 1 // S_OK / S_FALSE

            try {
                val clsidShellLink = arena.allocate(16)
                iidFromString.invokeWithArguments(arena.allocateFrom(CLSID_SHELL_LINK, StandardCharsets.UTF_16LE), clsidShellLink)

                val iidShellLinkW = arena.allocate(16)
                iidFromString.invokeWithArguments(arena.allocateFrom(IID_ISHELL_LINK_W, StandardCharsets.UTF_16LE), iidShellLinkW)

                val shellLinkRef = arena.allocate(ValueLayout.ADDRESS)
                val hr = coCreateInstance.invokeWithArguments(
                    clsidShellLink,
                    MemorySegment.NULL,
                    CLSCTX_INPROC_SERVER,
                    iidShellLinkW,
                    shellLinkRef,
                ) as Int
                check(hr == 0) { "CoCreateInstance(IShellLinkW) hr=0x${Integer.toHexString(hr)}" }
                val shellLink = shellLinkRef.get(ValueLayout.ADDRESS, 0)

                try {
                    val wideExe = arena.allocateFrom(exe.absolutePath, StandardCharsets.UTF_16LE)
                    callCom(shellLink, SL_SET_PATH, wideExe)

                    if (ico?.isFile == true) {
                        val wideIco = arena.allocateFrom(ico.absolutePath, StandardCharsets.UTF_16LE)
                        callCom(shellLink, SL_SET_ICON_LOCATION, wideIco, 0)
                    } else {
                        callCom(shellLink, SL_SET_ICON_LOCATION, wideExe, 0)
                    }
                    exe.parentFile?.let {
                        val wideWorkDir = arena.allocateFrom(it.absolutePath, StandardCharsets.UTF_16LE)
                        callCom(shellLink, SL_SET_WORKING_DIRECTORY, wideWorkDir)
                    }

                    // 设置 AUMID
                    setAppUserModelIdProperty(shellLink, arena, iidFromString)

                    // 保存到 .lnk
                    val iidPersistFile = arena.allocate(16)
                    iidFromString.invokeWithArguments(arena.allocateFrom(IID_IPERSIST_FILE, StandardCharsets.UTF_16LE), iidPersistFile)
                    val persistFile = queryInterface(shellLink, iidPersistFile, arena)
                        ?: error("QueryInterface(IPERSIST_FILE) failed")
                    try {
                        val wideLnk = arena.allocateFrom(lnk.absolutePath, StandardCharsets.UTF_16LE)
                        val saveHr = callCom(persistFile, PF_SAVE, wideLnk, 1 /* TRUE */)
                        check(saveHr == 0) { "IPersistFile::Save hr=0x${Integer.toHexString(saveHr)}" }
                    } finally {
                        callCom(persistFile, SLOT_RELEASE)
                    }
                } finally {
                    callCom(shellLink, SLOT_RELEASE)
                }
            } finally {
                if (coInitialized) {
                    coUninitialize.invokeWithArguments()
                }
            }
        }
    }

    private fun setAppUserModelIdProperty(shellLink: MemorySegment, arena: Arena, iidFromString: MethodHandle) {
        val iidPropStore = arena.allocate(16)
        iidFromString.invokeWithArguments(arena.allocateFrom(IID_IPROPERTY_STORE, StandardCharsets.UTF_16LE), iidPropStore)
        val store = queryInterface(shellLink, iidPropStore, arena)
            ?: error("QueryInterface(IPropertyStore) failed")
        try {
            val key = arena.allocate(20)
            val fmtidGuid = arena.allocate(16)
            iidFromString.invokeWithArguments(arena.allocateFrom(FMTID_APP_USER_MODEL_ID, StandardCharsets.UTF_16LE), fmtidGuid)
            MemorySegment.copy(fmtidGuid, 0, key, 0, 16)
            key.set(ValueLayout.JAVA_INT, 16, PID_APP_USER_MODEL_ID)

            val propvar = arena.allocate(24)
            propvar.set(ValueLayout.JAVA_SHORT, 0, VT_LPWSTR)
            val wideAppId = arena.allocateFrom(APP_ID, StandardCharsets.UTF_16LE)
            propvar.set(ValueLayout.ADDRESS, 8, wideAppId)

            val setHr = callCom(store, PS_SET_VALUE, key, propvar)
            check(setHr == 0) { "IPropertyStore::SetValue hr=0x${Integer.toHexString(setHr)}" }
            val commitHr = callCom(store, PS_COMMIT)
            check(commitHr == 0) { "IPropertyStore::Commit hr=0x${Integer.toHexString(commitHr)}" }
        } finally {
            callCom(store, SLOT_RELEASE)
        }
    }

    private fun queryInterface(iface: MemorySegment, iid: MemorySegment, arena: Arena): MemorySegment? {
        val outPtr = arena.allocate(ValueLayout.ADDRESS)
        val hr = callCom(iface, 0 /* QueryInterface */, iid, outPtr)
        return if (hr == 0) {
            val res = outPtr.get(ValueLayout.ADDRESS, 0)
            if (res != MemorySegment.NULL && res.address() != 0L) res else null
        } else null
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
                else -> error("Unsupported COM arg type: ${arg::class}")
            }
        }

        val desc = FunctionDescriptor.of(ValueLayout.JAVA_INT, *paramLayouts.toTypedArray())
        val handle = linker.downcallHandle(funcPtr, desc)
        val invokeArgs = arrayOfNulls<Any>(args.size + 1)
        invokeArgs[0] = iface
        args.copyInto(invokeArgs, 1)
        return handle.invokeWithArguments(*invokeArgs) as Int
    }
}
