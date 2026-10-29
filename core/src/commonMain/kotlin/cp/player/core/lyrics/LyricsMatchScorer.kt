package cp.player.core.lyrics

import cp.player.core.util.MusicSearchSanitizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 歌词候选匹配打分器。
 *
 * 用于在 AMLL 搜索回退、第三方插件搜索等存在多个候选的场景中，
 * 综合评估歌名、歌手、专辑及歌曲时长，选出置信度最高的目标条目，
 * 并剔除伴奏、翻唱、Live 错配以及张冠李戴的同名歌曲。
 */
object LyricsMatchScorer {

    /** 最低可接受置信度门槛（满分 100 分，低于 60 分视为未命中）。 */
    const val MIN_ACCEPTABLE_SCORE = 60

    private val INSTRUMENTAL_REGEX = Regex("""(?i)(?:伴奏|instrumental|\binst\b|karaoke|off vocal|纯音乐)""")
    private val COVER_REGEX = Regex("""(?i)(?:翻唱|\bcover\b)""")
    private val LIVE_REGEX = Regex("""(?i)(?:现场|\blive\b|演唱会)""")

    data class Target(
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = 0L,
    )

    data class Candidate(
        val titles: List<String>,
        val artists: List<String> = emptyList(),
        val albums: List<String> = emptyList(),
        val durationMs: Long = 0L,
    )

    /**
     * 计算目标歌曲与候选条目的匹配得分（0 ~ 100 分）。
     */
    fun score(target: Target, candidate: Candidate): Int {
        val cleanTargetTitle = cleanText(target.title)
        if (cleanTargetTitle.isEmpty()) return 0

        val cleanTargetArtist = target.artist?.let { cleanText(it) }?.takeIf { it.isNotBlank() && !isUnknownArtist(it) }
        val cleanCandidateTitles = candidate.titles.map { cleanText(it) }.filter { it.isNotBlank() }
        val cleanCandidateArtists = candidate.artists.map { cleanText(it) }.filter { it.isNotBlank() }

        if (cleanCandidateTitles.isEmpty()) return 0

        // 1. 歌名得分 (0 ~ 45 分)
        val titleScore = cleanCandidateTitles.maxOf { cTitle ->
            computeTitleScore(cleanTargetTitle, cTitle)
        }

        // 2. 歌手得分 (0 ~ 35 分)
        val artistScore = if (cleanTargetArtist == null) {
            // 目标无歌手信息时（如本地文件标签缺失），给予中立分
            20
        } else if (cleanCandidateArtists.isEmpty()) {
            // 候选缺少歌手字段
            15
        } else {
            computeArtistScore(cleanTargetArtist, cleanCandidateArtists)
        }

        // 3. 时长得分 (0 ~ 20 分)
        var durationScore = 12 // 默认未知时长中立分
        var durationPenalty = 0
        if (target.durationMs > 0 && candidate.durationMs > 0) {
            val diff = abs(target.durationMs - candidate.durationMs)
            when {
                diff <= 2_500L -> durationScore = 20
                diff <= 5_000L -> durationScore = 16
                diff <= 10_000L -> durationScore = 10
                diff <= 20_000L -> durationScore = 4
                else -> {
                    durationScore = 0
                    if (diff > 30_000L) {
                        // 时长相差超过 30 秒，极大概率不是同一版本
                        durationPenalty = 30
                    }
                }
            }
        }

        // 4. 惩罚项计算（版本、伴奏、翻唱、Live 冲突）
        var penalty = durationPenalty

        val targetRawTitle = target.title
        val candidateAnyTitle = candidate.titles.joinToString(" ")

        // 伴奏惩罚：原曲非伴奏，而候选是伴奏
        val targetIsInst = INSTRUMENTAL_REGEX.containsMatchIn(targetRawTitle)
        val candIsInst = INSTRUMENTAL_REGEX.containsMatchIn(candidateAnyTitle)
        if (!targetIsInst && candIsInst) {
            penalty += 45
        }

        // 翻唱惩罚：原曲非翻唱，而候选标记为翻唱
        val targetIsCover = COVER_REGEX.containsMatchIn(targetRawTitle)
        val candIsCover = COVER_REGEX.containsMatchIn(candidateAnyTitle)
        if (!targetIsCover && candIsCover) {
            penalty += 40
        }

        // 现场版惩罚：原曲非 Live，而候选是 Live（或反之）
        val targetIsLive = LIVE_REGEX.containsMatchIn(targetRawTitle)
        val candIsLive = LIVE_REGEX.containsMatchIn(candidateAnyTitle)
        if (targetIsLive != candIsLive) {
            penalty += 20
        }

        // 歌手明确且完全不匹配时的大幅扣分（防止同名张冠李戴）
        if (cleanTargetArtist != null && cleanCandidateArtists.isNotEmpty() && artistScore <= 5) {
            penalty += 35
        }

        val total = titleScore + artistScore + durationScore - penalty
        return total.coerceIn(0, 100)
    }

