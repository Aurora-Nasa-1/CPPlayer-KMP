package cp.player.app.platform

import cp.player.core.util.DesktopDataDir
import cp.player.core.util.defaultSettingsStorage
import java.io.File

/**
 * Windows 桌面渲染后端调优。
 *
 * ## 为什么需要它
 *
 * Compose Desktop 的绘制由 Skiko 承担。Skiko 0.144.6 在 Windows **默认走 Direct3D 12**，
 * 其 swap chain 使用 `DXGI_SWAP_EFFECT_FLIP_DISCARD` + `IDXGISwapChain3::Present`，
 * 且 native 侧会调用 `DwmFlush`——也就是说**出帧节奏被挂在 DWM 合成器上**，
 * 而 DWM 的合成节奏正是可变刷新率（VRR / G-SYNC / FreeSync）与 Windows「帧节奏控制」
 * 的作用对象。
 *
 * 上游 JetBrains/compose-multiplatform#1648「G-SYNC causes stuttering and flickering」
 * 记录了该组合的典型症状：VRR 开启且 Compose 窗口获焦时刷新率被显著拉低，移动鼠标
 * 即顿挫、面板闪烁。官方给出的规避手段就是换渲染后端。
 *
 * ## 时序约束（关键，别改坏）
 *
 * `SkikoProperties` 是 Kotlin `object`，**首次访问即固化**当时读到的系统属性，而首次
 * 访问发生在创建渲染器（`SkiaLayer`）时。因此 [applyBeforeSkikoInit] 必须在
 * `application { }` **之前**调用，否则属性写得再对也不生效。
 *
 * ## 取值来源（优先级从高到低）
 *
 * 1. 直接由 JVM 启动参数 / 环境变量指定（`-Dskiko.renderApi=...`、`SKIKO_RENDER_API`）
 *    ——视为外部显式指定，本模块**不覆盖**，只在日志里说明。
 * 2. `-Dcp.player.renderApi=...` / 环境变量 `CPPLAYER_RENDER_API`（自救通道，见下）。
 * 3. 设置页写入的持久化项（**含显式选择的「自动」**——一旦落盘，平台默认不再生效）。
 * 4. 平台默认：**Windows 上默认使用 OpenGL**（即上游针对 VRR 闪烁推荐的规避手段，
 *    用户没做过任何选择时就直接受益），其余平台不写任何属性、交给 Skiko 自行选择。
 *
 * 之所以让 1/2 压过持久化，是为了留一条**自救通道**：万一选到本机跑不起来的后端
 * （表现是启动即崩或窗口不出现，进不了设置页），仍可用 `-Dcp.player.renderApi=AUTO`
 * 启动，或直接改掉 `~/.cpplayer/cp_player_prefs.properties` 里的对应项。
 *
 * ## 安全模式（自动回退）
 *
 * 上面那条自救通道要求用户会加 JVM 启动参数或会手改配置文件——而应用**起不来**的时候，
 * 恰恰是普通用户最没法做这两件事的时候。所以再加一层自动兜底：
 *
 * 1. 应用了「设置页选定的非默认后端」或「平台默认 OpenGL」时，在其它初始化都跑完之后
 *    （[beginStartupProbe]）写一个探测文件；
 * 2. 窗口连续出满 [HEALTHY_FRAME_COUNT] 帧后，由 `Main` 调 [markStartupHealthy] 删掉它；
 * 3. 下次启动若探测文件**还在**，说明那个后端没能起来 → 自动改回「自动」并在设置页提示。
 *
 * 判断逻辑见 [abandonedBackend] 与 [shouldArmProbe]，都是纯函数、可测。
 *
 * 允许的取值来自 Skiko `GraphicsApi` 枚举（`UNKNOWN / SOFTWARE_FAST / SOFTWARE_COMPAT /
 * OPENGL / ANGLE / VULKAN / METAL / WEBGL / DIRECT3D`）中 `parseRenderApi` 真正接受的子集。
 */
internal object DesktopRenderTuning {

