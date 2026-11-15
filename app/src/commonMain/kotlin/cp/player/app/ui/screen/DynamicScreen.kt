package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.model.DynamicOrigin
import cp.player.app.model.DynamicPost
import cp.player.app.presentation.navigation.AppNavigator
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpCoverPlaceholder
import cp.player.app.ui.component.CpExpandableText
import cp.player.app.ui.component.CpLinearProgress
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpShimmerBox
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LegacyModalBottomSheet
import cp.player.app.ui.component.PageHeader
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.presentation.dynamic.DynamicFilter
import cp.player.app.ui.model.DynamicScreenModel
import cp.player.app.ui.model.DynamicUiState
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.CpShapes
import cp.player.app.ui.theme.cpCardSurface
import cp.player.app.ui.theme.cpNestedSurface
import cp.player.app.ui.util.formatChatTime
import cp.player.app.ui.util.resized
import cp.player.core.music.AlbumSummary
import cp.player.core.music.CPMediaId
import cp.player.core.music.COMMENT_MAX_LENGTH
import cp.player.core.music.Comment
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.commentCodePointLength
import cp.player.core.provider.ProviderCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「动态」页。
 *
 * 三种进入方式共用同一个页面实现：
 * - 主 tab（设置里允许它替代搜索）；
 * - 顶栏 / 侧栏动作位（未占 tab 时的路由页）；
 * - 用户主页 / 转发卡片里的「只看 TA」（[userId] 非空）。
 *
 * ⚠️ 模型必须按实例分槽（`modelTag`）：Voyager 的 ScreenModel 默认按类名键控，
 * 常驻的动态 tab 与路由页会撞进同一份模型，后者的初始过滤器会被静默丢弃。
 */
class DynamicScreen(
    private val userId: String? = null,
    private val userName: String? = null,
) : Screen {

    private val modelTag = "dynamic-${hashCode()}"

    @Composable
    override fun Content() {
        val model = rememberScreenModel(tag = modelTag) { DynamicScreenModel() }
        val state by model.state.collectAsState()
        val navigator = LocalAppNavigator.current
        val isRoutePage = navigator.currentKey == key
        val provider by AppModel.activeProviderFlow.collectAsState()
        val providerId = provider?.id ?: AppModel.activeProviderId()
        val activeProvider = provider
        val supportsFeed = activeProvider != null &&
            activeProvider.isFeatureAvailable(ProviderCapability.SOCIAL_FEED)
        val profile by AppModel.userProfileFlow.collectAsState()
        val selfId = profile?.uid?.toString().orEmpty()
        val scope = rememberCoroutineScope()

        LaunchedEffect(userId) {
            if (!userId.isNullOrBlank()) {
                model.setFilter(DynamicFilter.User(userId, userName ?: "TA 的动态"))
            } else {
                model.ensureLoaded()
            }
        }

        val confirm = rememberConfirmState()
        var previewUrl by remember { mutableStateOf<String?>(null) }
        var forwardTarget by remember { mutableStateOf<DynamicPost?>(null) }

        val body: @Composable (Modifier) -> Unit = { modifier ->
            DynamicContent(
                state = state,
                supportsFeed = supportsFeed,
                selfId = selfId,
                modifier = modifier,
                onRefresh = model::refresh,
                onLoadMore = model::loadMore,
                onSelectFollowing = { model.setFilter(DynamicFilter.Following) },
                onSelectOwn = model::showOwnPosts,
                onLike = model::toggleLike,
                onComment = model::openComments,
                onForward = { forwardTarget = it },
                onDelete = { post ->
                    confirm.request(
                        title = "删除动态",
                        message = "确定删除这条动态吗？删除后无法恢复。",
                        confirmLabel = "删除",
                        destructive = true,
                        onConfirm = { model.delete(post.id) },
                    )
                },
                onOpenResource = { post -> openResource(post, providerId, navigator, scope) },
                onOpenOrigin = { origin -> openOriginResource(origin, providerId, navigator, scope) },
                onOpenUser = { id, name ->
                    if (id.isNotBlank() && id != selfId) {
                        val uid = id.toLongOrNull()
                        if (uid != null && uid > 0L) navigator.push(AppRoute.UserProfile(uid, name, id))
                    }
                },
                onPreviewImage = { previewUrl = it },
            )
        }

        if (isRoutePage) {
            CpRouteScaffold(
                title = userName?.takeIf { it.isNotBlank() } ?: "动态",
                onBack = if (navigator.depth > 1) ({ navigator.pop() }) else null,
            ) { routeModifier -> body(routeModifier) }
        } else {
            body(Modifier)
        }

        state.commentTarget?.let { target ->
            DynamicCommentSheet(
                state = state,
                target = target,
                onDismiss = model::closeComments,
                onLoad = model::loadComments,
                onDraftChange = model::setCommentDraft,
                onSend = model::postComment,
            )
        }

        forwardTarget?.let { target ->
            ForwardDialog(
                target = target,
                pending = target.id in state.pendingIds,
                onDismiss = { forwardTarget = null },
                onConfirm = { text ->
                    model.forward(target.id, text)
                    forwardTarget = null
                },
            )
        }

        previewUrl?.let { url ->
            ImagePreviewDialog(url = url, onDismiss = { previewUrl = null })
        }

        CpConfirmHost(confirm)
    }
}

