package cp.player.core.playback

import cp.player.core.util.SettingsStorage

/**
 * Flick 引擎**逐项特性开关**的持久化契约。
 *
 * ### 为什么要有这一层
 * 引擎（`native/flick-player-engine`）里很多能力是「有就必须一直开」的：直连 HTTP 流、
 * 无缝队列、原生交叉淡化、动态响度、DSD 输出模式。它们在此之前**没有用户开关** ——
 * 宿主只能按 `apiLevel` 一刀切（见 docs/dev/FLICK_ENGINE.md §1.1），于是：
 * - 用户想排查「换直连流之后某些音源播不动」时无从下手；
 * - Linux / 某些 ALSA 设备上 DSD 的 DoP 行为不一致，却没有地方降级；
 * - 动态响度挂在链上会改变听感，不该由程序替用户默认锚定。
 *
 * 这里只放**键与默认值**（磁盘契约）与**运行时快照**（[FlickEngineToggles]）。
 * 真正下发引擎/影响宿主路径的判断在 `DesktopHiResPlatformPlayer`。
 *
 * ### 默认值的原则
 * 除 DSD（Auto）外全部 **默认开**：这些能力是引擎本来的行为，关掉只是排障/降级手段。
 * 默认关会让「升级后音质变差」变成一次静默回归。
 */
object FlickEngineSettings {

    /** 直连 HTTP(S) 流（边下边播 + Range seek）。关掉则退回「整首落盘再播」。 */
    const val KEY_DIRECT_STREAM = "flick_engine_direct_stream"
    const val DEFAULT_DIRECT_STREAM = true

    /** 无缝队列（gapless）：把下一首提前交给引擎，双源衔接。关掉走「播完再 load」。 */
    const val KEY_GAPLESS_QUEUE = "flick_engine_gapless_queue"
    const val DEFAULT_GAPLESS_QUEUE = true

    /** 原生交叉淡化（双源重叠）。关掉则交回宿主的音量斜坡（PlaybackFadeController）。 */
    const val KEY_NATIVE_CROSSFADE = "flick_engine_native_crossfade"
    const val DEFAULT_NATIVE_CROSSFADE = true

    /** 动态响度处理（压缩器 + 限幅器）。关掉则音效页不再暴露响度均衡。 */
    const val KEY_DYNAMICS = "flick_engine_dynamics"
    const val DEFAULT_DYNAMICS = true

    /** DSD 输出模式。取值见 [DSD_MODE_PCM] … [DSD_MODE_AUTO]。 */
    const val KEY_DSD_MODE = "flick_engine_dsd_mode"

    /** PCM 抽取（软解到 PCM，兼容一切设备）。 */
    const val DSD_MODE_PCM = 0

    /** DoP（DSD over PCM，需要 DAC 支持）。 */
    const val DSD_MODE_DOP = 1

    /** Native DSD（需要驱动/固件直通）。 */
    const val DSD_MODE_NATIVE = 2

    /** 自动（引擎按设备能力挑，**默认**）。 */
    const val DSD_MODE_AUTO = 3

    const val DEFAULT_DSD_MODE = DSD_MODE_AUTO

    /** 读取布尔开关；缺失或非法值回落到默认值。 */
    private fun readBool(storage: SettingsStorage, key: String, default: Boolean): Boolean =
        storage.getString(key)?.toBooleanStrictOrNull() ?: default

    fun directStream(storage: SettingsStorage): Boolean =
        readBool(storage, KEY_DIRECT_STREAM, DEFAULT_DIRECT_STREAM)

    fun gaplessQueue(storage: SettingsStorage): Boolean =
        readBool(storage, KEY_GAPLESS_QUEUE, DEFAULT_GAPLESS_QUEUE)

    fun nativeCrossfade(storage: SettingsStorage): Boolean =
        readBool(storage, KEY_NATIVE_CROSSFADE, DEFAULT_NATIVE_CROSSFADE)

    fun dynamics(storage: SettingsStorage): Boolean =
        readBool(storage, KEY_DYNAMICS, DEFAULT_DYNAMICS)

    /** 读取 DSD 输出模式；越界值夹到合法区间，非法值回落 [DEFAULT_DSD_MODE]。 */
    fun dsdMode(storage: SettingsStorage): Int =
        storage.getString(KEY_DSD_MODE)?.toIntOrNull()?.coerceIn(DSD_MODE_PCM, DSD_MODE_AUTO)
            ?: DEFAULT_DSD_MODE
}

/**
 * 引擎特性开关的**运行时快照**。
 *
 * 播放链路上的能力位（`supportsGaplessQueue` / `supportsSeekableRemoteStreams` /
 * `supportsNativeCrossfade` / `supportsLeveling`）是**高频 getter**（每次预加载、每次
 * 拖进度都会读），不能每次去碰 `SettingsStorage`（全量文件读）。所以磁盘是事实源，
 * 这里是它的只读镜像：
 *
 * ```
 * AppModel.setXxx()  ──写盘──▶  FlickEngineToggles.xxx（立即生效）
 * DesktopEngineManager.createPlayer(storage) ──applyFrom──▶ 同上（冷启动/换引擎时对齐）
 * ```
 *
 * 默认值与 [FlickEngineSettings] 保持一致 —— 快照未加载时等价于「全部默认」，
 * 不会出现「读不到盘就静默关掉引擎能力」。
 */
object FlickEngineToggles {

    @Volatile
    var directStream: Boolean = FlickEngineSettings.DEFAULT_DIRECT_STREAM

    @Volatile
    var gaplessQueue: Boolean = FlickEngineSettings.DEFAULT_GAPLESS_QUEUE

    @Volatile
    var nativeCrossfade: Boolean = FlickEngineSettings.DEFAULT_NATIVE_CROSSFADE

    @Volatile
    var dynamics: Boolean = FlickEngineSettings.DEFAULT_DYNAMICS

    @Volatile
    var dsdMode: Int = FlickEngineSettings.DEFAULT_DSD_MODE
        private set

    /**
     * DSD 模式的**下发器**，由 `DesktopHiResEngine` 在类初始化时注册。
     *
     * 这样 [applyDsdMode] 不必让 commonMain 反向依赖桌面实现：引擎没加载时是 null，
     * 纯记录值；引擎在时立刻下发（引擎未 init 则由它自己在 init 后补发）。
     */
    @Volatile
    private var dsdApplier: ((Int) -> Unit)? = null

    fun registerDsdApplier(block: (Int) -> Unit) {
        dsdApplier = block
    }

    /** 从磁盘加载全部开关（冷启动 / 引擎重建时调用）。 */
    fun applyFrom(storage: SettingsStorage) {
        directStream = FlickEngineSettings.directStream(storage)
        gaplessQueue = FlickEngineSettings.gaplessQueue(storage)
        nativeCrossfade = FlickEngineSettings.nativeCrossfade(storage)
        dynamics = FlickEngineSettings.dynamics(storage)
        dsdMode = FlickEngineSettings.dsdMode(storage)
    }

    /** 切换 DSD 模式：先更新快照，再尝试立刻下发给已初始化的引擎。 */
    fun applyDsdMode(mode: Int) {
        val clamped = mode.coerceIn(FlickEngineSettings.DSD_MODE_PCM, FlickEngineSettings.DSD_MODE_AUTO)
        dsdMode = clamped
        dsdApplier?.invoke(clamped)
    }
}
