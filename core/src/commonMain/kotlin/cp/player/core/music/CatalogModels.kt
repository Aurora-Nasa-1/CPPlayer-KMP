package cp.player.core.music

/** Item offsets and nullable upstream pagination metadata, shared by catalog reads. */
data class CatalogPage<T>(val items: List<T>, val total: Long? = null, val hasMore: Boolean? = null)

data class VideoSummary(
    val id: String,
    val title: String,
    val durationMs: Long,
    val coverUrl: String? = null,
    val creators: List<ArtistSummary> = emptyList(),
    val resourceType: String = "video",
)

data class SocialUserSummary(val id: String, val nickname: String, val avatarUrl: String? = null)

data class DjRadioSummary(
    val id: String,
    val title: String,
    val coverUrl: String? = null,
    val creator: SocialUserSummary? = null,
    val programCount: Int? = null,
)

data class CurrentAccountSession(
    val loggedIn: Boolean,
    val userId: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
)

/** Original lyric formats; the playback parser owns format selection and conversion. */
data class TrackLyrics(
    val lrc: String? = null,
    val ttml: String? = null,
    val yrc: String? = null,
    val translation: String? = null,
    val romanization: String? = null,
)
