package cp.player.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 页面级骨架块集合 —— [CpSongRowSkeleton] 之外的「别的页面长什么样」。
 *
 * ## 为什么按页面各写一套
 *
 * 骨架屏唯一的价值是**结构预言**：块的位置、大小、圆角必须与数据到位后的真内容
 * 对齐，否则替换瞬间会整屏跳一下，比居中转个圈更难受。所以这里不追求「一块通用
 * 灰条套所有页面」，而是每类页面各有一个与真实布局同构的组合；能复用的只有最底层
 * 的 [CpShimmerBox]。
 *
 * ## 三条纪律（与 [cpShimmer] 一致）
 *
 * 1. 只在真的在加载时挂载 —— 这些块各自持有一条无限动画。
 * 2. 圆角、封面边长照抄真组件（`AlbumCoverThumb` 的 28dp / `SongItem` 的 48dp …）。
 * 3. 调用方负责外层容器与内边距。**不要再加横向内边距**：页面根容器已经给了
 *    `CpSpacing.pageHorizontal`，再加一层会变成双重缩进。
 */

/**
 * 骨架屏上方的一行说明。
 *
 * 纯灰块说不出「在等什么」，长请求（读专辑 / 拉联系人）里用户会怀疑是不是卡死。
 * 保留一行 `bodySmall` 是最便宜的解释 —— 与 `LibraryScreen` 曲库骨架同一做法。
 */
@Composable
fun CpSkeletonCaption(text: String, modifier: Modifier = Modifier) {
    CpText(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/**
 * 区块标题的骨架：大标题 + 可选副标题 + 可选尾部动作。
 *
 * 尺寸照 [SectionHeader]：`titleLarge` ≈ 20dp 行高、`bodySmall` ≈ 11dp。
 */
@Composable
fun CpSectionHeaderSkeleton(
    modifier: Modifier = Modifier,
    titleWidth: Dp = 120.dp,
    subtitleWidth: Dp? = null,
    trailing: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CpShimmerBox(
                modifier = Modifier.width(titleWidth).height(20.dp),
                shape = MaterialTheme.shapes.extraSmall,
            )
            if (subtitleWidth != null) {
                CpShimmerBox(
                    modifier = Modifier.width(subtitleWidth).height(12.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                )
            }
        }
        if (trailing) {
            CpShimmerBox(
                modifier = Modifier.size(36.dp),
                shape = MaterialTheme.shapes.small,
            )
        }
    }
}

/**
 * 详情页 hero（专辑 / 歌单）：封面 + 标题 + 元信息 + 摘要 + 动作组 + 收藏胶囊。
 *
 * 宽屏与窄屏**分两套**，因为真组件就是两套：[AlbumHeader] 宽屏把封面放在左侧
 * 220dp 固定栏、文字占右侧；窄屏是竖排，封面宽度 `min(280dp, 62% 屏宽)`。
 * 用同一套骨架套两边，切到宽屏时会看到「封面突然从上方跳到左边」。
 *
 * @param wide 与 [CpBreakpoints.isExpanded] 同源，调用方传入。
 * @param coverSize 宽屏封面边长 —— 专辑 220dp；歌单若无封面，传 0 即整块省略。
 */
@Composable
fun CpDetailHeroSkeleton(
    wide: Boolean,
    modifier: Modifier = Modifier,
    coverSize: Dp = 220.dp,
    coverCorner: Dp = 28.dp,
) {
    val hasCover = coverSize > 0.dp
    if (wide) {
        Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (hasCover) {
                CpShimmerBox(
                    modifier = Modifier.size(coverSize),
                    shape = RoundedCornerShape(coverCorner),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                CpShimmerBox(
                    modifier = Modifier.fillMaxWidth(0.55f).height(32.dp),
                    shape = MaterialTheme.shapes.small,
                )
                Spacer(Modifier.height(10.dp))
                CpShimmerBox(
                    modifier = Modifier.fillMaxWidth(0.4f).height(14.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                )
                Spacer(Modifier.height(14.dp))
                TextBlockSkeleton(lines = 3, lastLineFraction = 0.45f)
                Spacer(Modifier.height(18.dp))
                CpShimmerBox(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large,
                )
                Spacer(Modifier.height(10.dp))
                CpShimmerBox(
                    modifier = Modifier.width(112.dp).height(32.dp),
                    shape = CircleShape,
                )
            }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            if (hasCover) {
                // ⚠️ `widthIn` 必须在 `fillMaxWidth` **之前**，否则约束已被钉死、
                // `widthIn` 变成空操作（同 [AlbumHeader] 里那条注释）。
                CpShimmerBox(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .fillMaxWidth(0.62f)
                        .aspectRatio(1f),
                    shape = RoundedCornerShape(coverCorner),
                )
                Spacer(Modifier.height(16.dp))
            }
            CpShimmerBox(
                modifier = Modifier.fillMaxWidth(0.7f).height(26.dp),
                shape = MaterialTheme.shapes.small,
            )
            Spacer(Modifier.height(8.dp))
            CpShimmerBox(
                modifier = Modifier.fillMaxWidth(0.5f).height(14.dp),
                shape = MaterialTheme.shapes.extraSmall,
            )
            Spacer(Modifier.height(14.dp))
            TextBlockSkeleton(lines = 3, lastLineFraction = 0.5f)
            Spacer(Modifier.height(16.dp))
            CpShimmerBox(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.large,
            )
            Spacer(Modifier.height(10.dp))
            CpShimmerBox(
                modifier = Modifier.width(112.dp).height(32.dp),
                shape = CircleShape,
            )
        }
    }
}

/**
 * 歌单详情页窄屏 hero：主播放双胶囊 + 次级动作轨。
 *
 * 与 [CpDetailHeroSkeleton] 不同 —— 歌单详情的标题在顶栏，hero 里**没有封面也没有
 * 标题**，只有两排练动作按钮。硬套「封面 + 标题」那套会让加载结束后整块塌掉一半。
 */
@Composable
fun CpPlaylistHeroSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        // 「全部播放（weight 1f）+ 随机播放」的不对称双胶囊。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CpShimmerBox(
                modifier = Modifier.weight(1f).height(56.dp),
                shape = CircleShape,
            )
            CpShimmerBox(
                modifier = Modifier.width(132.dp).height(56.dp),
                shape = CircleShape,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(5) {
                CpShimmerBox(
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = MaterialTheme.shapes.large,
                )
            }
        }
    }
}

