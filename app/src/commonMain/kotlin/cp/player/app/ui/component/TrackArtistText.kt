package cp.player.app.ui.component

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import cp.player.core.music.ArtistSummary
import cp.player.core.music.TrackSummary

/** 歌手串里各歌手的分隔符 —— 与 [TrackSummary.artist] 的拼法保持一致（" / "）。 */
private const val ARTIST_SEPARATOR = " / "

/** [AnnotatedString] 里标记「这一段属于第几个歌手」的注解键。 */
private const val ARTIST_ANNOTATION = "track-artist"

/**
 * 曲目歌手行：**多个歌手各自可点**，点谁进谁的主页。
 *
 * ## 为什么不能拿 [TrackSummary.artist] 整串去点
 *
 * `artist` 是上游拼好的一整串（"周杰伦 / 杨瑞代"）。整串只有一个点击区 ⇒ 点第二个人的
 * 名字也进第一个人的主页 —— 合唱曲目里这是**错的跳转**，而且用户点完才发现。
 * 所以这里按 [TrackSummary.artists] 逐个分段注册点击区，每个人拿自己的 id。
 *
 * ## 三种退化，都退回纯文本而不是「画得像能点」
 *
 * 1. [onArtistClick] 为 null（拿不到 Navigator）；
 * 2. [TrackSummary.artists] 为空（上游只给了 `artist` 字符串，没有 id）；
 * 3. 某一位歌手 `id == 0L`（上游数组里缺 id）⇒ 那一位**单独**不可点，其余照常；
 *    若**全部**缺 id，则整行都退回纯文本（点击区盖住整行却无处可跳，最糟）。
 *
 * 「点了没反应」比「看着就不像能点」糟糕得多（参见 `pushOrNotify` 的 KDoc），
 * 所以没有 id 的时候宁可不挂点击。
 */
@Composable
fun TrackArtistText(
    track: TrackSummary,
    onArtistClick: ((ArtistSummary) -> Unit)?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1,
) {
    val textStyle = style.copy(color = color)
    val artists = track.artists
    // 退化 1 / 2 / 3：整串纯文本，连点击区都不挂。
    // 全都缺 id 时也一样挂不得 —— ClickableText 的点击区盖住整行，「点了没反应」。
    if (onArtistClick == null || artists.isEmpty() || artists.none { it.id != 0L }) {
        Text(
            text = track.artist,
            modifier = modifier,
            style = textStyle,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    // 曲目换了才重算（歌手串不随播放进度变）。
    val annotated = remember(artists) { artistAnnotatedString(artists) }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = textStyle,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onClick = { offset ->
            // 点在分隔符 / 行尾空白上取不到注解 —— 静默忽略，不跳。
            val index = annotated
                .getStringAnnotations(ARTIST_ANNOTATION, offset, offset)
                .firstOrNull()
                ?.item
                ?.toIntOrNull()
                ?: return@ClickableText
            val artist = artists.getOrNull(index) ?: return@ClickableText
            if (artist.id != 0L) onArtistClick(artist)
        },
    )
}

/**
 * 把歌手表拼成一个带「第几位」注解的串。
 *
 * 按下标而非名字做注解：合唱里同名不同 id 时用名字会串到第一个人身上。
 */
private fun artistAnnotatedString(artists: List<ArtistSummary>): AnnotatedString = buildAnnotatedString {
    artists.forEachIndexed { index, artist ->
        if (index > 0) append(ARTIST_SEPARATOR)
        val start = length
        append(artist.name)
        // 退化 3：没有 id 的那一位不注册注解 ⇒ 点它不跳转。
        if (artist.id != 0L) addStringAnnotation(ARTIST_ANNOTATION, index.toString(), start, length)
    }
}
