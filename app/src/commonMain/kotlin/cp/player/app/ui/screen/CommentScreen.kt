package cp.player.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.core.music.Comment
import cp.player.app.ui.model.CommentScreenModel
import cp.player.app.ui.util.popOrNotify
import coil3.compose.AsyncImage

class CommentScreen(val id: String, val type: String = "music") : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val model = rememberScreenModel { CommentScreenModel(id, type) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.current
        // 桌面自绘标题栏的标题（页内顶栏在桌面端整体让位，见 CpRouteScaffold 的 KDoc）。
        cp.player.app.ui.util.DesktopRouteTitle("评论")

        // ⚠️ 必须走 `CpRouteScaffold`，不要退回 `AppScaffold`。
        //
        // 本页原先直接用 `AppScaffold`，是收敛返回键外观时**唯一漏掉**的一页：
        // `AppScaffold` 的返回键判据里带了 `!LocalWindowChromeActive`，桌面端于是既不画
        // 页内返回键、也不发布路由标题 —— 而它的 `onBackPressed` 又只有窄屏走得到。
        // 结果是宽屏窗口下从播放页三页页签点进评论后，**整页没有任何返回入口**。
        // `CpRouteScaffold` 把「桌面发布标题 / 窄屏自绘顶栏 / 双栏右栏只出正文」三选一
        // 收在一处，本页只需要声明标题与返回动作。
        CpRouteScaffold(
            title = "评论",
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            Box(pageModifier) {
                when {
                    state.loading && state.comments.isEmpty() -> {
                        cp.player.app.ui.component.CpLoadingIndicator(
                            Modifier.align(Alignment.Center).size(40.dp)
                        )
                    }
                    state.error != null -> {
                        ContentState(
                            title = "加载失败",
                            message = state.error,
                            error = true,
                            actionLabel = "重试",
                            onAction = model::loadComments
                        )
                    }
                    else -> {
                        LazyScrollColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(state.comments) { comment ->
                                CommentItem(comment, onLike = { model.toggleLike(comment) })
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CommentItem(comment: Comment, onLike: () -> Unit) {
        Row(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = comment.avatar,
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        comment.user,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onLike, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (comment.liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                            contentDescription = if (comment.liked) "取消点赞" else "点赞",
                            tint = if (comment.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        comment.likedCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(comment.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(comment.content, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(Modifier.padding(top = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