/**
 * 个人主页 hero：居中大头像 + 昵称 + 签名 + 三列统计。
 *
 * 照 [ProfileHero] 的尺寸：头像 112dp 圆形、昵称 `headlineSmall`、统计是
 * 「数字（`titleMedium`）+ 标签（`labelSmall`）」两行一组。
 */
@Composable
fun CpProfileHeroSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CpShimmerBox(modifier = Modifier.size(112.dp), shape = CircleShape)
        Spacer(Modifier.height(14.dp))
        CpShimmerBox(
            modifier = Modifier.width(140.dp).height(26.dp),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.height(10.dp))
        CpShimmerBox(
            modifier = Modifier.width(220.dp).height(14.dp),
            shape = MaterialTheme.shapes.extraSmall,
        )
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            repeat(3) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CpShimmerBox(
                        modifier = Modifier.width(36.dp).height(20.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    CpShimmerBox(
                        modifier = Modifier.width(48.dp).height(11.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                }
            }
        }
    }
}

/**
 * 「卡片行」骨架：一条 `surfaceContainerHigh` 卡片里放缩略图 + 两行文字。
 *
 * 对应 [AlbumItem] / [PlaylistItem] / [ArtistItem] 这一族 —— 它们的共同点是
 * **外面套了一层卡片**（`shapes.largeIncreased`）、缩略图 60dp（歌手是 56dp 圆形）、
 * 内边距 16/12。搜索结果按类型切页签时，这三种结果长得几乎一样，所以共用一套。
 *
 * ⚠️ 卡片这层**必须画出来**：少了它，骨架的行距比真实列表紧 24dp，
 * 数据回来的瞬间整屏会往下"长"一截。
 *
 * @param circular 歌手头像用圆形，专辑 / 歌单封面用 12dp 圆角。
 */
