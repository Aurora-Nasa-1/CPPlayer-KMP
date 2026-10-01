package cp.player.app.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * **全应用唯一的返回按钮。**
 *
 * 收敛前仓里有三套并存、且互不相干的实现，同一屏上就能看出差别：
 * - `AppScaffold` 自带的那个 —— `FilledIconButton` + `surfaceContainerHighest`；
 * - 10 个路由页各自手写的 `IconButton(onClick = { navigator.pop() }) { Icon(ArrowBack) }`
 *   —— 无底色、24dp 图标，与上一种并排时一个像"悬浮块"、一个像"裸图标"；
 * - `PlaylistDetailScreen.WideLayout` 又抄了一遍第一种。
 *
 * 统一取**第一种**（M3 的 filled tonal 图标按钮）：它在顶栏里自带对比度，
 * 不会因为页面容器色是 `background` 还是 `surfaceContainer` 而看起来忽隐忽现
 * —— 裸 `IconButton` 就踩过这个（浅色主题下顶栏也是浅色，箭头几乎融进背景）。
 *
 * ⚠️ 桌面**窗口标题栏**里那个返回键**不是**这个组件：它属于窗口控制条
 * （方形 hover、无涟漪、18dp 图标，见 `DesktopTitleBar.ChromeSlot`），
 * 换成圆形填充按钮会和旁边的 最小化 / 最大化 / 关闭 明显不是一组。两者的**语义**统一
 * （都由 [cp.player.app.ui.util.DesktopShell] / Navigator 决定"能不能返回"），
 * **外观**按各自所在的 chrome 决定。
 */
@Composable
fun CpBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "返回",
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = contentDescription,
            modifier = Modifier.size(CpIconSize.action),
        )
    }
}

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
