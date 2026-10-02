package cp.player.app.platform

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.Ole32Util
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.ptr.PointerByReference
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Windows SMTC 的应用身份注册（「来源应用」名字 + 图标）。
 *
 * ## 为什么需要
 *
 * SMTC 面板角落的应用名字和图标**不是播放器自己报的**：系统按
 * `进程 AUMID → 开始菜单应用列表` 解析。没有注册 AUMID 的进程
 * （`java.exe`、或快捷方式缺 `System.AppUserModel.ID` 属性的 jpackage 安装）
 * 一律显示「未知应用」+ 空白图标。JMTC 的 C++ 层完全没有身份相关 API，
 * 所以这一步只能在 Java 侧自己补。Chrome / Spotify 的做法相同：
 * 设显式 AUMID + 在开始菜单放一个带该 AUMID 属性的快捷方式。
 *
 * ## 实现要点
 *
 * - 全程原生 COM（jna-platform 只有基础封装，IShellLinkW / IPropertyStore
 *   需要手撸 vtable 调用），一次性注册后用 marker 文件幂等跳过。
 * - 每个 COM 调用都可能失败（权限 / 精简系统 / 快捷方式被安全软件拦），
 *   所以 [ensure] 设计为**永不抛异常**：身份只是锦上添花，失败打日志、
 *   SMTC 的按钮控制照常工作，只是名字还是「未知应用」。
 * - 必须跑在与 JMTC init 同一条线程上（见 [JmtcMediaControls.smtcExecutor]）：
 *   这里 `CoInitializeEx(MTA)` 与 JMTC 的 `winrt::init_apartment()`（MTA）同套间，
 *   实测兼容；唯独不能是 STA（会踩 SMTCAdapter.dll 的 init 崩溃）。
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

    // vtable 槽位：IUnknown 占 0(QI) 1(AddRef) 2(Release)
    private const val SLOT_QI = 0
    private const val SLOT_RELEASE = 2

    // IShellLinkW（方法序从 GetPath 起）：SetWorkingDirectory=9、SetIconLocation=17、SetPath=20
    private const val SL_SET_WORKING_DIRECTORY = 9
    private const val SL_SET_ICON_LOCATION = 17
    private const val SL_SET_PATH = 20

    // IPersistFile 继承 IPersist（多一个 GetClassID 槽位）：IsDirty=4、Load=5、Save=6
    private const val PF_SAVE = 6

    // IPropertyStore：GetCount=3、GetAt=4、GetValue=5、SetValue=6、Commit=7
    private const val PS_SET_VALUE = 6
    private const val PS_COMMIT = 7

    /**
     * 注册进程身份；幂等，永不抛异常。只在 Windows 上有意义。
     * 调用线程的 COM 套间约束见类 KDoc。
     */
    fun ensure() {
        runCatching {
            val hr = Shell32.INSTANCE
                .SetCurrentProcessExplicitAppUserModelID(WString(APP_ID))
                .toInt()
            if (hr != 0) {
                System.err.println("[SMTC] SetCurrentProcessExplicitAppUserModelID hr=0x${Integer.toHexString(hr)}")
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
     * 把资源里的 PNG 打包成 ICO 写到 [dir]，返回 ICO 文件；拿不到资源就返回 null
     * （调用方回落 exe 自带图标）。PNG 直嵌条目 Vista+ 全部支持，免手写 DIB 编码。
     */
    private fun writeAppIconIco(dir: File): File? {
        val png = WindowsSmtcIdentity::class.java
            .getResourceAsStream(ICON_RESOURCE)
            ?.use { it.readBytes() }
            ?: return null
        // 条目宽高字节取图标边长；256 写 0（惯例）。资源如被替换，按实际解码尺寸适配。
        val side = runCatching {
            ImageIO.read(ByteArrayInputStream(png))?.let { minOf(it.width, it.height) } ?: 192
        }.getOrDefault(192).coerceIn(1, 256)
        val sideByte = if (side >= 256) 0 else side
        val ico = File(dir, "app.ico")
        ico.outputStream().use { out ->
            // ICONDIR：reserved=0、type=1(图标)、count=1
            out.write(byteArrayOf(0, 0, 1, 0, 1, 0))
            // ICONDIRENTRY
            out.write(sideByte)                       // width
            out.write(sideByte)                       // height
            out.write(0)                              // 调色板色数
            out.write(0)                              // reserved
            out.write(byteArrayOf(1, 0))              // planes = 1
            out.write(byteArrayOf(32, 0))             // bpp = 32
            var len = png.size
            repeat(4) { out.write(len and 0xFF); len = len shr 8 }   // bytesInRes
            out.write(22); out.write(0); out.write(0); out.write(0)  // imageOffset = 6+16
            out.write(png)
        }
        return ico
    }

    private fun createShortcut(lnk: File, exe: File, ico: File?) {
        val coInit = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
        val coInitialized = coInit.toInt() == 0 || coInit.toInt() == 1 // S_OK / S_FALSE
        try {
            val shellLinkRef = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                Ole32Util.getGUIDFromString(CLSID_SHELL_LINK),
                null,
                CLSCTX_INPROC_SERVER,
                Ole32Util.getGUIDFromString(IID_ISHELL_LINK_W),
                shellLinkRef,
            )
            check(hr.toInt() == 0) { "CoCreateInstance(IShellLinkW) hr=0x${Integer.toHexString(hr.toInt())}" }
            val shellLink = shellLinkRef.value ?: error("CoCreateInstance returned null")

            try {
                call(shellLink, SL_SET_PATH, WString(exe.absolutePath))
                // 图标优先用真应用图标（ICO）；开发跑下 exe 是 java.exe，借用它会显示 Java 图标
                if (ico?.isFile == true) {
                    call(shellLink, SL_SET_ICON_LOCATION, WString(ico.absolutePath), 0)
                } else {
                    call(shellLink, SL_SET_ICON_LOCATION, WString(exe.absolutePath), 0)
                }
                exe.parentFile?.let { call(shellLink, SL_SET_WORKING_DIRECTORY, WString(it.absolutePath)) }

                // 属性先写进内存 link，Save 时随 .lnk 一起持久化（顺序反了属性会丢）。
                setAppUserModelIdProperty(shellLink)

                val persistFile = queryInterface(shellLink, IID_IPERSIST_FILE)
                    ?: error("QueryInterface(IPersistFile) failed")
                try {
                    val saveHr = call(persistFile, PF_SAVE, WString(lnk.absolutePath), 1 /* TRUE */)
                    check(saveHr == 0) { "IPersistFile::Save hr=0x${Integer.toHexString(saveHr)}" }
                } finally {
                    call(persistFile, SLOT_RELEASE)
                }
            } finally {
                call(shellLink, SLOT_RELEASE)
            }
        } finally {
            if (coInitialized) Ole32.INSTANCE.CoUninitialize()
        }
    }

    /** 给快捷方式写 `System.AppUserModel.ID` 属性——没有它系统解析不到我们。 */
    private fun setAppUserModelIdProperty(shellLink: Pointer) {
        val store = queryInterface(shellLink, IID_IPROPERTY_STORE)
            ?: error("QueryInterface(IPropertyStore) failed")
        try {
            // PROPERTYKEY = GUID(16B) + DWORD pid(4B)
            val key = Memory(24)
            val fmtid = Ole32Util.getGUIDFromString(FMTID_APP_USER_MODEL_ID)
            fmtid.write()
            key.write(0, fmtid.pointer.getByteArray(0, 16), 0, 16)
            key.setInt(16, PID_APP_USER_MODEL_ID)

            val value = PropVariant().apply {
                vt = VT_LPWSTR
                pwszVal = wideString(APP_ID)
                write()
            }

            val setHr = call(store, PS_SET_VALUE, key, value)
            check(setHr == 0) { "IPropertyStore::SetValue hr=0x${Integer.toHexString(setHr)}" }
            val commitHr = call(store, PS_COMMIT)
            check(commitHr == 0) { "IPropertyStore::Commit hr=0x${Integer.toHexString(commitHr)}" }
        } finally {
            call(store, SLOT_RELEASE)
        }
    }

    private fun queryInterface(iface: Pointer, iid: String): Pointer? {
        val ref = PointerByReference()
        val hr = call(iface, SLOT_QI, Ole32Util.getGUIDFromString(iid), ref)
        return if (hr == 0) ref.value else null
    }

    /** 调 COM 接口 vtable 的第 [slot] 个槽（含 this），返回 HRESULT。 */
    private fun call(iface: Pointer, slot: Int, vararg args: Any): Int {
        val vtable = iface.getPointer(0)
        val fn = Function.getFunction(
            vtable.getPointer(slot * Native.POINTER_SIZE.toLong()),
            Function.ALT_CONVENTION,
        )
        val callArgs = arrayOfNulls<Any>(args.size + 1)
        callArgs[0] = iface
        args.copyInto(callArgs, 1)
        return fn.invokeInt(callArgs)
    }

    /** VT_LPWSTR 的 PROPVARIANT。真实结构 x64 上是 24B，padding 补齐方便排查。 */
    @Structure.FieldOrder("vt", "wReserved1", "wReserved2", "wReserved3", "pwszVal", "padding")
    class PropVariant : Structure() {
        @JvmField var vt: Short = 0
        @JvmField var wReserved1: Short = 0
        @JvmField var wReserved2: Short = 0
        @JvmField var wReserved3: Short = 0
        @JvmField var pwszVal: Pointer? = null
        @JvmField var padding: ByteArray = ByteArray(8)
    }

    /** 分配 UTF-16 原生字符串（wchar_t *，Windows 为 UTF-16LE）。 */
    private fun wideString(value: String): Pointer {
        val chars = (value + "\u0000").toCharArray()
        val memory = Memory(chars.size * 2L)
        memory.write(0, chars, 0, chars.size)
        return memory
    }
}
