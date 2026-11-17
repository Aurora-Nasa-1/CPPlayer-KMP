package cp.player.app.platform

import cp.player.core.util.defaultSettingsStorage
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import kotlin.concurrent.thread

/**
 * 用 FFM 调 `nvapi64.dll`，把**本程序**在 NVIDIA 驱动里的「显示器技术」写成「固定刷新率」，
 * 也就是关掉针对本程序的 VRR / G-SYNC。
 *
 * ## 为什么需要它
 *
 * 本程序的窗口是 Compose 事件驱动重绘：没交互时几乎不出帧、滚动时才突然连出几十帧。
 * 开了 VRR 的显示器会把刷新率跟着这个节奏拉来拉去，在部分面板上表现为**亮度闪烁**
 * （设置页里那条「VRR 闪烁」注释就是这么来的）。播放器界面又长期开着，闪烁比游戏里更难受。
 *
 * ## 为什么不是「运行时立刻关」
 *
 * NVAPI 没有「给我这个进程关掉 VRR」的运行时调用。能改的只有**驱动配置文件（DRS）里
 * 逐程序的「G-SYNC」覆盖项**：`VRR_APP_OVERRIDE_ID`（0x10A879CF）设为
 * `VRR_APP_OVERRIDE_FIXED_REFRESH`（4，即 NVIDIA 控制面板里的「固定刷新率」）。
 * 驱动在**进程启动时**读这份配置，所以写入后要**下次启动**才生效，本次会话不受影响。
 *
 * ## 权限与副作用（实测，别想当然）
 *
 * - 写入位置是 `%ProgramData%\NVIDIA Corporation\Drs\nvdrsdb*.bin`。本机（RTX 3070 /
 *   驱动 616.56 / 普通用户）实测 `NvAPI_DRS_SaveSettings` 返回 `NVAPI_OK`，**不需要管理员**；
 *   但这不是文档保证的行为，别处遇到 `NVAPI_ACCESS_DENIED` 属正常，按失败处理即可。
 * - 会在 NVIDIA 控制面板里建一条**用户可见的**程序配置：`管理 3D 设置 → 程序设置 → CPPlayer`，
 *   「监视器技术 = 固定刷新率」。用户随时可以在面板里改回去 —— 我们不覆盖用户已显式改过的值
 *   （见 [applyNow] 里的「已是固定刷新率就不写」判断）。
 *
 * ## 只在原生镜像里跑
 *
 * 开发时（`desktopRun`）进程是 `java.exe`，此时按 exe 路径建配置会**污染 java.exe 的驱动配置**。
 * 所以这里显式要求「跑在 GraalVM Native Image 里」，JVM 上一律跳过。
 *
 * ## ⚠️ FFM 签名登记
 *
 * 与 [WindowsWindowCorners] 同一条硬约束：镜像里每个 downcall 签名必须在
 * `app/graalvm/reachability-metadata.json` 的 `foreign.downcalls` 登记，这里用到 6 条
 * （`void*(int)`、`int()`、`int(void*)`、`int(void*,void*,void*,void*)`、
 * `int(void*,void*,void*)`、`int(void*,void*,int,void*)`、`int(void*,void*,int)`）。
 * 漏一条 ⇒ `downcallHandle` 抛 `MissingForeignRegistrationError` ⇒ 本功能静默失效。
 *
 * ## 结构体布局（pack 4，按 nvapi.h 推导）
 *
 * ```
 * NVDRS_SETTING_V1    12320 = 24 + 4096(UnicodeString) + 4100 + 4100
 *     0 version | 4 settingName[4096] | 4100 settingId | 4104 type | 4108 location
 *     4112 isCurrentPredefined | 4116 isPredefinedValid | 4120 预定义值 | 8220 当前值
 * NVDRS_APPLICATION_V4 20492 = 8 + 4096*4 + 4 + 4096
 * NVDRS_PROFILE_V1     4116 = 4 + 4096 + 4 + 4 + 4 + 4
 * ```
 * 版本号 = `尺寸 | (版本 << 16)`（`MAKE_NVAPI_VERSION`），写错就是
 * `NVAPI_INCOMPATIBLE_STRUCT_VERSION`。
 */
