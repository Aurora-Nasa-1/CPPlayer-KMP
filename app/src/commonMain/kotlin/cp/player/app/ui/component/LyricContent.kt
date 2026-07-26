package cp.player.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import cp.player.kmp.playback.LyricsState
import cp.player.kmp.playback.PlaybackUiState

/**
 * 歌词显示组件（KMP 版使用官方 accompanist-lyrics-ui 移植）。
 */
@Composable
fun LyricContent(
    state: PlaybackUiState,
    showTranslation: Boolean = true,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 60.dp, horizontal = 8.dp),
) {
    val lyricState = state.lyrics
    val lines = (lyricState as? LyricsState.Success)?.lines.orEmpty()

    if (lines.isEmpty()) {
        val label = when (lyricState) {
            LyricsState.Loading -> "歌词加载中…"
            LyricsState.NoLyrics -> "暂无歌词"
            is LyricsState.Error -> "歌词获取失败：${lyricState.message}"
            else -> "等待曲目开始播放后展示歌词"
        }
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val syncedLyrics = remember(lines) {
        val iLines: List<ISyncedLine> = lines.map { l ->
            val st = l.time.toInt()
            val en = (l.endTime ?: (l.time + 5000)).toInt()
            if (l.words.isNotEmpty()) {
                KaraokeLine.MainKaraokeLine(
                    syllables = l.words.map { w ->
                        com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable(
                            content = w.text,
                            start = w.beginTime.toInt(),
                            end = w.endTime.toInt(),
                            phonetic = ""
                        )
                    },
                    translation = l.translation ?: "",
                    alignment = com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment.Unspecified,
                    start = st,
                    end = en,
                    phonetic = l.romanization ?: "",
                    accompanimentLines = emptyList()
                )
            } else {
                SyncedLine(
                    content = l.text,
                    translation = l.translation ?: "",
                    start = st,
                    end = en
                )
            }
        }
        SyncedLyrics(lines = iLines)
    }

    val listState = rememberLazyListState()

    // 使用 rememberUpdatedState 确保 lambda 始终读取最新的 currentPosition
    val currentPosition = state.positionMs
    val latestPosition by rememberUpdatedState(currentPosition)
    val currentPositionProvider = remember {
        { latestPosition.toInt() }
    }

    val currentTextStyle = MaterialTheme.typography.headlineMedium
    val normalStyle = remember(currentTextStyle) {
        currentTextStyle.copy(
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 36.sp,
            textMotion = TextMotion.Animated,
        )
    }

    val accompanimentStyle = remember(currentTextStyle) {
        currentTextStyle.copy(
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textMotion = TextMotion.Animated,
        )
    }

    val phoneticTextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    KaraokeLyricsView(
        listState = listState,
        lyrics = syncedLyrics,
        currentPosition = currentPositionProvider,
        onLineClicked = { line -> onSeek(line.start.toLong()) },
        onLinePressed = { },
        normalLineTextStyle = normalStyle,
        accompanimentLineTextStyle = accompanimentStyle,
        phoneticTextStyle = phoneticTextStyle,
        textColor = MaterialTheme.colorScheme.onSurface,
        showTranslation = showTranslation,
        showPhonetic = true,
        useBlurEffect = false,
        modifier = modifier.fillMaxSize()
    )
}