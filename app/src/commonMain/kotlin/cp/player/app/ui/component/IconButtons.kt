package cp.player.app.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 紧凑型圆形图标按钮（MiniPlayer / 播放控制通用）。 */
@Composable
fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription, modifier = Modifier.size(24.dp))
    }
}

/** 大行按钮（播放控制常用 56dp）。 */
@Composable
fun LargeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = Color.Unspecified,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        enabled = enabled,
    ) {
        Icon(icon, contentDescription, modifier = Modifier.size(36.dp), tint = tint)
    }
}