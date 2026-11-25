package cp.player.core.lyricpush

import cp.player.core.playback.SyncedLyricLine

/** Fallback duration when a lyric row has no explicit end or word timing. */
internal const val FALLBACK_LINE_DURATION_MS = 3_000L

/**
 * A platform-independent representation of the whole-song document expected by SuperLyricApi 4.0.
 * Keeping this separate from the SDK models makes timing and secondary-track mapping testable on
 * the desktop target without loading Android classes.
 */
internal data class SuperLyricPayload(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val lines: List<SuperLyricPayloadLine>,
)

internal data class SuperLyricPayloadLine(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<SuperLyricPayloadWord>,
    val translation: String?,
    val phonetic: String?,
)

internal data class SuperLyricPayloadWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Build a complete SuperLyric 4.0 song document from the player's current lyric snapshot.
 * A missing title or lyric body cannot be accepted by the v4 publisher, so it is not published.
 */
internal fun buildSuperLyricPayload(
    track: LyricPushTrack,
    lines: List<SyncedLyricLine>,
    secondary: LyricSecondaryMode,
): SuperLyricPayload? {
    val title = track.title.trim().takeIf(String::isNotEmpty) ?: return null
    if (lines.isEmpty()) return null

    // The 4.0 contract requires ascending timestamps. Keep equal-time rows stable.
    val orderedLines = lines.sortedBy(SyncedLyricLine::time)
    val payloadLines = orderedLines.mapIndexedNotNull { index, line ->
        val text = line.lineTextOrNull() ?: return@mapIndexedNotNull null
        val startMs = line.time.coerceAtLeast(0L)
        val endMs = line.lineEndMs(orderedLines, index).coerceAtLeast(startMs)
        val words = line.words
            .withLineSpacing(text)
            .sortedBy(SyncedLyricLine.SyncedWord::beginTime)
            .map { word ->
                val wordStartMs = word.beginTime.coerceAtLeast(0L)
                SuperLyricPayloadWord(
                    text = word.text,
                    startMs = wordStartMs,
                    endMs = word.endTime.coerceAtLeast(wordStartMs),
                )
            }

        SuperLyricPayloadLine(
            text = text,
            startMs = startMs,
            endMs = endMs,
            words = words,
            translation = line.translation
                ?.takeIf { secondary == LyricSecondaryMode.TRANSLATION && it.isNotBlank() },
            phonetic = line.romanization
                ?.takeIf { secondary == LyricSecondaryMode.PRONUNCIATION && it.isNotBlank() },
        )
    }
    if (payloadLines.isEmpty()) return null

    val stableId = track.id.takeIf(String::isNotBlank)
        ?: listOf(track.sourceId.orEmpty(), title, track.artist, track.album).joinToString("##")

    return SuperLyricPayload(
        id = stableId,
        title = title,
        artist = track.artist,
        album = track.album,
        durationMs = track.durationMs.coerceAtLeast(0L),
        lines = payloadLines,
    )
}

/** End time in milliseconds, with a safe fallback for the final row. */
internal fun SyncedLyricLine.lineEndMs(
    lines: List<SyncedLyricLine>,
    index: Int,
): Long {
    endTime?.takeIf { it > time }?.let { return it }
    words.maxOfOrNull { it.endTime }?.takeIf { it > time }?.let { return it }
    lines.getOrNull(index + 1)?.time?.takeIf { it > time }?.let { return it }
    return time + FALLBACK_LINE_DURATION_MS
}