    /** 持久化键（写入 `~/.cpplayer/cp_player_prefs.properties`）。 */
    const val KEY_BACKEND = "desktop_render_api"
    const val KEY_VSYNC = "desktop_vsync_override"

    /** 记录「上次启动因某个后端起不来而被自动回退」，供设置页提示用户。 */
    const val KEY_LAST_REVERT = "desktop_render_api_last_revert"

    /** 探测文件名（与配置文件同目录）。语义见 [applyBeforeSkikoInit]。 */
    private const val PROBE_FILE_NAME = "render_tuning_probe"

    /**
     * 探测文件路径的覆写点，**仅供测试隔离**（让测试不碰真实 `~/.cpplayer/`）。
     * 生产环境不需要设置。
     */
    private const val PROP_PROBE_FILE = "cp.player.probeFile"

    /**
     * 认定「该后端在本机可用」所需连续呈现的帧数。
     *
     * 取 3 帧是因为：能走到数帧这一步，说明 SkiaLayer 已创建、Composition 已跑起来、
     * 渲染循环在转 —— 三者任一失败都不会走到这里。3 帧在 60 Hz 下约 50 ms，不会拖慢启动。
     */
    const val HEALTHY_FRAME_COUNT = 3

    /** 自救通道用的启动参数 / 环境变量名。 */
    private const val OVERRIDE_BACKEND = "cp.player.renderApi"
    private const val OVERRIDE_VSYNC = "cp.player.vsync"
    private const val ENV_BACKEND = "CPPLAYER_RENDER_API"
    private const val ENV_VSYNC = "CPPLAYER_VSYNC"

    /** Skiko 自己认的属性名。 */
    private const val PROP_RENDER_API = "skiko.renderApi"
    private const val PROP_VSYNC = "skiko.vsync.enabled"

    /** Skiko 官方环境变量，存在即代表外部已显式指定。 */
    private const val ENV_SKIKO_RENDER_API = "SKIKO_RENDER_API"

    /**
     * 可选渲染后端。
     *
     * `value` 为写入 `skiko.renderApi` 的字面量；[AUTO] 用空串表示「不干预」。
     */
    enum class Backend(val value: String, val label: String, val note: String) {
        AUTO(
            "",
            "自动（跟随 Skiko 默认）",
            "不写 skiko.renderApi，交给 Skiko 决定（Windows 上即 Direct3D 12）。性能最好；与 VRR 冲突时会出现刷新率抖动与闪烁。",
        ),
        OPENGL(
            "OPENGL",
            "OpenGL",
            "绕开 D3D 呈现路径，上游针对 VRR 问题推荐的规避手段。Windows 上未做选择时的平台默认。",
        ),
        ANGLE(
            "ANGLE",
            "ANGLE（OpenGL ES over D3D11）",
            "走 D3D11 转译层，介于默认与 OpenGL 之间，可作第二选择。",
        ),
        DIRECT3D(
            "DIRECT3D",
            "Direct3D 12（强制）",
            "显式强制 D3D12，用于与「自动」对照，确认默认确实走了这条路径。",
        ),
        SOFTWARE_COMPAT(
            "SOFTWARE_COMPAT",
            "软件渲染（兼容）",
            "完全不经过 GPU，最稳但最慢，仅用于判断问题是否出在 GPU 呈现路径。",
        ),
        SOFTWARE_FAST(
            "SOFTWARE_FAST",
            "软件渲染（快速）",
            "软件光栅快速路径，仅用于诊断。",
        ),
        ;

        companion object {
            /** 按存储值解析，未知值回落到 [AUTO]。 */
            fun fromStorage(raw: String?): Backend =
                entries.firstOrNull { it.name == raw } ?: AUTO
        }
    }

