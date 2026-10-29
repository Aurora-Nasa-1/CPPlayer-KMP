package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.model.CommentFloorUi
import cp.player.app.ui.model.CommentScreenModel
import cp.player.app.ui.model.CommentSort
import cp.player.app.ui.model.CommentUiState
import cp.player.app.ui.screen.UserProfileScreen
import cp.player.app.ui.util.pushOrNotify
import cp.player.core.music.COMMENT_MAX_LENGTH
import cp.player.core.music.Comment

/**
 * 评论区内容（窄屏评论页与桌面评论弹层**共用**）。
 *
 * ### 为什么抽成组件
 * 改造前窄屏 `PlayerScreen.CommentPage` 与宽屏播放页各有一份自己实现的扁平列表。
 * 这里收成一份，两种形态只在「外壳」（Scaffold / 桌面弹层）上不同。
 *
 * ### 楼层（显示与折叠）
 * 顶层评论若 `replyCount > 0`，下方给出「查看 N 条回复 / 收起回复」的切换行：
 * - **折叠态**只显示这一行，不请求网络；
 * - **首次展开**才拉第一页（懒加载），结果缓存在 `state.floors`；
 * - 楼层内还有更多时给「查看更多回复」，游标（`nextTime`）由状态机带回；
 * - 折叠**不丢数据** —— 再次展开直接用缓存，不重复请求。
 *
 * 输入条固定在底部：回复某条评论时顶部出现「回复 @昵称 ✕」，发送成功后就地插入。
 */
@Composable
fun CommentPane(
    model: CommentScreenModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val s = cpStrings()

    Column(modifier.fillMaxSize()) {
        CommentSortRow(
            selected = state.sortType,
            totalCount = state.totalCount,
            onSelect = model::setSort,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading && state.comments.isEmpty() -> {
                    CpLoadingIndicator(Modifier.align(Alignment.Center).size(40.dp))
                }
                state.error != null && state.comments.isEmpty() -> {
                    ContentState(
                        title = s.player.commentsLoadFailed,
                        message = state.error,
                        error = true,
                        actionLabel = s.player.retry,
                        onAction = model::loadComments,
                    )
                }
                state.comments.isEmpty() -> {
                    ContentState(
                        title = s.social.comment.empty,
                        message = s.social.comment.emptyHint,
                    )
                }
                else -> {
                    CommentList(state, model)
                }
            }
        }
        CommentComposer(state, model)
    }
}

