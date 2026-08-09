package cp.player.app.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Pure UI counterpart of the segmented list rows used by the Android app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LegacyListItem(
    index: Int,
    total: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    leadingContent: (@Composable () -> Unit)? = null,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val press = rememberPressedScale()
    // Surface 自带 onClick 不支持长按，需要长按时改用 combinedClickable
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            leadingContent?.invoke()
            Column(Modifier.weight(1f)) {
                headlineContent()
                supportingContent?.invoke()
            }
            trailingContent?.invoke(this)
        }
    }
    if (onLongClick != null) {
        Surface(
            modifier = modifier.fillMaxWidth()
                .combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick),
            shape = legacySegmentShape(index, total),
            color = containerColor,
            content = content,
        )
    } else {
        Surface(
            onClick = onClick ?: {},
            enabled = onClick != null,
            interactionSource = press.first,
            modifier = modifier.fillMaxWidth().then(if (onClick != null) press.second else Modifier),
            shape = legacySegmentShape(index, total),
            color = containerColor,
            content = content,
        )
    }
}

fun legacySegmentShape(index: Int, total: Int): Shape {
    if (total <= 1) return RoundedCornerShape(20.dp)
    val outer = 20.dp
    val inner = 4.dp
    return when (index) {
        0 -> RoundedCornerShape(outer, outer, inner, inner)
        total - 1 -> RoundedCornerShape(inner, inner, outer, outer)
        else -> RoundedCornerShape(inner)
    }
}
