package cp.player.app.ui.wall

import kotlin.random.Random

internal data class PosterSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    val sourceKind: String = "library",
    val rawId: String = id,
    val playbackSourceKey: String? = null,
) {
    fun playlistIdentityKey(): String = "$sourceKind:$rawId"
}

internal data class PosterWallItem(val song: PosterSong, val sourceIndex: Int, val key: String)

internal data class PosterWallDataset(val source: String, val query: String, val items: List<PosterWallItem>, val shuffleSeed: Int = 0)

/** Shuffle only the presentation, retaining source indices and duplicate queue occurrences. */
internal fun rearrangePosterWallItems(items: List<PosterWallItem>, seed: Int): List<PosterWallItem> {
    if (seed == 0 || items.size < 2) return items
    return items.shuffled(Random(seed))
}

internal fun nextPosterWallShuffleSeed(current: List<PosterWallItem>, seed: Int): Int {
    val original = current.sortedBy { it.sourceIndex }
    var next = seed
    do {
        next = if (next == Int.MAX_VALUE) 1 else next + 1
    } while (original.size > 1 && rearrangePosterWallItems(original, next) == current)
    return next
}

internal fun buildPosterWallItems(songs: List<PosterSong>, source: String, query: String): List<PosterWallItem> {
    val needle = query.trim()
    return songs.mapIndexedNotNull { index, song ->
        if (needle.isBlank() || song.title.contains(needle, true) || song.artist.contains(needle, true) ||
            song.album.contains(needle, true) || song.rawId.contains(needle, true)
        ) {
            PosterWallItem(song, index, "$source:$index:${song.playlistIdentityKey()}")
        } else null
    }
}

internal fun resolvePosterQueueIndex(queue: List<PosterSong>, song: PosterSong, hint: Int): Int {
    val key = song.playlistIdentityKey()
    fun matches(candidate: PosterSong?) = candidate != null &&
        candidate.playlistIdentityKey() == key &&
        (song.playbackSourceKey == null || candidate.playbackSourceKey == song.playbackSourceKey)
    if (matches(queue.getOrNull(hint))) return hint
    return queue.indexOfFirst(::matches)
}