internal object WindowsNvapiVrr {

    /** 持久化键：`fixed` = 写「固定刷新率」，`off` = 完全不碰驱动配置。 */
    private const val KEY_MODE = "desktop_vrr_profile"

    /**
     * 本次运行的上次结果，给设置页「当前状态」用。
     *
     * 原生包没有控制台，`println` 谁也看不见 —— 所以结果必须能落到 UI 上，
     * 否则这个功能失败时用户和开发者都无从判断。
     */
    @Volatile
    private var lastStatus: String = "尚未尝试"

    /** 是否要写驱动配置。默认写（这正是本功能的目的），用户可在设置里关掉。 */
    enum class Mode(val storage: String, val label: String) {
        FIXED_REFRESH("fixed", "关闭本程序的 VRR（写入 NVIDIA「固定刷新率」）"),
        OFF("off", "不干预（保留 NVIDIA 控制面板里的现有设置）"),
    }

    private val prefs by lazy { defaultSettingsStorage() }

    fun storedMode(): Mode {
        val raw = prefs.getString(KEY_MODE) ?: return Mode.FIXED_REFRESH
        return Mode.entries.firstOrNull { it.storage == raw } ?: Mode.FIXED_REFRESH
    }

    fun storeMode(mode: Mode) {
        prefs.putString(KEY_MODE, mode.storage)
    }

    fun lastStatus(): String = lastStatus

    /**
     * 启动时在后台跑一次，不阻塞 UI（驱动调用是同步的，几十毫秒）。
     *
     * 用守护线程而不是协程：这一步必须在任何 UI 之前就可能失败，且失败不该影响启动。
     */
    fun applyInBackground() {
        thread(isDaemon = true, name = "cpplayer-nvapi-vrr") {
            val status = runCatching { applyNow() }
                .onFailure { it.printStackTrace() }
                .getOrElse { "失败：${it.message ?: it::class.simpleName}" }
            lastStatus = status
            println("[NvapiVrr] $status")
        }
    }

    // ======================== 实现 ========================

    /**
     * `nvapi_QueryInterface` 的函数 ID（见 nvapi_interface.h）。
     *
     * 一律带 `L` 后缀声明成 Long：这些 ID 是 32 位无符号量，`0xD22BDD7E` 这类超过
     * `Int.MAX_VALUE` 的字面量会被 Kotlin 推成 Long，混着写会在调 `api()` 时类型不匹配。
     * 真正下传时再 `.toInt()` 还原成 32 位。
     */
    private const val ID_INITIALIZE = 0x0150E828L
    private const val ID_UNLOAD = 0xD22BDD7EL
    private const val ID_DRS_CREATE_SESSION = 0x0694D52EL
    private const val ID_DRS_DESTROY_SESSION = 0xDAD9CFF8L
    private const val ID_DRS_LOAD_SETTINGS = 0x375DBD6BL
    private const val ID_DRS_SAVE_SETTINGS = 0xFCBC7E14L
    private const val ID_DRS_FIND_APPLICATION = 0xEEE566B2L
    private const val ID_DRS_CREATE_PROFILE = 0xCC176068L
    private const val ID_DRS_CREATE_APPLICATION = 0x4347A9DEL
    private const val ID_DRS_SET_SETTING = 0x577DD202L
    private const val ID_DRS_GET_SETTING = 0x73BF8338L

    /** `VRR_APP_OVERRIDE_ID`：逐程序的「G-SYNC」覆盖（NvApiDriverSettings.h）。 */
    private const val VRR_APP_OVERRIDE_ID = 0x10A879CF