// ============================================================ 正文

@Composable
internal fun DynamicContent(
    state: DynamicUiState,
    supportsFeed: Boolean,
    selfId: String,
    modifier: Modifier,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectFollowing: () -> Unit,
    onSelectOwn: () -> Unit,
    onLike: (String) -> Unit,
    onComment: (DynamicPost) -> Unit,
    onForward: (DynamicPost) -> Unit,
    onDelete: (DynamicPost) -> Unit,
    onOpenResource: (DynamicPost) -> Unit,
    onOpenOrigin: (DynamicOrigin) -> Unit,
    onOpenUser: (String, String) -> Unit,
    onPreviewImage: (String) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        AnimatedVisibility(visible = state.refreshing, enter = fadeIn(), exit = fadeOut()) {
            CpLinearProgress(progress = 0.38f, animated = false, modifier = Modifier.fillMaxWidth())
        }
        LazyScrollColumn(
            modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                top = CpSpacing.pageTop,
                bottom = CpSpacing.formBottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
        ) {
            item {
                PageHeader(
                    title = "动态",
                    subtitle = "关注的人与自己的最新分享",
                    modifier = Modifier.padding(bottom = 8.dp),
                    action = {
                        FilledTonalButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            CpText("刷新")
                        }
                    },
                )
            }
            item {
                val userFilter = state.filter as? DynamicFilter.User
                val viewingOtherUser = userFilter != null && userFilter.userId != selfId
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CpToggleChip(
                        checked = state.filter is DynamicFilter.Following,
                        onCheckedChange = { if (it) onSelectFollowing() },
                        label = "关注",
                        icon = Icons.Filled.DynamicFeed,
                    )
                    CpToggleChip(
                        checked = userFilter != null,
                        onCheckedChange = { if (it) onSelectOwn() },
                        label = if (viewingOtherUser) "TA 的动态" else "我的动态",
                        icon = Icons.Filled.Person,
                        enabled = selfId.isNotBlank(),
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.posts.isNotEmpty()) {
                        CpText(
                            "${state.posts.size} 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when {
                !supportsFeed -> item {
                    StateSurface(Modifier.padding(top = 12.dp)) {
                        ContentState(
                            title = "当前音源不支持动态",
                            message = "动态需要音源声明 social.events 能力；切换音源后这里会自动刷新。",
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                state.unsupported -> item {
                    StateSurface(Modifier.padding(top = 12.dp)) {
                        ContentState(
                            title = "当前音源没有开放动态",
                            message = "音源没有实现社交动态接口。重试不会改变结果，请切换音源或稍后再来。",
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                state.showSkeleton -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(3) { DynamicSkeletonCard() }
                    }
                }
                state.error != null && state.posts.isEmpty() -> item {
                    StateSurface {
                        ContentState(
                            title = "没有加载到动态",
                            message = state.error,
                            error = true,
                            actionLabel = "重试",
                            onAction = onRefresh,
                        )
                    }
                }
                state.showEmpty -> item {
                    StateSurface(Modifier.padding(top = 12.dp)) {
                        ContentState(
                            title = if (state.filter is DynamicFilter.User) "还没有发过动态" else "关注流还是空的",
                            message = if (state.filter is DynamicFilter.User) {
                                "发一条动态，或者去关注页看看别人在听什么。"
                            } else {
                                "关注一些朋友后，他们的分享会出现在这里。"
                            },
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                else -> {
                    items(state.posts, key = { it.id }) { post ->
                        DynamicPostCard(
                            post = post,
                            selfId = selfId,
                            pending = post.id in state.pendingIds,
                            onLike = { onLike(post.id) },
                            onComment = { onComment(post) },
                            onForward = { onForward(post) },
                            onDelete = { onDelete(post) },
                            onOpenResource = { onOpenResource(post) },
                            onOpenOrigin = onOpenOrigin,
                            onOpenUser = onOpenUser,
                            onPreviewImage = onPreviewImage,
                            modifier = Modifier.animateItem(),
                        )
                    }
                    item {
                        DynamicFeedFooter(
                            state = state,
                            onLoadMore = onLoadMore,
                            onRetry = onRefresh,
                        )
                    }
                }
            }
        }
    }
}

// ============================================================ 动态卡片

@Composable
private fun DynamicPostCard(
    post: DynamicPost,
    selfId: String,
    pending: Boolean,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    onOpenResource: () -> Unit,
    onOpenOrigin: (DynamicOrigin) -> Unit,
    onOpenUser: (String, String) -> Unit,
    onPreviewImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOwn = selfId.isNotBlank() && post.userId == selfId
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = cpCardSurface(),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                DynamicAvatar(
                    url = post.avatarUrl,
                    contentDescription = post.userName,
                    size = 44.dp,
                    onClick = { onOpenUser(post.userId, post.userName) },
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    CpText(
                        post.userName.ifBlank { "未知用户" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onOpenUser(post.userId, post.userName) },
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CpText(
                            formatChatTime(post.publishedAtEpochMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!post.location.isNullOrBlank()) {
                            Icon(
                                Icons.Filled.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            CpText(
                                post.location,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (isOwn) {
                    IconButton(onClick = onDelete, enabled = !pending) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除动态",
                            modifier = Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (post.text.isNotBlank()) {
                CpExpandableText(
                    text = post.text,
                    collapsedMaxLines = 6,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                    expandLabel = "展开全文",
                    collapseLabel = "收起",
                )
            }

            if (post.imageUrls.isNotEmpty()) {
                DynamicImageGrid(
                    urls = post.imageUrls,
                    onPreview = onPreviewImage,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (post.hasResource) {
                DynamicResourceCard(post, onClick = onOpenResource, modifier = Modifier.padding(top = 12.dp))
            }

            post.forwardedFrom?.let { origin ->
                DynamicForwardCard(
                    origin = origin,
                    onClick = { onOpenOrigin(origin) },
                    onOpenUser = onOpenUser,
                    onPreviewImage = onPreviewImage,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            DynamicActionRow(
                post = post,
                pending = pending,
                onLike = onLike,
                onComment = onComment,
                onForward = onForward,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun DynamicActionRow(
    post: DynamicPost,
    pending: Boolean,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedAction(
            icon = if (post.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            label = post.likedCount.compactCount(),
            contentDescription = if (post.liked) "取消点赞" else "点赞",
            active = post.liked,
            enabled = !pending,
            onClick = onLike,
        )
        FeedAction(
            icon = Icons.AutoMirrored.Filled.Comment,
            label = post.commentCount.compactCount(),
            contentDescription = "评论",
            onClick = onComment,
        )
        FeedAction(
            icon = Icons.Filled.Share,
            label = post.forwardCount.compactCount(),
            contentDescription = "转发",
            enabled = !pending,
            onClick = onForward,
        )
        Spacer(Modifier.weight(1f))
        if (pending) {
            CpLoadingIndicator(Modifier.size(18.dp))
        }
    }
}

@Composable
private fun FeedAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CpShapes.full,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
            if (label.isNotBlank() && label != "0") {
                CpText(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

private fun Int.compactCount(): String = when {
    this <= 0 -> ""
    this < 10_000 -> toString()
    else -> "${(this / 1000)}k"
}

@Composable
private fun DynamicImageGrid(
    urls: List<String>,
    onPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = urls.take(6)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { url ->
                    AsyncImage(
                        model = url.resized(640),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .height(if (row.size == 1) 190.dp else 108.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(cpNestedSurface())
                            .clickable { onPreview(url) },
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DynamicResourceCard(
    post: DynamicPost,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(56.dp).clip(MaterialTheme.shapes.small)
                    .background(cpNestedSurface()),
                contentAlignment = Alignment.Center,
            ) {
                CpCoverPlaceholder(Modifier.fillMaxSize(), corner = 10.dp)
                if (!post.resourceCoverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = post.resourceCoverUrl.resized(160),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                CpText(
                    post.resourceTitle?.takeIf { it.isNotBlank() } ?: "关联内容",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                val subtitle = listOfNotNull(
                    post.resourceSubtitle?.takeIf { it.isNotBlank() },
                    resourceTypeLabel(post.resourceType),
                ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    CpText(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Icon(
                imageVector = if (post.resourceType == "song") Icons.Filled.PlayArrow else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun DynamicForwardCard(
    origin: DynamicOrigin,
    onClick: () -> Unit,
    onOpenUser: (String, String) -> Unit,
    onPreviewImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DynamicAvatar(
                    url = origin.avatarUrl,
                    contentDescription = origin.userName,
                    size = 26.dp,
                    onClick = { onOpenUser(origin.userId, origin.userName) },
                )
                Spacer(Modifier.width(8.dp))
                CpText(
                    origin.userName.ifBlank { "未知用户" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenUser(origin.userId, origin.userName) },
                )
                Spacer(Modifier.weight(1f))
                CpText(
                    formatChatTime(origin.publishedAtEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (origin.text.isNotBlank()) {
                CpExpandableText(
                    text = origin.text,
                    collapsedMaxLines = 4,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                    expandLabel = "展开",
                    collapseLabel = "收起",
                )
            }
            if (origin.imageUrls.isNotEmpty()) {
                DynamicImageGrid(
                    urls = origin.imageUrls,
                    onPreview = onPreviewImage,
                    modifier = Modifier.padding(top = 8.dp).clickable(onClick = onClick),
                )
            }
            if (!origin.resourceId.isNullOrBlank() && !origin.resourceType.isNullOrBlank()) {
                val snapshot = DynamicPost(
                    id = origin.id,
                    userId = origin.userId,
                    userName = origin.userName,
                    avatarUrl = origin.avatarUrl,
                    text = origin.text,
                    publishedAtEpochMs = origin.publishedAtEpochMs,
                    resourceId = origin.resourceId,
                    resourceType = origin.resourceType,
                    resourceTitle = origin.resourceTitle,
                    resourceCoverUrl = origin.resourceCoverUrl,
                    resourceSubtitle = origin.resourceSubtitle,
                )
                DynamicResourceCard(snapshot, onClick = onClick, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun DynamicAvatar(
    url: String,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = url.resized(96),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.Person,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxSize(0.55f),
            )
        }
    }
}

// ============================================================ 骨架 / 页脚

@Composable
private fun DynamicSkeletonCard() {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = cpCardSurface(),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CpShimmerBox(Modifier.size(44.dp), CircleShape, base, highlight)
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CpShimmerBox(Modifier.fillMaxWidth(0.36f).height(13.dp), MaterialTheme.shapes.extraSmall, base, highlight)
                    CpShimmerBox(Modifier.fillMaxWidth(0.22f).height(10.dp), MaterialTheme.shapes.extraSmall, base, highlight)
                }
            }
            Spacer(Modifier.height(14.dp))
            CpShimmerBox(Modifier.fillMaxWidth().height(13.dp), MaterialTheme.shapes.extraSmall, base, highlight)
            Spacer(Modifier.height(8.dp))
            CpShimmerBox(Modifier.fillMaxWidth(0.72f).height(13.dp), MaterialTheme.shapes.extraSmall, base, highlight)
            Spacer(Modifier.height(14.dp))
            CpShimmerBox(Modifier.fillMaxWidth().height(148.dp), MaterialTheme.shapes.large, base, highlight)
            Spacer(Modifier.height(14.dp))
            CpShimmerBox(Modifier.fillMaxWidth(0.5f).height(26.dp), CpShapes.full, base, highlight)
        }
    }
}

@Composable
private fun DynamicFeedFooter(
    state: DynamicUiState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            state.loadingMore -> CpLoadingIndicator(Modifier.size(26.dp))
            state.error != null -> {
                CpText(
                    state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onRetry) { CpText("重试") }
            }
            state.hasMore -> TextButton(onClick = onLoadMore) { CpText("加载更多") }
            state.posts.isNotEmpty() -> CpText(
                "已经到底了",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================================================ 评论

@Composable
private fun DynamicCommentSheet(
    state: DynamicUiState,
    target: DynamicPost,
    onDismiss: () -> Unit,
    onLoad: (Boolean) -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    LegacyModalBottomSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    CpText("动态评论", style = MaterialTheme.typography.titleLarge)
                    CpText(
                        "${target.userName.ifBlank { "未知用户" }} · ${target.commentCount} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            when {
                state.commentsLoading && state.comments.isEmpty() -> Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) { CpLoadingIndicator(Modifier.size(32.dp)) }

                state.commentsError != null && state.comments.isEmpty() -> ContentState(
                    title = "评论加载失败",
                    message = state.commentsError,
                    error = true,
                    actionLabel = "重试",
                    onAction = { onLoad(true) },
                )

                state.comments.isEmpty() -> ContentState(
                    title = "还没有评论",
                    message = "说点什么吧",
                    modifier = Modifier.padding(vertical = 16.dp),
                )

                else -> LazyScrollColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.comments, key = { it.id }) { comment ->
                        DynamicCommentRow(comment)
                    }
                    if (state.commentsHasMore) {
                        item {
                            TextButton(
                                onClick = { onLoad(false) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { CpText(if (state.commentsLoading) "加载中…" else "查看更多评论") }
                        }
                    }
                }
            }

            state.commentsError?.takeIf { state.comments.isNotEmpty() }?.let { error ->
                CpText(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            val length = commentCodePointLength(state.commentDraft)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.commentDraft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { CpText("写评论…", style = MaterialTheme.typography.bodyMedium) },
                    maxLines = 3,
                    shape = MaterialTheme.shapes.extraLarge,
                    supportingText = if (length > COMMENT_MAX_LENGTH - 30) {
                        { CpText("$length/$COMMENT_MAX_LENGTH") }
                    } else {
                        null
                    },
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = state.commentDraft.isNotBlank() &&
                        !state.commentSending &&
                        length <= COMMENT_MAX_LENGTH,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    if (state.commentSending) {
                        CpLoadingIndicator(Modifier.size(18.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送评论")
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicCommentRow(comment: Comment) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        DynamicAvatar(
            url = comment.avatar,
            contentDescription = comment.user,
            size = 32.dp,
            onClick = {},
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CpText(
                    comment.user.ifBlank { "匿名用户" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (comment.isHot) {
                    Spacer(Modifier.width(6.dp))
                    CpText(
                        "热门",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            CpText(
                comment.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = Int.MAX_VALUE,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                Modifier.padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CpText(
                    comment.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (comment.ipLocation.isNotBlank()) {
                    CpText(
                        comment.ipLocation,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (comment.likedCount > 0) {
                    CpText(
                        "♥ ${comment.likedCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (comment.liked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ============================================================ 弹层

@Composable
private fun ForwardDialog(
    target: DynamicPost,
    pending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CpText("转发动态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        CpText(
                            target.userName.ifBlank { "未知用户" },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        CpText(
                            target.text.ifBlank { "这条动态没有正文" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 200) text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { CpText("说点什么…") },
                    maxLines = 4,
                    supportingText = { CpText("${text.length}/200") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = !pending,
            ) { CpText("转发") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { CpText("取消") }
        },
    )
}

@Composable
private fun ImagePreviewDialog(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.86f)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

// ============================================================ 工具

private fun resourceTypeLabel(type: String?): String? = when (type) {
    "song" -> "单曲"
    "playlist" -> "歌单"
    "album" -> "专辑"
    "mv" -> "MV"
    "video" -> "视频"
    "dj" -> "电台"
    else -> null
}

private fun openResource(
    post: DynamicPost,
    providerId: String,
    navigator: AppNavigator,
    scope: CoroutineScope,
) = openTypedResource(
    resourceId = post.resourceId,
    resourceType = post.resourceType,
    title = post.resourceTitle,
    coverUrl = post.resourceCoverUrl,
    subtitle = post.resourceSubtitle,
    providerId = providerId,
    navigator = navigator,
    scope = scope,
)

private fun openOriginResource(
    origin: DynamicOrigin,
    providerId: String,
    navigator: AppNavigator,
    scope: CoroutineScope,
) = openTypedResource(
    resourceId = origin.resourceId,
    resourceType = origin.resourceType,
    title = origin.resourceTitle,
    coverUrl = origin.resourceCoverUrl,
    subtitle = origin.resourceSubtitle,
    providerId = providerId,
    navigator = navigator,
    scope = scope,
)

private fun openTypedResource(
    resourceId: String?,
    resourceType: String?,
    title: String?,
    coverUrl: String?,
    subtitle: String?,
    providerId: String,
    navigator: AppNavigator,
    scope: CoroutineScope,
) {
    val id = resourceId?.takeIf { it.isNotBlank() } ?: return
    when (resourceType) {
        "song" -> {
            val mediaId = CPMediaId.toCanonicalMediaId(id, providerId, "song")
            scope.launch {
                AppModel.playback.playQueue(listOf(mediaId), startIndex = 0)
            }
        }
        "playlist" -> navigator.push(
            AppRoute.PlaylistDetail(
                PlaylistSummary(
                    id = id.toLongOrNull() ?: 0L,
                    name = title?.takeIf { it.isNotBlank() } ?: "歌单",
                    coverUrl = coverUrl,
                    trackCount = 0,
                    creatorName = subtitle,
                    resourceId = id,
                ),
            )
        )
        "album" -> navigator.push(
            AppRoute.AlbumDetail(
                albumId = id.toLongOrNull() ?: 0L,
                fallback = AlbumSummary(
                    id = id.toLongOrNull() ?: 0L,
                    name = title?.takeIf { it.isNotBlank() } ?: "专辑",
                    coverUrl = coverUrl,
                    artistName = subtitle,
                    trackCount = 0,
                    resourceId = id,
                ),
            )
        )
        "mv", "video" -> navigator.push(
            AppRoute.VideoPlayer(
                mediaId = CPMediaId.toCanonicalMediaId(id, providerId, resourceType),
                title = title,
            )
        )
        else -> cp.player.app.ui.util.UiEvents.notify("暂不支持打开${resourceTypeLabel(resourceType) ?: "该内容"}")
    }
}