    /**
     * 本次运行后端取值的实际来源。
     *
     * 之所以要记下来，是因为外部启动参数/环境变量**优先于设置页**：此时用户改设置页是无效的，
     * 若还提示「重启后生效」就是在骗人。见 [isRestartPending]。
     *
     * [SETTINGS] 表示持久化文件里**有**用户的选择（包括显式选择的「自动」）；
     * [DEFAULT] 表示用户从未做过选择，本次取的是平台默认（Windows=OpenGL，其余=Skiko 默认）。
     * 两者的区别决定要不要立探测字据（见 [shouldArmProbe]）。
     */
    internal enum class BackendSource { EXTERNAL, OVERRIDE, SETTINGS, DEFAULT }

    /**
     * [applyBeforeSkikoInit] 记录下来的来源与取值。
     *
     * `null` 表示还没跑过（例如单元测试直接调其它函数），此时 [isRestartPending] 保守地返回 false。
     */
    @Volatile
    private var appliedSource: BackendSource? = null

    @Volatile
    private var appliedBackend: Backend? = null

    private val prefs by lazy { defaultSettingsStorage() }

    // ======================== 持久化 ========================

    /** 本进程是否跑在 Windows 上。 */
    internal val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    /**
     * 平台默认后端：**Windows 上默认 OpenGL**（上游针对 VRR 闪烁推荐的规避手段，
     * 让用户不做任何选择就默认避开 Direct3D 12 的呈现路径问题）；其余平台不干预、
     * 交给 Skiko 自行选择。
     */
    fun platformDefaultBackend(): Backend = if (isWindows) Backend.OPENGL else Backend.AUTO

    /**
     * 设置页展示 / 生效的后端取值。
     *
     * 持久化文件里没有值时返回**平台默认**（[platformDefaultBackend]）。注意这与
     * 「显式选择自动」不同：显式 AUTO 会以 `"AUTO"` 字符串落盘（见 [storeBackend]），
     * 从而跳过平台默认、真正交给 Skiko 决定。
     */
    fun storedBackend(): Backend {
        val raw = prefs.getString(KEY_BACKEND) ?: return platformDefaultBackend()
        return Backend.fromStorage(raw)
    }

    fun storeBackend(backend: Backend) {
        // 「自动」也要显式落盘而不是删键：删键 == 「从未选择」，会让 Windows 上的
        // 平台默认（OpenGL）重新生效。显式落盘才能表达「用户就是要 Skiko 默认」，
        // 安全模式的自动回退也依赖这一点来跳出「默认 → 崩 → 回退 → 又默认」的循环。
        prefs.putString(KEY_BACKEND, backend.name)
    }