    /**
     * 在候选列表中找出综合得分最高的条目。
     * 若最高分低于 [minScore]，则返回 null，避免错配。
     */
    fun <T> findBestMatch(
        target: Target,
        candidates: List<T>,
        minScore: Int = MIN_ACCEPTABLE_SCORE,
        candidateSelector: (T) -> Candidate,
    ): T? {
        if (candidates.isEmpty()) return null

        var bestItem: T? = null
        var bestScore = -1

        for (item in candidates) {
            val candidate = candidateSelector(item)
            val s = score(target, candidate)
            if (s > bestScore) {
                bestScore = s
                bestItem = item
            }
        }

        return if (bestScore >= minScore) bestItem else null
    }

    /**
     * 计算歌名得分 (0 ~ 45)。
     */
    private fun computeTitleScore(target: String, candidate: String): Int {
        if (target == candidate) return 45

        // 子串包含且长度接近
        if (target.contains(candidate) || candidate.contains(target)) {
            val minLen = min(target.length, candidate.length).toDouble()
            val maxLen = max(target.length, candidate.length).toDouble()
            val ratio = if (maxLen > 0) minLen / maxLen else 0.0
            return (35 + (ratio * 10)).toInt().coerceIn(35, 43)
        }

        // 编辑距离相似度
        val sim = similarity(target, candidate)
        return (sim * 45).toInt().coerceIn(0, 45)
    }

    /**
     * 计算歌手得分 (0 ~ 35)。
     */
    private fun computeArtistScore(target: String, candidates: List<String>): Int {
        var maxScore = 0
        for (c in candidates) {
            if (c == target) return 35
            // 包含主歌手，如 "周杰伦 feat. xxx" 与 "周杰伦"
            if (c.contains(target) || target.contains(c)) {
                val s = 30
                if (s > maxScore) maxScore = s
                continue
            }
            val sim = similarity(target, c)
            val s = (sim * 35).toInt()
            if (s > maxScore) maxScore = s
        }
        return maxScore
    }

    private fun cleanText(text: String): String {
        return text.lowercase()
            .replace(Regex("""[\s\p{Punct}·～~—_]+"""), "")
            .trim()
    }

    private fun isUnknownArtist(artist: String): Boolean {
        val trimmed = artist.trim().lowercase()
        return trimmed.isEmpty() ||
            trimmed == "<unknown>" ||
            trimmed == "unknown" ||
            trimmed == "unknown artist" ||
            trimmed == "未知" ||
            trimmed == "未知歌手" ||
            trimmed == "群星" ||
            trimmed == "various artists"
    }

    /**
     * Levenshtein 相似度 (0.0 ~ 1.0)。
     */
    private fun similarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val len1 = s1.length
        val len2 = s2.length
        if (len1 == 0 || len2 == 0) return 0.0

        val dp = Array(len1 + 1) { IntArray(len2 + 1) }
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        val maxLen = max(len1, len2)
        return 1.0 - (dp[len1][len2].toDouble() / maxLen)
    }
}
