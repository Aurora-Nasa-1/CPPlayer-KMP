package cp.player.kmp.local

/**
 * 本地音乐源接口（KMP 版）。
 *
 * 对标旧项目的 `LocalMusicManager`，但抽象为平台无关接口。
 * 当前版本仅定义探索/扫描能力；具体实现由各平台 actual 或后续版本提供。
 *
 * ### 设计意图
 * [MusicBackend] 将本地音乐与云音乐统一在同一"音乐源"抽象下，
 * 前端通过 [MusicSource] 接口访问，无需区分数据来自云端还是本地。
 *
 * @see cp.player.kmp.music.MusicSource 统一音乐源
 */
interface LocalMusicSource {

    /**
     * 扫描指定目录下的音乐文件。
     *
     * @param directory 扫描根目录绝对路径；null 时使用平台默认音乐目录
     * @return 扫描到的本地音乐元信息列表
     */
    suspend fun scan(directory: String? = null): List<LocalSongMetadata>

    /** 获取上次扫描结果（缓存，不触发新扫描）。 */
    fun cached(): List<LocalSongMetadata>

    /** 是否正在扫描（实现可维护一个 StateFlow 或简单布尔）。默认 false。 */
    val isScanning: Boolean get() = false

    companion object
}

/**
 * 本地音乐元信息（最小子集，避免耦合解析库）。
 *
 * 文件路径即唯一标识。
 */
data class LocalSongMetadata(
    val path: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
)