@Composable
fun CpCardRowSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 6,
    thumbSize: Dp = 60.dp,
    circular: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
    ) {
        repeat(rows) { index ->
                // ⚠️ 卡片底必须比骨架块**低一档**（Low 而不是 High）：
                // `CpShimmerBox` 的底色就是 `surfaceContainerHigh`，卡片也用 High 的话
                // 块与卡片完全同色 —— 出图后是一整条纯色长条，什么都看不见。
                // （与 `MobileHomeSkeleton` 里卡片用 Low 是同一条理由。）
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.largeIncreased,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 块底色抬到 Highest：卡片是 Low，块若是默认的 High，两者只差一档，
                    // 出图后整条卡片看起来就是一块纯色（离屏核对时抓到的）。
                    CpShimmerBox(
                        modifier = Modifier.size(thumbSize),
                        shape = if (circular) CircleShape else RoundedCornerShape(12.dp),
                        base = MaterialTheme.colorScheme.surfaceContainerHighest,
                        highlight = MaterialTheme.colorScheme.surfaceBright,
                    )
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 每行宽度错开一点：等宽会让整列看起来像表格单元格。
                        CpShimmerBox(
                            modifier = Modifier.fillMaxWidth(0.5f + (index % 3) * 0.08f).height(14.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            base = MaterialTheme.colorScheme.surfaceContainerHighest,
                            highlight = MaterialTheme.colorScheme.surfaceBright,
                        )
                        CpShimmerBox(
                            modifier = Modifier.fillMaxWidth(0.32f + (index % 2) * 0.1f).height(11.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            base = MaterialTheme.colorScheme.surfaceContainerHighest,
                            highlight = MaterialTheme.colorScheme.surfaceBright,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 会话 / 联系人列表骨架。
 *
 * 照 [ContactRow]：左侧 52dp 圆形头像，右侧「昵称 + 最后一条消息」两行，
 * 行尾还有时间戳。单行高度按 76dp 估（头像 52 + 上下各 12）。
 */
@Composable
fun CpContactListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 8,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(rows) { index ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CpShimmerBox(modifier = Modifier.size(52.dp), shape = CircleShape)
                Column(
                    modifier = Modifier.weight(1f).padding(start = CpSpacing.formRowGap),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 昵称等长会看起来像表格 ⇒ 用比例宽度错开每一行。
                        CpShimmerBox(
                            modifier = Modifier.fillMaxWidth(0.34f + (index % 3) * 0.07f).height(14.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                        Spacer(Modifier.weight(1f))
                        CpShimmerBox(
                            modifier = Modifier.width(34.dp).height(10.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                    }
                    CpShimmerBox(
                        modifier = Modifier.fillMaxWidth(0.58f + (index % 2) * 0.12f).height(11.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                }
            }
        }
    }
}

/**
 * 聊天气泡骨架：左右交替，对方一侧带 32dp 头像。
 *
 * 与 [MessageBubble] 的形状一致（20dp 圆角，朝向那一侧收成 4dp 直角），
 * 这样「气泡从哪边长出来」在加载时就已经说清楚了。
 */
@Composable
fun CpChatBubblesSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 7,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(rows) { index ->
            val isMe = index % 2 == 1
            val bubbleShape = if (isMe) {
                RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
            } else {
                RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (!isMe) {
                    CpShimmerBox(modifier = Modifier.size(32.dp), shape = CircleShape)
                    Spacer(Modifier.width(8.dp))
                }
                Column(
                    modifier = Modifier.fillMaxWidth(if (index % 3 == 0) 0.62f else 0.44f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 高度也要有两档：全是 40dp 的等高条看起来像表格行，
                    // 真实的对话气泡就是一行的、两行的混在一起。
                    CpShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (index % 3 == 0) 56.dp else 40.dp),
                        shape = bubbleShape,
                    )
                }
            }
        }
    }
}

/** 多行文字的占位：最后一行**短一截**，否则看起来像一个段落方块而不像文字。 */
@Composable
private fun TextBlockSkeleton(
    lines: Int,
    lastLineFraction: Float,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(lines) { index ->
            val fraction =
                if (index == lines - 1) lastLineFraction else 0.92f - index * 0.06f
            CpShimmerBox(
                modifier = Modifier.fillMaxWidth(fraction).height(12.dp),
                shape = MaterialTheme.shapes.extraSmall,
            )
        }
    }
}
