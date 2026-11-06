package cp.player.core.provider

import kotlinx.serialization.Serializable

/**
 * 软件原生音质标准等级定义与阶梯。
 */
object StandardAudioQuality {
    const val STANDARD = "standard"
    const val HIGHER = "higher"
    const val EXHIGH = "exhigh"
    const val LOSSLESS = "lossless"
    const val HIRES = "hires"
    const val JYMASTER = "jymaster"
    const val SKY = "sky"
    const val DOLBY = "dolby"

    /**
     * 原生音质能力阶梯（从低到高递增）。
     * 用于跨音源切换时在目标音质缺失时进行就近平滑降级选择。
     */
    val LADDER: List<String> = listOf(
        STANDARD,
        HIGHER,
        EXHIGH,
        LOSSLESS,
        HIRES,
        JYMASTER,
        SKY,
        DOLBY
    )

    /** 获取原生音质阶梯权重（0 起步；未知返回 -1） */
    fun rankOf(quality: String): Int =
        LADDER.indexOfFirst { it.equals(quality, ignoreCase = true) }

    fun isValid(quality: String): Boolean = rankOf(quality) >= 0
}

/**
 * 音源自定义音质选项及其向原生音质的映射规范。
 *
 * @param key 音源上游端点实际接收的音质标识（如 "hi-res", "320k", "flac", "master" 等）
 * @param targetQuality 映射到的软件原生音质（如 "hires", "exhigh", "lossless", "standard" 等）
 * @param label 音源自定义前端展示名（可选，如 "Hi-Res 无损", "臻品母带"），为空时回退原生文案
 * @param bitrate 预估码率（可选，bps，如 320000, 999000）
 * @param format 预估容器/编码格式（可选，如 "flac", "mp3"）
 */
@Serializable
data class ProviderQualityOption(
    val key: String,
    val targetQuality: String,
    val label: String? = null,
    val bitrate: Int? = null,
    val format: String? = null,
) : cp.player.core.util.JavaSerializable

/**
 * 音质解析器：负责在软件原生音质偏好与音源具体参数之间双向解析，
 * 并处理音源切换、档位缺失时的平滑降级（保持用户偏好不变）。
 */
object AudioQualityResolver {

    /**
     * 将软件原生偏好音质解析为特定音源实际需要的参数 key。
     *
     * @param options 音源声明的音质选项列表；为 null 或空时直接原样返回 [targetQuality]
     * @param targetQuality 用户设定的原生目标音质（如 "hires", "lossless", "exhigh", "standard"）
     * @return 发给音源接口的实际 key（如 "hi-res", "flac", "320k"）
     */
    fun resolveKey(options: List<ProviderQualityOption>?, targetQuality: String): String {
        if (options.isNullOrEmpty()) return targetQuality

        // 1. 精确匹配：targetQuality 相同
        val exactMatch = options.firstOrNull {
            it.targetQuality.equals(targetQuality, ignoreCase = true)
        }
        if (exactMatch != null) return exactMatch.key

        // 2. 备选匹配：音源定义的 key 本身就等于 targetQuality
        val keyMatch = options.firstOrNull {
            it.key.equals(targetQuality, ignoreCase = true)
        }
        if (keyMatch != null) return keyMatch.key

        // 3. 阶梯就近降级策略：
        // 在音源支持的选项中，寻找 targetQuality 阶梯 rank <= 目标 rank 的最高档位；
        // 若全部高于目标 rank 或无法识别，退回音源支持的第一项（或最低项）。
        val targetRank = StandardAudioQuality.rankOf(targetQuality)
        if (targetRank >= 0) {
            val candidate = options
                .map { option -> option to StandardAudioQuality.rankOf(option.targetQuality) }
                .filter { (_, rank) -> rank in 0..targetRank }
                .maxByOrNull { (_, rank) -> rank }
                ?.first
            if (candidate != null) return candidate.key
        }

        // 4. 保底选择：首个可用选项
        return options.first().key
    }

    /**
     * 根据音源实际返回的 key 反查对应的配置选项。
     */
    fun resolveOption(options: List<ProviderQualityOption>?, actualKey: String): ProviderQualityOption? {
        if (options.isNullOrEmpty()) return null
        return options.firstOrNull { it.key.equals(actualKey, ignoreCase = true) }
            ?: options.firstOrNull { it.targetQuality.equals(actualKey, ignoreCase = true) }
    }

    /**
     * 将音源实际返回的 key 还原映射为软件原生音质等级。
     * 若未匹配到映射或未声明选项，原样返回 [actualKey]。
     */
    fun resolveTargetQuality(options: List<ProviderQualityOption>?, actualKey: String): String {
        return resolveOption(options, actualKey)?.targetQuality ?: actualKey
    }

    /**
     * 获取用于前端呈现的音质标签：优先使用音源自定义 label，缺失时回退默认文案。
     */
    fun getDisplayLabel(option: ProviderQualityOption?, defaultLabel: String): String {
        return option?.label?.takeIf { it.isNotBlank() } ?: defaultLabel
    }
}