    /** `VRR_APP_OVERRIDE_FIXED_REFRESH`：NVIDIA 控制面板里的「固定刷新率」。 */
    private const val VRR_APP_OVERRIDE_FIXED_REFRESH = 4

    /** `NVDRS_DWORD_TYPE`。 */
    private const val NVDRS_DWORD_TYPE = 0

    /** `NvAPI_UnicodeString` = `NvU16[2048]`。 */
    private const val USTR = 4096

    private const val SETTING_SIZE = 24 + USTR + 4100 * 2
    private const val APPLICATION_SIZE = 8 + USTR * 4 + 4 + USTR
    private const val PROFILE_SIZE = USTR + 20

    private const val OFF_SETTING_ID = 4100
    private const val OFF_TYPE = 4104
    private const val OFF_CURRENT_PREDEFINED = 4112
    private const val OFF_CURRENT_VALUE = 8220

    private const val NVAPI_OK = 0

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** GraalVM Native Image 才会设置这个属性（`imagecode=build|runtime`）。 */
    private fun isNativeImage(): Boolean =
        System.getProperty("org.graalvm.nativeimage.imagecode") != null

    private fun applyNow(): String {
        if (!isWindows()) return "跳过：非 Windows"
        if (!isNativeImage()) return "跳过：开发运行（进程是 java.exe，不该给它建驱动配置）"
        when (storedMode()) {
            Mode.OFF -> return "已关闭写入（设置里选了「不干预」）"
            Mode.FIXED_REFRESH -> Unit
        }
        val exe = currentExePath() ?: return "跳过：拿不到本进程的 exe 路径"

        return Arena.ofConfined().use { arena ->
            val linker = Linker.nativeLinker()
            val nvapi = runCatching { SymbolLookup.libraryLookup("nvapi64.dll", arena) }
                .getOrElse { return@use "跳过：加载不了 nvapi64.dll（不是 NVIDIA 平台？）" }

            val query = downcall(
                linker, nvapi, "nvapi_QueryInterface", ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            )

            fun api(id: Long, name: String, returns: MemoryLayout, vararg params: MemoryLayout): MethodHandle {
                val ptr = query.invokeWithArguments(id.toInt()) as MemorySegment
                check(ptr.address() != 0L) { "$name 在本机驱动里不存在" }
                return linker.downcallHandle(ptr, FunctionDescriptor.of(returns, *params))
            }

            val initialize = api(ID_INITIALIZE, "NvAPI_Initialize", ValueLayout.JAVA_INT)
            val unload = api(ID_UNLOAD, "NvAPI_Unload", ValueLayout.JAVA_INT)
            val createSession = api(
                ID_DRS_CREATE_SESSION, "NvAPI_DRS_CreateSession",
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            )
            val destroySession = api(
                ID_DRS_DESTROY_SESSION, "NvAPI_DRS_DestroySession",
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            )
            val loadSettings = api(
                ID_DRS_LOAD_SETTINGS, "NvAPI_DRS_LoadSettings",
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            )
            val saveSettings = api(
                ID_DRS_SAVE_SETTINGS, "NvAPI_DRS_SaveSettings",
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            )
            val findApplication = api(
                ID_DRS_FIND_APPLICATION, "NvAPI_DRS_FindApplicationByName",
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            )
            val createProfile = api(
                ID_DRS_CREATE_PROFILE, "NvAPI_DRS_CreateProfile",
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            )
            val createApplication = api(
                ID_DRS_CREATE_APPLICATION, "NvAPI_DRS_CreateApplication",
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            )
            val getSetting = api(
                ID_DRS_GET_SETTING, "NvAPI_DRS_GetSetting",
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            )
            val setSetting = api(
                ID_DRS_SET_SETTING, "NvAPI_DRS_SetSetting",
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            )

            val status = initialize.invokeWithArguments() as Int
            if (status != NVAPI_OK) return@use "失败：NvAPI_Initialize 返回 $status"

            val sessionOut = arena.allocate(ValueLayout.ADDRESS)
            var result = createSession.invokeWithArguments(sessionOut) as Int
            if (result != NVAPI_OK) {
                unload.invokeWithArguments()
                return@use "失败：DRS_CreateSession 返回 $result（需要管理员权限？）"
            }
            val session = sessionOut.get(ValueLayout.ADDRESS, 0)

            try {
                result = loadSettings.invokeWithArguments(session) as Int
                if (result != NVAPI_OK) return@use "失败：DRS_LoadSettings 返回 $result"

                val appName = arena.allocate(USTR.toLong())
                putWide(appName, exe)
                val profileOut = arena.allocate(ValueLayout.ADDRESS)
                val appInfo = arena.allocate(APPLICATION_SIZE.toLong())
                appInfo.set(ValueLayout.JAVA_INT, 0, nvapiVersion(APPLICATION_SIZE, 4))
                result = findApplication.invokeWithArguments(session, appName, profileOut, appInfo) as Int

                if (result != NVAPI_OK || profileOut.get(ValueLayout.ADDRESS, 0).address() == 0L) {
                    // 没有该程序的配置：建一条，名为 CPPlayer，并把本 exe 挂上去。
                    val profileName = arena.allocate(USTR.toLong())
                    putWide(profileName, "CPPlayer")
                    val profile = arena.allocate(PROFILE_SIZE.toLong())
                    profile.set(ValueLayout.JAVA_INT, 0, nvapiVersion(PROFILE_SIZE, 1))
                    profile.asSlice(4, USTR.toLong()).copyFrom(profileName)
                    result = createProfile.invokeWithArguments(session, profile, profileOut) as Int
                    if (result != NVAPI_OK) return@use "失败：DRS_CreateProfile 返回 $result"

                    val app = arena.allocate(APPLICATION_SIZE.toLong())
                    app.set(ValueLayout.JAVA_INT, 0, nvapiVersion(APPLICATION_SIZE, 4))
                    app.asSlice(8, USTR.toLong()).copyFrom(appName)
                    val friendly = arena.allocate(USTR.toLong())
                    putWide(friendly, exe.substringAfterLast('\\'))
                    app.asSlice(8L + USTR, USTR.toLong()).copyFrom(friendly)
                    result = createApplication.invokeWithArguments(
                        session, profileOut.get(ValueLayout.ADDRESS, 0), app,
                    ) as Int
                    if (result != NVAPI_OK) return@use "失败：DRS_CreateApplication 返回 $result"
                }

                val profile = profileOut.get(ValueLayout.ADDRESS, 0)

                // 已经是我们想要的值（且是用户值而非预定义值）就不写，避免每次启动都改驱动库。
                val current = arena.allocate(SETTING_SIZE.toLong())
                current.set(ValueLayout.JAVA_INT, 0, nvapiVersion(SETTING_SIZE, 1))
                result = getSetting.invokeWithArguments(
                    session, profile, VRR_APP_OVERRIDE_ID, current,
                ) as Int
                if (result == NVAPI_OK &&
                    current.get(ValueLayout.JAVA_INT, OFF_CURRENT_VALUE.toLong()) == VRR_APP_OVERRIDE_FIXED_REFRESH &&
                    current.get(ValueLayout.JAVA_INT, OFF_CURRENT_PREDEFINED.toLong()) == 0
                ) {
                    return@use "已生效：本程序的 VRR 已是「固定刷新率」"
                }

                val setting = arena.allocate(SETTING_SIZE.toLong())
                setting.set(ValueLayout.JAVA_INT, 0, nvapiVersion(SETTING_SIZE, 1))
                setting.set(ValueLayout.JAVA_INT, OFF_SETTING_ID.toLong(), VRR_APP_OVERRIDE_ID)
                setting.set(ValueLayout.JAVA_INT, OFF_TYPE.toLong(), NVDRS_DWORD_TYPE)
                setting.set(
                    ValueLayout.JAVA_INT, OFF_CURRENT_VALUE.toLong(), VRR_APP_OVERRIDE_FIXED_REFRESH,
                )
                result = setSetting.invokeWithArguments(session, profile, setting) as Int
                if (result != NVAPI_OK) return@use "失败：DRS_SetSetting 返回 $result"

                result = saveSettings.invokeWithArguments(session) as Int
                if (result != NVAPI_OK) {
                    return@use "失败：DRS_SaveSettings 返回 $result（多半是权限不足，可手动在 " +
                        "NVIDIA 控制面板把本程序的「监视器技术」设为「固定刷新率」）"
                }

                val verify = arena.allocate(SETTING_SIZE.toLong())
                verify.set(ValueLayout.JAVA_INT, 0, nvapiVersion(SETTING_SIZE, 1))
                result = getSetting.invokeWithArguments(
                    session, profile, VRR_APP_OVERRIDE_ID, verify,
                ) as Int
                val stored = verify.get(ValueLayout.JAVA_INT, OFF_CURRENT_VALUE.toLong())
                if (result != NVAPI_OK || stored != VRR_APP_OVERRIDE_FIXED_REFRESH) {
                    "已写入但回读不一致（读回 $stored）"
                } else {
                    "已写入：本程序下次启动起为「固定刷新率」"
                }
            } finally {
                runCatching { destroySession.invokeWithArguments(session) }
                runCatching { unload.invokeWithArguments() }
            }
        }
    }

