package cp.player.app.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cp.player.app.ui.theme.CpShapes

/**
 * 底部弹层外壳。
 *
 * ⚠️ 这里原本还有一个 `LegacyPageScaffold`（`onBackPressed = null` 写死 + 各调用点自己塞
 * `navigationIcon`），已于 2026-10-01 删除 —— 路由页外壳统一收敛到 [CpRouteScaffold]，
 * 返回键统一收敛到 [CpBackButton]。别再把它加回来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyModalBottomSheet(
    onDismissRequest: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = 32.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // 底部弹窗形状收敛到 `CpShapes.sheet` —— 原先这里、`MoreOptionsSheet`、
        // `DownloadsScreen` 各写了一遍同样的「上两角 32dp」，改一次要改三处。
        shape = CpShapes.sheet,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outlineVariant,
                width = 48.dp,
                height = 4.dp,
            )
        },
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = bottomPadding),
            content = content,
        )
    }
}
