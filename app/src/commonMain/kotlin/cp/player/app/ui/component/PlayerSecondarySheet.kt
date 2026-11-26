package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import cp.player.core.playback.PlaybackUiState

internal enum class PlayerSheet { Queue, Comments }

@Composable
internal fun PlayerSecondarySheet(
    sheet: PlayerSheet?,
    state: PlaybackUiState,
    supportsComments: Boolean,
    onPlayAt: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemoveQueue: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onClose: () -> Unit,
) {
    when (sheet) {
        PlayerSheet.Queue -> QueueBottomSheet(
            queue = state.queue,
            currentIndex = state.currentIndex,
            onPlayAt = onPlayAt,
            onRemove = onRemoveQueue,
            onMove = onMove,
            onClear = onClearQueue,
            onClose = onClose,
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = state.repeatMode,
            onShuffle = onShuffle,
            onRepeat = onRepeat,
        )
        PlayerSheet.Comments -> if (supportsComments) {
            state.currentTrack?.let { ModernCommentBottomSheet(it.id, onClose) }
        }
        null -> Unit
    }
}