    /** `MAKE_NVAPI_VERSION`：低 16 位是结构体字节数，高 16 位是版本号。 */
    private fun nvapiVersion(size: Int, version: Int): Int = (size and 0xFFFF) or (version shl 16)

    private fun putWide(dst: MemorySegment, text: String) {
        val raw = text.toByteArray(Charsets.UTF_16LE)
        dst.asSlice(0, raw.size.toLong()).copyFrom(MemorySegment.ofArray(raw))
    }

    /**
     * 本进程 exe 的完整路径（驱动按这个路径认领程序）。
     *
     * 用 `GetModuleFileNameW` 而不是 `ProcessHandle.info().command()`：后者在原生镜像里
     * 未必实现，而且别的写法可能拿到启动器路径。`current` 这类目录联接由驱动自己做归一化
     * （实测写入后 `FindApplicationByName` 能按同一路径找回）。
     */
    private fun currentExePath(): String? = runCatching {
        Arena.ofConfined().use { arena ->
            val linker = Linker.nativeLinker()
            val kernel32 = SymbolLookup.libraryLookup("kernel32", arena)
            val getModuleFileName = downcall(
                linker, kernel32, "GetModuleFileNameW", ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            )
            val buffer = arena.allocate(1024L * 2)
            // ⚠️ 第一个参数是 hModule（NULL 表示当前进程）。这里描述符写的是 ADDRESS，
            // 就必须传 `MemorySegment.NULL`：传 `0L` 在 JVM 上抛 WrongMethodTypeException，
            // 在原生镜像里则可能被静默当成 0 传下去 —— 两种都不该出现。
            val len = getModuleFileName.invokeWithArguments(MemorySegment.NULL, buffer, 1024) as Int
            if (len <= 0) return@use null
            val bytes = ByteArray(len * 2)
            MemorySegment.copy(buffer, 0L, MemorySegment.ofArray(bytes), 0L, bytes.size.toLong())
            String(bytes, Charsets.UTF_16LE)
        }
    }.getOrNull()

    private fun downcall(
        linker: Linker,
        library: SymbolLookup,
        name: String,
        returns: MemoryLayout,
        vararg parameters: MemoryLayout,
    ): MethodHandle = linker.downcallHandle(
        library.find(name).orElseThrow { IllegalStateException("缺少符号 $name") },
        FunctionDescriptor.of(returns, *parameters),
    )
}
