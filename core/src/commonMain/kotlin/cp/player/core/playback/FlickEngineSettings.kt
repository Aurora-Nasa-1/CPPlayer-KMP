package cp.player.core.playback

import cp.player.core.util.SettingsStorage

/**
 * Flick 引擎硬件输出选项的持久化契约与运行时快照。
 *
 * 精简说明：
 * 此前这里的「直连流 / 无缝队列 / 原生交叉淡化 / 动态响度」开关与播放设置、
 * 音效设置（交叉淡化、预加载、响度均衡）重叠或仅用于内部排障，已移除冗余开关，
 * 仅保留真正属于引擎硬件层的 **DSD 输出模式**。
 */
object FlickEngineSettings {

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

    /** 读取 DSD 输出模式；越界值夹到合法区间，非法值回落 [DEFAULT_DSD_MODE]。 */
    fun dsdMode(storage: SettingsStorage): Int =
        storage.getString(KEY_DSD_MODE)?.toIntOrNull()?.coerceIn(DSD_MODE_PCM, DSD_MODE_AUTO)
            ?: DEFAULT_DSD_MODE
}

/**
 * Flick 引擎硬件配置的**运行时快照**。
 */
object FlickEngineToggles {

    @Volatile
    var dsdMode: Int = FlickEngineSettings.DEFAULT_DSD_MODE
        private set

    /**
     * DSD 模式的**下发器**，由各平台引擎在类初始化时注册。
     */
    @Volatile
    private var dsdApplier: ((Int) -> Unit)? = null

    fun registerDsdApplier(block: (Int) -> Unit) {
        dsdApplier = block
    }

    /** 从磁盘加载配置（冷启动 / 引擎重建时调用）。 */
    fun applyFrom(storage: SettingsStorage) {
        dsdMode = FlickEngineSettings.dsdMode(storage)
    }

    /** 切换 DSD 模式：先更新快照，再尝试立刻下发给已初始化的引擎。 */
    fun applyDsdMode(mode: Int) {
        val clamped = mode.coerceIn(FlickEngineSettings.DSD_MODE_PCM, FlickEngineSettings.DSD_MODE_AUTO)
        dsdMode = clamped
        dsdApplier?.invoke(clamped)
    }
}
