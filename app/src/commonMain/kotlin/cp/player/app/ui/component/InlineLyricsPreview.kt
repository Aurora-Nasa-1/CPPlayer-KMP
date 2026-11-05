package cp.player.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.model.MobilePlayerCustomConfig
import cp.player.app.ui.util.LyricOffsetManager
import cp.player.core.lyrics.RubyText
import cp.player.core.playback.LyricsState

/** A quiet, unframed current line. The scrolling lyric reader belongs to lyrics focus. */
@Composable
fun InlineLyricsPreview(
    lyricsState: LyricsState,
    positionMs: Long,
    isPlaying: Boolean,
    config: MobilePlayerCustomConfig,
    trackId: String?,
    compact: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val colors = MaterialTheme.colorScheme
    val offset = trackId?.let(LyricOffsetManager::getOffset) ?: 0L
    val position = (positionMs + offset).coerceAtLeast(0L)
    val lines = (lyricsState as? LyricsState.Success)?.lines.orEmpty()
    val index = remember(lines, position) { lines.indexOfLast { it.time <= position } }
    val line = lines.getOrNull(index)
    val label = when (lyricsState) {
        LyricsState.Loading -> s.player.lyricsLoading
        LyricsState.NoLyrics -> s.player.lyricsEmpty
        is LyricsState.Error -> s.player.lyricsError(lyricsState.message)
        else -> s.player.lyricsIdle
    }
    val text = line?.let { RubyText.strip(it.text) }?.takeIf(String::isNotBlank) ?: label
    val annotated = buildAnnotatedString {
        append(text)
        if (config.karaokeEnabled && line != null && line.words.isNotEmpty()) {
            addStyle(SpanStyle(color = colors.onSurface.copy(alpha = 0.38f)), 0, length)
            var cursor = 0
            line.words.forEach { word ->
                val wordText = RubyText.strip(word.text)
                val start = text.indexOf(wordText, cursor)
                if (wordText.isNotEmpty() && start >= 0) {
                    val end = start + wordText.length
                    if (position >= word.beginTime) {
                        addStyle(SpanStyle(color = colors.onSurface), start, end)
                    }
                    cursor = end
                }
            }
        }
    }
    val translation = line?.translation?.takeIf { config.showTranslation && it.isNotBlank() }
    val nextLine = lines.getOrNull(index + 1)?.let { RubyText.strip(it.text) }?.takeIf(String::isNotBlank)
    Column(
        modifier.fillMaxWidth().heightIn(min = if (compact) CpSpacing.touchTarget else 64.dp)
            .clickable(role = Role.Button, onClickLabel = "展开完整歌词", onClick = onClick)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        CpText(
            annotated,
            modifier = Modifier.fillMaxWidth().testTag("player-current-lyric"),
            style = MaterialTheme.typography.headlineMedium.lyricLineStyle(
                config.lyricsFontSizeSp.coerceIn(14, 22).sp,
            ),
            color = if (line != null) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1, reveal = CpTextReveal.Marquee,
        )
        if (!compact) {
            CpText(
                translation ?: nextLine.orEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("player-secondary-lyric"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1, reveal = CpTextReveal.Marquee,
            )
        }
    }
}
