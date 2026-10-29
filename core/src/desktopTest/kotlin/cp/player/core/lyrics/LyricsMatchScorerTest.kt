package cp.player.core.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsMatchScorerTest {

    @Test
    fun `exact match gets high score`() {
        val target = LyricsMatchScorer.Target(
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            durationMs = 269_000L,
        )
        val candidate = LyricsMatchScorer.Candidate(
            titles = listOf("晴天"),
            artists = listOf("周杰伦"),
            albums = listOf("叶惠美"),
            durationMs = 270_000L,
        )
        val score = LyricsMatchScorer.score(target, candidate)
        assertTrue(score >= 95, "Exact match score should be >= 95, got $score")
    }

    @Test
    fun `instrumental candidate gets heavy penalty for normal track`() {
        val target = LyricsMatchScorer.Target(
            title = "七里香",
            artist = "周杰伦",
            durationMs = 300_000L,
        )
        val normalCandidate = LyricsMatchScorer.Candidate(
            titles = listOf("七里香"),
            artists = listOf("周杰伦"),
            durationMs = 300_000L,
        )
        val instCandidate = LyricsMatchScorer.Candidate(
            titles = listOf("七里香 (伴奏)"),
            artists = listOf("周杰伦"),
            durationMs = 300_000L,
        )

        val normalScore = LyricsMatchScorer.score(target, normalCandidate)
        val instScore = LyricsMatchScorer.score(target, instCandidate)

        assertTrue(normalScore >= 90)
        assertTrue(instScore < LyricsMatchScorer.MIN_ACCEPTABLE_SCORE, "Instrumental score should be < 60, got $instScore")

        val best = LyricsMatchScorer.findBestMatch(target, listOf(instCandidate, normalCandidate)) { it }
        assertEquals(normalCandidate, best)
    }

    @Test
    fun `cover candidate gets penalized`() {
        val target = LyricsMatchScorer.Target(
            title = "起风了",
            artist = "买辣椒也用券",
            durationMs = 310_000L,
        )
        val coverCandidate = LyricsMatchScorer.Candidate(
            titles = listOf("起风了（Cover 翻唱）"),
            artists = listOf("某翻唱歌手"),
            durationMs = 310_000L,
        )
        val score = LyricsMatchScorer.score(target, coverCandidate)
        assertTrue(score < LyricsMatchScorer.MIN_ACCEPTABLE_SCORE)
    }

    @Test
    fun `same title but completely different artist gets rejected`() {
        val target = LyricsMatchScorer.Target(
            title = "晴天",
            artist = "周杰伦",
            durationMs = 269_000L,
        )
        val diffArtistCandidate = LyricsMatchScorer.Candidate(
            titles = listOf("晴天"),
            artists = listOf("刘德华"),
            durationMs = 269_000L,
        )
        val score = LyricsMatchScorer.score(target, diffArtistCandidate)
        assertTrue(score < LyricsMatchScorer.MIN_ACCEPTABLE_SCORE, "Different artist should be rejected, got $score")
    }

    @Test
    fun `huge duration discrepancy gets rejected`() {
        val target = LyricsMatchScorer.Target(
            title = "简单爱",
            artist = "周杰伦",
            durationMs = 270_000L, // 4分30秒
        )
        val cutVersionCandidate = LyricsMatchScorer.Candidate(
            titles = listOf("简单爱"),
            artists = listOf("周杰伦"),
            durationMs = 90_000L, // 1分30秒 铃声剪辑版
        )
        val score = LyricsMatchScorer.score(target, cutVersionCandidate)
        assertTrue(score < LyricsMatchScorer.MIN_ACCEPTABLE_SCORE, "Discrepant duration should be rejected, got $score")
    }

    @Test
    fun `findBestMatch picks true match and ignores inferior candidates`() {
        val target = LyricsMatchScorer.Target(
            title = "青花瓷",
            artist = "周杰伦",
            durationMs = 239_000L,
        )
        val candidates = listOf(
            LyricsMatchScorer.Candidate(listOf("青花瓷 (伴奏)"), listOf("周杰伦"), durationMs = 239_000L),
            LyricsMatchScorer.Candidate(listOf("青花瓷"), listOf("翻唱者A"), durationMs = 239_000L),
            LyricsMatchScorer.Candidate(listOf("青花瓷"), listOf("周杰伦"), durationMs = 240_000L),
            LyricsMatchScorer.Candidate(listOf("无关歌曲"), listOf("某某"), durationMs = 200_000L),
        )

        val best = LyricsMatchScorer.findBestMatch(target, candidates) { it }
        assertNotNull(best)
        assertEquals(candidates[2], best)
    }

    @Test
    fun `findBestMatch returns null when no candidate meets threshold`() {
        val target = LyricsMatchScorer.Target(
            title = "暗号",
            artist = "周杰伦",
            durationMs = 240_000L,
        )
        val candidates = listOf(
            LyricsMatchScorer.Candidate(listOf("完全不沾边的歌"), listOf("某歌手"), durationMs = 180_000L),
            LyricsMatchScorer.Candidate(listOf("暗号 (伴奏)"), listOf("未知"), durationMs = 100_000L),
        )

        val best = LyricsMatchScorer.findBestMatch(target, candidates) { it }
        assertNull(best)
    }
}
