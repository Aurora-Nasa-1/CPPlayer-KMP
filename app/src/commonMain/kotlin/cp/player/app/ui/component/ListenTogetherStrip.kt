package cp.player.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.ui.screen.ListenTogetherScreen
import cp.player.app.ui.util.LocalRootNavigator
import cp.player.app.ui.util.pushOrNotify
import cp.player.core.listentogether.ListenTogetherState
import cp.player.core.util.currentTimeMillis

/**
 * 小播放器里的「一起听」条 —— **只在已经在房间里时出现**。
 *
 * ### 为什么挂在 [MiniPlayer] 里面
 * `MiniPlayer` 是**两个宿主共用**的组件（`App.kt` 的 `GlobalMiniPlayerHost` 与 `MainScreen`
 * 自己那一份）。挂在这里，一处改动就同时覆盖「三个 tab」与「所有路由页」，
 * 不必去改那两个宿主 —— 而宿主恰好是并行会话正在编辑的文件。
 *
 * 附带好处：小播放器的**尾留白**是按它的实测高度算的（`LocalMiniPlayerTailSpace`），
 * 这条挂在它内部，留白会**自动跟着变**，不会出现「条把列表最后一行压住」。
 *
 * ### 为什么它自己导航，而不是加回调参数
 * 加 `onOpenRoom: () -> Unit` 就必须回头改两个宿主把回调透传下来，
 * 而那两个文件当前正被其他会话占用。这里直接用 [LocalRootNavigator]：
 * 房间页是**整页**体验（要盖住窗口含侧栏），本来就该 push 到根 Navigator
 * （与 `PlayerMoreBottomSheet` 里那个入口同一条理由）。
 *
 * ### 分成「包装 + 无状态内容」两层
 * 内容层不碰 `AppModel`，所以能被**离屏渲染**出来核对版式（编译和单测都量不到宽度）。
 */
@Composable
fun ListenTogetherStrip(modifier: Modifier = Modifier) {
    val state by AppModel.listenTogetherState.collectAsState()
    val navigator = LocalRootNavigator.current
    ListenTogetherStripContent(
        state = state,
        onOpen = if (navigator == null) null else { { navigator.pushOrNotify(ListenTogetherScreen()) } },
        modifier = modifier,
    )
}

/**
 * 条的内容（无状态）。
 *
 * 不在房间时**完全不绘制**：返回早退，而不是「画一条空的高度」——
 * 后者会在小播放器顶部留一条看不见的缝。
 */
@Composable
internal fun ListenTogetherStripContent(
    state: ListenTogetherState,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val room = state.room
    if (!state.inRoom || room == null) return

    // 结构：条在上、分隔线在**条与播放内容之间**、播放内容在下。
    // ⚠️ 分隔线不能放在条的上方 —— 那是卡片的最顶端，贴着圆角边几乎看不见，
    // 起不到「把这条和播放器正文分开」的作用（离屏出图实测）。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = onOpen != null) { onOpen?.invoke() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 用 secondaryContainer 作底：小播放器本身是 surfaceContainerHigh，
        // 拿 surfaceContainerHighest 当底在深色下等于隐形（本仓库踩过）。
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Headphones,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Text(
            text = "一起听",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = buildString {
                append("${room.members.size} 人")
                if (state.isOwner()) append(" · 房主")
                append(LT_REMAINING_SEPARATOR)
                append(ltTtl(room.createdAtMs, room.effectiveDurationMs).shortText())
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 撑开：箭头是「可以点进去」的提示，要贴着右边缘才读得懂，
        // 紧跟文字会让它看起来像个多余的标点。
        Spacer(Modifier.weight(1f))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "进入一起听房间",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private const val LT_REMAINING_SEPARATOR = " · "

/**
 * 房间剩余有效期（**唯一一份**时间算法）。
 *
 * 把「怎么算」与「怎么显示」分开：小播放器那条要极短（「剩余 29 分」），
 * 房间页要说得完整（「剩余约 29 分钟」），但 `createdAt + duration - now` 这段算术
 * 只能有一份 —— 两份必然漂移，而这类漂移表现为「两个地方显示的剩余时间不一样」。
 *
 * 房间是**服务端**限时的（实测 `effectiveDurationMs` = 30 分钟），到点即失效。
 * 必须一直可见，否则表现为「听着听着突然掉线」且不知道为什么。
 */
internal sealed interface LtTtl {
    /** 上游没给时间字段。 */
    data object Unknown : LtTtl

    /** 已过有效期。 */
    data object Expired : LtTtl

    /** 还剩 [minutes] 分钟（≥1）。 */
    data class Left(val minutes: Long) : LtTtl

    /** 不足一分钟。 */
    data object Imminent : LtTtl
}

internal fun ltTtl(createdAtMs: Long?, durationMs: Long?): LtTtl {
    if (createdAtMs == null || durationMs == null || durationMs <= 0L) return LtTtl.Unknown
    val left = createdAtMs + durationMs - currentTimeMillis()
    if (left <= 0L) return LtTtl.Expired
    val minutes = left / 60_000L
    return if (minutes >= 1) LtTtl.Left(minutes) else LtTtl.Imminent
}

/** 小播放器那条用的紧凑文案。 */
internal fun LtTtl.shortText(): String = when (this) {
    LtTtl.Unknown -> "时长未知"
    LtTtl.Expired -> "已到期"
    LtTtl.Imminent -> "即将到期"
    is LtTtl.Left -> "剩余 $minutes 分"
}
