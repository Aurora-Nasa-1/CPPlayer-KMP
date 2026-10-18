package cp.player.app.ui.wall

import cp.player.core.playback.SyncedLyricLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Split visual words without losing the timing of syllables inside an English word or CJK text. */
internal fun posterLyricWords(line: SyncedLyricLine, nextLine: SyncedLyricLine? = null): List<SyncedLyricLine> {
    val sourceText = line.text.ifBlank { "♪" }
    val words = line.words.ifEmpty {
        val nextStart = nextLine?.time ?: (line.time + 4000L)
        val end = line.endTime ?: nextStart
        listOf(SyncedLyricLine.SyncedWord(sourceText, line.time, end))
    }
    val text = words.joinToString("") { it.text }
    val ranges = posterWordRanges(text)
    var wordOffset = 0
    val timedRanges = words.map { word ->
        val start = wordOffset
        wordOffset += word.text.length
        Triple(start, wordOffset, word)
    }
    return ranges.map { range ->
        val timed = timedRanges.mapNotNull { (start, end, word) ->
            val from = maxOf(start, range.first)
            val to = minOf(end, range.last + 1)
            if (from >= to) return@mapNotNull null
            val duration = (word.endTime - word.beginTime).coerceAtLeast(0L)
            val length = word.text.length.coerceAtLeast(1)
            fun at(offset: Int) = word.beginTime + duration * offset / length
            SyncedLyricLine.SyncedWord(text.substring(from, to), at(from - start), at(to - start))
        }
        val startTime = timed.minOfOrNull { it.beginTime } ?: line.time
        val endTime = timed.maxOfOrNull { it.endTime } ?: (line.endTime ?: (startTime + 3000L))
        line.copy(
            time = startTime,
            text = text.substring(range),
            words = timed,
            endTime = endTime,
            translation = null,
            romanization = null
        )
    }
}

internal fun posterWordRanges(text: String): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var groupStart = -1
    var i = 0
    fun flush(end: Int) {
        if (groupStart >= 0 && end > groupStart) {
            ranges += groupStart until end
        }
        groupStart = -1
    }
    while (i < text.length) {
        val ch = text[i]
        val isSurrogate = ch.isHighSurrogate() && i + 1 < text.length
        val charLen = if (isSurrogate) 2 else 1
        val codePoint = if (isSurrogate) {
            ((ch.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) + 0x10000
        } else ch.code

        val isCjkOrEmoji = (codePoint in 0x4E00..0x9FFF) ||
            (codePoint in 0x3400..0x4DBF) ||
            (codePoint in 0x3040..0x309F) ||
            (codePoint in 0x30A0..0x30FF) ||
            (codePoint in 0xAC00..0xD7AF) ||
            (codePoint in 0xF900..0xFAFF) ||
            (codePoint >= 0x1F000)

        if (ch.isWhitespace()) {
            flush(i)
        } else if (isCjkOrEmoji) {
            flush(i)
            ranges += i until (i + charLen)
        } else if (!ch.isLetterOrDigit() && ch != '\'' && ch != '’' && groupStart < 0 && ranges.isNotEmpty()) {
            val last = ranges.removeAt(ranges.lastIndex)
            ranges += last.first until (i + charLen)
        } else {
            if (groupStart < 0) groupStart = i
        }
        i += charLen
    }
    flush(text.length)
    return ranges
}

internal data class PosterOrbitFrame(val x: Float, val y: Float, val depth: Float, val rotationY: Float) {
    val scale: Float get() = 0.68f + depth * 0.32f
    val alpha: Float get() = 0.22f + depth * 0.78f
}

internal const val POSTER_ORBIT_AXIS_DEGREES = 45f

/** Rotate positions around the shared scene center; keep glyphs upright and readable. */
internal fun PosterOrbitFrame.onPosterAxis(): PosterOrbitFrame {
    val radians = POSTER_ORBIT_AXIS_DEGREES * PI.toFloat() / 180f
    return copy(
        x = x * cos(radians) - y * sin(radians),
        y = x * sin(radians) + y * cos(radians)
    )
}

internal fun posterOrbitDimensions(width: Float, height: Float, wordWidth: Float, wordHeight: Float): Pair<Float, Float> {
    val available = minOf((width - wordWidth) / 2f, (height - wordHeight) / 2f).coerceAtLeast(0f)
    val extent = available * sqrt(2f)
    return extent * 0.36f to extent * 1.28f
}

/** A helix on a cylinder around the same vertical axis drawn by the scene backdrop. */
internal fun posterOrbitFrame(index: Int, count: Int, seconds: Float, radius: Float, height: Float): PosterOrbitFrame {
    val angle = seconds * 0.48f + index * (2f * PI.toFloat() / count.coerceIn(4, 8))
    val depth = (cos(angle) + 1f) / 2f
    return PosterOrbitFrame(
        x = sin(angle) * radius,
        y = if (count <= 1) 0f else (index.toFloat() / (count - 1) - 0.5f) * height,
        depth = depth,
        rotationY = sin(angle) * 48f
    )
}

internal data class PosterWordSize(val width: Float, val height: Float)
internal data class PosterScatteredWord(val x: Float, val y: Float, val rotation: Float, val phase: Float)

/** Seeded placement uses measured glyph sizes and accounts for rotation and subsequent drift. */
internal fun posterScatteredWords(
    sizes: List<PosterWordSize>,
    width: Float,
    height: Float,
    seed: Int,
    drift: Float
): List<PosterScatteredWord> {
    if (width <= 0f || height <= 0f) return emptyList()
    val random = Random(seed)
    data class Occupied(val x: Float, val y: Float, val halfWidth: Float, val halfHeight: Float)
    val occupied = mutableListOf<Occupied>()
    val placed = arrayOfNulls<PosterScatteredWord>(sizes.size)

    sizes.indices.sortedByDescending { sizes[it].width * sizes[it].height }.forEach { index ->
        val word = sizes[index]
        val rotation = (random.nextFloat() - 0.5f) * 28f
        val radians = rotation * PI.toFloat() / 180f
        val halfWidth = (abs(cos(radians)) * word.width + abs(sin(radians)) * word.height) / 2f + drift
        val halfHeight = (abs(sin(radians)) * word.width + abs(cos(radians)) * word.height) / 2f + drift
        var best: Occupied? = null
        var bestScore = Float.POSITIVE_INFINITY
        repeat(240) {
            val candidate = Occupied(
                halfWidth + random.nextFloat() * (width - 2f * halfWidth).coerceAtLeast(0f),
                halfHeight + random.nextFloat() * (height - 2f * halfHeight).coerceAtLeast(0f),
                halfWidth,
                halfHeight
            )
            val score = occupied.sumOf { other ->
                (maxOf(0f, candidate.halfWidth + other.halfWidth - abs(candidate.x - other.x)) *
                    maxOf(0f, candidate.halfHeight + other.halfHeight - abs(candidate.y - other.y))).toDouble()
            }.toFloat()
            if (score < bestScore) {
                best = candidate
                bestScore = score
            }
        }
        val selected = checkNotNull(best)
        occupied += selected
        placed[index] = PosterScatteredWord(selected.x, selected.y, rotation, random.nextFloat() * 2f * PI.toFloat())
    }
    return placed.map { checkNotNull(it) }
}