@Composable
private fun CommentSortRow(selected: Int, totalCount: Long, onSelect: (Int) -> Unit) {
    val s = cpStrings().social.comment
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CommentSort.ALL.forEach { sort ->
            CpToggleChip(
                checked = selected == sort,
                onCheckedChange = { onSelect(sort) },
                label = when (sort) {
                    CommentSort.HOT -> s.sortHot
                    CommentSort.LATEST -> s.sortLatest
                    else -> s.sortRecommend
                },
            )
        }
        Spacer(Modifier.weight(1f))
        if (totalCount > 0L) {
            CpText(
                s.totalCount(totalCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CommentList(state: CommentUiState, model: CommentScreenModel) {
    val s = cpStrings().social.comment
    val listState = rememberLazyListState()
    LazyScrollColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(state.comments, key = { it.id }) { comment ->
            CommentRow(comment, state.floors[comment.id], model)
        }
        item {
            when {
                state.loadingMore -> Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                    CpLoadingIndicator(Modifier.size(24.dp))
                }
                state.hasMore -> FooterAction(s.loadMore) { model.loadMore() }
                else -> CpText(
                    s.noMore,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun FooterAction(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), Alignment.Center) {
        CpText(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** 一条顶层评论：正文 + （若有）楼层。 */
@Composable
private fun CommentRow(comment: Comment, floor: CommentFloorUi?, model: CommentScreenModel) {
    Column(Modifier.fillMaxWidth()) {
        CommentBody(comment, model)
        if (comment.replyCount > 0 || floor?.replies?.isNotEmpty() == true) {
            FloorSection(comment, floor, model)
        }
    }
}

/**
 * 楼层：折叠行 + （展开时）回复列表 + 更多。
 *
 * 折叠行本身**不触发网络** —— 只有真正展开才调 [CommentScreenModel.toggleFloor]。
 * 折叠行做成**实心 chip**（而不是一行主题色文字）：它承载的是「这里有一整个楼」这个信息，
 * 混在正文里太容易被当成普通链接忽略掉。
 */
@Composable
private fun FloorSection(comment: Comment, floor: CommentFloorUi?, model: CommentScreenModel) {
    val s = cpStrings().social.comment
    val expanded = floor?.expanded == true
    // 条数取「上游计数」与「已加载条数」的较大值：上游不给计数时，至少内联种子数能显示出来。
    val count = maxOf(comment.replyCount, floor?.replies?.size ?: 0)
    Column(Modifier.fillMaxWidth().padding(start = 50.dp, top = 8.dp)) {
        Box(
            Modifier
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable { model.toggleFloor(comment) }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            CpText(
                text = if (expanded) s.collapseReplies else s.expandReplies(count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                floor.replies.forEach { reply ->
                    CommentBody(reply, model, compact = true)
                }
                when {
                    // 已有内容时不再显示加载态 / 错误：内联种子（含平铺归组）可能已经够了，
                    // 补一次「完整第一页」失败也不该把已经能看的回复盖住。
                    floor.replies.isEmpty() && floor.loading -> Box(Modifier.fillMaxWidth(), Alignment.Center) {
                        CpLoadingIndicator(Modifier.size(20.dp))
                    }
                    floor.replies.isEmpty() && floor.error != null -> CpText(
                        s.repliesFailed,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    floor.hasMore -> CpText(
                        s.moreReplies,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable { model.loadMoreFloor(comment) }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * 评论正文（顶层评论与楼层回复共用）。
 *
 * @param compact 楼层里用的紧凑版：头像更小、字号不变（可读性优先）
 */
@Composable
private fun CommentBody(comment: Comment, model: CommentScreenModel, compact: Boolean = false) {
    val s = cpStrings().social.comment
    val navigator = LocalNavigator.current
    val onUserClick: (() -> Unit)? = remember(navigator, comment.userId) {
        if (navigator == null || comment.userId <= 0L) null
        else { { navigator.pushOrNotify(UserProfileScreen(comment.userId, comment.user)) } }
    }
    Row(Modifier.fillMaxWidth()) {
        AsyncImage(
            model = comment.avatar,
            contentDescription = null,
            modifier = Modifier
                .size(if (compact) 32.dp else 40.dp)
                .clip(CircleShape)
                .let { if (onUserClick != null) it.clickable(onClick = onUserClick) else it },
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ⚠️ 昵称必须**独占**权重：之前昵称与「占位 Spacer」各占 1f、而昵称又用
                // `fill = false` 只取自身宽度，剩余空间就没被全部让给右侧 —— 点赞按钮的
                // x 位置会随昵称长短浮动，多条评论之间对不齐。改成昵称吃满剩余宽度、
                // 点赞按钮不参与权重，才能保证右侧始终贴边对齐。
                CpText(
                    comment.user,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .let { if (onUserClick != null) it.clickable(onClick = onUserClick) else it },
                )
                if (comment.isHot) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        CpText(
                            s.hotBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                LikeButton(comment, model)
            }
            val meta = listOfNotNull(
                comment.time.takeIf { it.isNotBlank() },
                comment.ipLocation.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                CpText(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            CommentText(comment, s.replyTo(comment.replyToNickname ?: ""))
            CpText(
                s.reply,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable { model.startReply(comment) }
                    .padding(top = 2.dp, bottom = 2.dp, end = 8.dp),
            )
        }
    }
}

/**
 * 正文文本。回复楼层里的回复时，前置一段主题色的「回复 @昵称」——
 * 只有 [replyToNickname] 非空（即不是「直回楼主」）才加前缀。
 */
@Composable
private fun CommentText(comment: Comment, replyPrefix: String) {
    ProvideCpTextFullDisplay {
        val target = comment.replyToNickname
        if (target.isNullOrBlank()) {
            CpText(comment.content, style = MaterialTheme.typography.bodyMedium)
            return@ProvideCpTextFullDisplay
        }
        val primary = MaterialTheme.colorScheme.primary
        CpText(
            buildAnnotatedString {
                withStyle(SpanStyle(color = primary)) { append(replyPrefix) }
                append("  ")
                append(comment.content)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LikeButton(comment: Comment, model: CommentScreenModel) {
    val s = cpStrings().social.comment
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .clickable { model.toggleLike(comment) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Icon(
            if (comment.liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            contentDescription = if (comment.liked) s.unlike else s.like,
            tint = if (comment.liked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        if (comment.likedCount > 0) {
            Spacer(Modifier.width(4.dp))
            CpText(
                comment.likedCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (comment.liked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 底部输入条：无割裂感的沉浸浮动式胶囊 Dock。 */
@Composable
private fun CommentComposer(state: CommentUiState, model: CommentScreenModel) {
    val s = cpStrings().social.comment
    val isDark = LocalIsDarkTheme.current
    val focus = remember { FocusRequester() }
    // 点了「回复」就把焦点送进输入框
    LaunchedEffect(state.replyTarget?.id) {
        if (state.replyTarget != null) runCatching { focus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        state.replyTarget?.let { target ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Comment,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    CpText(
                        s.replyTo(target.user),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { model.cancelReply() }
                            .padding(2.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = s.cancelReply,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        // 浮动一体化药丸胶囊：输入与发送合一，告别横向割裂的方块底板
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraExtraLarge,
            color = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f)
            else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.18f else 0.30f),
            ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = state.draft,
                    onValueChange = model::updateDraft,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = {
                        CpText(
                            s.inputPlaceholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
                        )
                    },
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )

                if (state.draft.length >= COMMENT_MAX_LENGTH - 30) {
                    CpText(
                        text = "${state.draft.length}/$COMMENT_MAX_LENGTH",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.draftTooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }

                val sendBtnBg by animateColorAsState(
                    targetValue = if (state.canSend) MaterialTheme.colorScheme.primary else Color.Transparent,
                    animationSpec = CpMotion.effects(),
                    label = "sendBtnBg",
                )
                val sendBtnTint by animateColorAsState(
                    targetValue = if (state.canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    animationSpec = CpMotion.effects(),
                    label = "sendBtnTint",
                )

                FilledIconButton(
                    onClick = model::send,
                    enabled = state.canSend,
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = sendBtnBg,
                        contentColor = sendBtnTint,
                        disabledContainerColor = Color.Transparent,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = s.send,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }

        val hint = state.sendError ?: if (state.draftTooLong) s.tooLong(COMMENT_MAX_LENGTH) else null
        if (hint != null) {
            CpText(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp, start = 12.dp),
            )
        }
    }
}