    /** `null` 表示不干预垂直同步，保持 Skiko 默认。 */
    fun storedVsyncOverride(): Boolean? = when (prefs.getString(KEY_VSYNC)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    fun storeVsyncOverride(value: Boolean?) {
        if (value == null) prefs.remove(KEY_VSYNC) else prefs.putString(KEY_VSYNC, value.toString())
    }

    /** 上次被自动回退掉的后端；`null` 表示没有发生过。 */
    fun lastRevertedBackend(): Backend? {
        val raw = prefs.getString(KEY_LAST_REVERT) ?: return null
        return Backend.entries.firstOrNull { it.name == raw }
    }

    /** 用户做出新的选择后调用，清掉历史回退提示，避免旧警告一直挂着。 */
    fun clearRevertNote() = prefs.remove(KEY_LAST_REVERT)

    // ======================== 安全模式（探测文件） ========================

    /**
     * 探测文件路径。
     *
     * 允许用 `-Dcp.player.probeFile=...` 覆写，**只为给测试提供隔离点**，
     * 免得测试去动真实 `~/.cpplayer/`。生产环境不需要设置。
     */
    private fun probeFile(): File =
        System.getProperty(PROP_PROBE_FILE)?.let(::File)
            ?: DesktopDataDir.file(PROBE_FILE_NAME)

    /**
     * 纯逻辑：由探测文件内容判断「上次启动是不是卡在/崩在某个后端上」。
     *
     * 探测文件只在**我们主动应用了设置页选定的非默认后端**时写入，成功出帧后删除。
     * 所以它能活到下一次启动，就只有一种解释：那个后端在本机起不来。
     *
     * @param probeRaw 探测文件内容；`null` 表示文件不存在（上次正常起来了）。
     * @return 需要回退的后端；`null` 表示无需回退。
     */
    internal fun abandonedBackend(probeRaw: String?): Backend? {
        val needle = probeRaw?.trim()?.uppercase().orEmpty()
        if (needle.isEmpty() || needle == Backend.AUTO.name) return null
        return Backend.entries.firstOrNull { it.name == needle }
    }

    /** 读探测文件内容；不存在或读失败都返回 `null`（读不到就当没发生过，不误伤）。 */
    internal fun readProbeFrom(file: File): String? =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    /** 写探测文件；失败不抛（探测只是尽力而为的安全网，不能反过来把启动搞崩）。 */
    internal fun writeProbeTo(file: File, backend: Backend) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(backend.name)
        }
    }

    private fun readProbe(): String? = readProbeFrom(probeFile())

    private fun clearProbe() {
        runCatching { probeFile().delete() }
    }

    /**
     * 纯逻辑：本次启动该不该为渲染后端「立字据」。
     *
     * 「设置页选定的非默认后端」与「平台默认 OpenGL」都该记账——后者在用户没做过
     * 任何选择时就会生效，一旦在本机跑不起来，同样会陷入「起不来 → 进不去设置页」
     * 的循环，必须能自动回退（回退会显式落盘 `AUTO`，从而把平台默认一并关掉）。
     * 不该记账的：
     * - 外部启动参数 / `cp.player.renderApi` 每次都压过设置页，回退持久化值也救不了它，记账只会误报；
     * - `AUTO` 本身没什么可失败的。
     */
    internal fun shouldArmProbe(source: BackendSource?, backend: Backend?): Boolean =
        backend != null && backend != Backend.AUTO &&
            (source == BackendSource.SETTINGS || source == BackendSource.DEFAULT)

    /**
     * 立字据：本次以设置页选定的非默认后端启动，需要 [markStartupHealthy] 来销账。
     *
     * 由 `Main` 在 `application { }` 内、**其它初始化之后**调用。
     * 放在这个位置是有讲究的：若提前到 [applyBeforeSkikoInit] 就写，那么
     * `MusicBackend.init` 里音频原生库加载失败一类的**无关**崩溃，也会被算到渲染后端头上，
     * 导致下次启动无端回退用户的设置并给出误导性提示。
     */
    fun beginStartupProbe() {
        if (shouldArmProbe(appliedSource, appliedBackend)) {
            writeProbeTo(probeFile(), appliedBackend!!)
        }
    }

    /**
     * 由 `Main` 在窗口连续成功出帧后调用，宣告「这个后端跑得起来」。
     *
     * 没被调用就等于探测文件留到下次启动 → 自动回退。见 [abandonedBackend]。
     */
    fun markStartupHealthy() = clearProbe()

    // ======================== 生效值（只读，供 UI / 日志展示） ========================

    /** 本次运行写进 `skiko.renderApi` 的原始值；`null` 表示未设置（完全交给 Skiko）。 */
    fun requestedApiRaw(): String? = System.getProperty(PROP_RENDER_API)

    /**
     * 「请求值」的人类可读描述，供 UI / 日志展示。
     *
     * 刻意**不**把无法识别的取值折叠成「自动」。外部完全可以用遗留别名（`SOFTWARE`、
     * `DIRECT_SOFTWARE`）或 Skiko 未来新增的取值来指定后端；若这里一律显示成「自动」，
     * 对照实验会得出完全相反的结论。识别不了就如实把原始值摆出来。
     */
    fun requestedApiSummary(): String {
        val raw = requestedApiRaw() ?: return "自动（未设置 $PROP_RENDER_API，交给 Skiko 决定）"
        val matched = Backend.entries.firstOrNull { it.value == raw }
        return if (matched != null) {
            "${matched.label} → $PROP_RENDER_API=$raw"
        } else {
            "$PROP_RENDER_API=$raw（本模块未识别的取值）"
        }
    }

    /** 本次运行是否主动干预了垂直同步。 */
    fun effectiveVsyncOverride(): Boolean? = System.getProperty(PROP_VSYNC)?.toBooleanStrictOrNull()

    /**
     * 纯逻辑：设置页保存的选择与本次运行实际请求的后端是否不一致（即需要重启才生效）。
     *
     * @param runningApi 本次运行写进 `skiko.renderApi` 的值；`null`/空串表示未设置（= 自动）。
     * @param stored 设置页持久化的选择。
     * @param externalOverride 本次取值是否来自外部启动参数/环境变量。
     */
    internal fun restartPending(runningApi: String?, stored: Backend, externalOverride: Boolean): Boolean {
        // 外部指定优先于设置页，此时改设置页根本不会生效，提示「重启后生效」是误导。
        if (externalOverride) return false
        return runningApi.orEmpty() != stored.value
    }

    /** [restartPending] 的运行时取值版本。 */
    fun isRestartPending(): Boolean = restartPending(
        runningApi = requestedApiRaw(),
        stored = storedBackend(),
        externalOverride = appliedSource == BackendSource.EXTERNAL || appliedSource == BackendSource.OVERRIDE,
    )

    /**
     * Skiko 解析后的**真实**渲染 API。
     *
     * 与 [requestedApiSummary] 的区别在于：Skiko 在本机不支持所选后端时会**静默回退**，
     * 所以「请求值」不等于「实际值」。做对照实验时以本值为准。
     *
     * 用反射读取是为了不依赖 `skiko-awt` 的编译期可见性，避免版本变动导致编译失败。
     */
    fun resolvedSkikoApi(): String = runCatching {
        val clazz = Class.forName("org.jetbrains.skiko.SkikoProperties")
        val instance = clazz.getField("INSTANCE").get(null)
        clazz.getMethod("getRenderApi").invoke(instance).toString()
    }.getOrDefault("(无法读取)")

    /** 持久化文件位置，用于在 UI / 日志里告诉用户「去哪改回来」。 */
    fun storageHint(): String = DesktopDataDir.file("cp_player_prefs.properties").absolutePath

    // ======================== 应用（必须在 Skiko 初始化前） ========================

    /**
     * 解析最终取值并写入 `skiko.*` 系统属性，然后打印一段可直接用于对照实验的日志。
     *
     * **必须在 `application { }` 之前调用。** 见类注释的时序约束。
     */
    fun applyBeforeSkikoInit() {
        // ---- 第 0 步：安全模式。必须赶在读取设置之前，否则会拿着「已判定不可用」的值继续用。----
        recoverFromFailedLaunch()

        val externalApi = System.getProperty(PROP_RENDER_API) ?: System.getenv(ENV_SKIKO_RENDER_API)
        val overrideRaw = System.getProperty(OVERRIDE_BACKEND) ?: System.getenv(ENV_BACKEND)
        val hasStoredChoice = prefs.getString(KEY_BACKEND) != null
        val stored = storedBackend()

        val backend = when {
            // 1) 外部已显式指定 skiko.renderApi：尊重它，不覆盖。
            externalApi != null -> null
            // 2) 自救通道：启动参数 / 环境变量。
            overrideRaw != null -> resolveByName(overrideRaw)
            // 3) 设置页持久化项（无持久化值时 [storedBackend] 已回落到平台默认）。
            else -> stored
        }
        val source = when {
            externalApi != null -> BackendSource.EXTERNAL
            overrideRaw != null -> BackendSource.OVERRIDE
            // 持久化文件里有用户的选择（含显式 AUTO）→ 设置页；否则 → 平台默认。
            hasStoredChoice -> BackendSource.SETTINGS
            else -> BackendSource.DEFAULT
        }
        appliedSource = source
        appliedBackend = backend

        if (backend != null && backend != Backend.AUTO) {
            System.setProperty(PROP_RENDER_API, backend.value)
        }

        val externalVsync = System.getProperty(PROP_VSYNC)
        val vsyncOverride = when {
            externalVsync != null -> null
            System.getProperty(OVERRIDE_VSYNC) != null || System.getenv(ENV_VSYNC) != null ->
                (System.getProperty(OVERRIDE_VSYNC) ?: System.getenv(ENV_VSYNC))
                    ?.toBooleanStrictOrNull()
            else -> storedVsyncOverride()
        }
        vsyncOverride?.let { System.setProperty(PROP_VSYNC, it.toString()) }

        val sourceLabel = when (source) {
            BackendSource.EXTERNAL -> "外部启动参数（不干预）"
            BackendSource.OVERRIDE -> "启动参数/环境变量"
            BackendSource.SETTINGS -> "设置页"
            BackendSource.DEFAULT -> "平台默认（未做过选择）"
        }
        val requestedLabel = when {
            externalApi != null -> "$externalApi（外部指定，本模块未干预）"
            backend == null || backend == Backend.AUTO -> "自动（未设置 $PROP_RENDER_API，交给 Skiko）"
            else -> "${backend.label} → $PROP_RENDER_API=${backend.value}（来源：$sourceLabel）"
        }
        println(
            buildString {
                appendLine("[RenderTuning] os=${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
                appendLine("[RenderTuning] 渲染后端请求值：$requestedLabel")
                appendLine(
                    "[RenderTuning] 垂直同步：" + when {
                        externalVsync != null -> "外部指定 $PROP_VSYNC=$externalVsync（本模块未干预）"
                        vsyncOverride != null -> "$PROP_VSYNC=$vsyncOverride"
                        else -> "未干预（Skiko 默认）"
                    },
                )
                append("[RenderTuning] 安全模式：若所选后端跑不起来（启动即崩或窗口不出现），下次启动会自动回退为「自动」。")
                append("手工干预：-D$OVERRIDE_BACKEND=AUTO，或删除 ${storageHint()} 中的 $KEY_BACKEND / $KEY_VSYNC 项。")
            },
        )
    }

    /**
     * 安全模式：处理上一次启动留下的探测文件。
     *
     * 探测文件存在 ⟹ 上次以某个「设置页选定的非默认后端」启动，却没撑到出帧
     * （启动即崩 / 窗口不出现 / 渲染循环卡死）⟹ 该后端在本机不可用。
     * 此时自动改回「自动」并记一笔提示，避免用户陷入
     * 「选一次崩一次，而设置页恰恰进不去」的死循环。
     *
     * 为什么用文件而不是进程内 try/catch：后端不可用时往往在**原生层**直接崩，
     * 进程内捕获不到；只有落在磁盘上的证据能跨进程存活。
     */
    private fun recoverFromFailedLaunch() {
        val raw = readProbe()
        val abandoned = abandonedBackend(raw)
        if (abandoned != null) {
            // 顺序要紧：先落回退值再写提示。storeBackend 不碰 KEY_LAST_REVERT，二者互不干扰。
            // 回退值会以 "AUTO" **显式落盘**（storeBackend 不再删键）：这正是关掉平台默认的开关——
            // 否则 Windows 上删键后下下次启动又会默认 OpenGL，陷入「默认 → 崩 → 回退 → 又默认」的循环。
            storeBackend(Backend.AUTO)
            prefs.putString(KEY_LAST_REVERT, abandoned.name)
            clearProbe()
            println(
                "[RenderTuning] 上次以「${abandoned.label}」启动未能正常出帧，已自动回退为「自动」。" +
                    "该后端在本机可能不受支持，建议先更新显卡驱动再试。",
            )
        } else if (raw != null) {
            // 文件在、但内容识别不了（多半被截断或为空）：清掉，免得下次继续误判。
            clearProbe()
        }
    }

    private fun resolveByName(raw: String?): Backend {
        val needle = raw?.trim()?.uppercase() ?: return Backend.AUTO
        if (needle.isEmpty() || needle == "AUTO" || needle == "DEFAULT") return Backend.AUTO
        return Backend.entries.firstOrNull { it.name == needle || it.value == needle } ?: Backend.AUTO
    }
}
