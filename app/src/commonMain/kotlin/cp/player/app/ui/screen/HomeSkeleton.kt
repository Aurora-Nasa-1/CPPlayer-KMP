package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cp.player.app.ui.component.CpShimmerBox
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.ScrollColumn

/**
 * 首页加载骨架屏（桌面端与移动端）。
 *
 * ## 设计纪律
 * 1. 结构与真实内容完全对齐（轮播图、电台快捷按钮、每日推荐卡片、发现歌单网格、排行榜）。
 * 2. 颜色统一使用 [CpShimmerBox] 的容器色阶（`surfaceContainerHigh` -> `surfaceContainerHighest`），
 *    自动适配浅色/深色主题。
 * 3. 严格遵循 [CpSpacing] 的边距规则，数据返回替换时无突兀跳动。
 */

private val DesktopBannerHeight = 260.dp
private val MobileBannerHeight = 168.dp

private fun responsiveFloat(width: Float, min: Float, max: Float, start: Float, end: Float): Float {
    if (width <= start) return min
    if (width >= end) return max
    val progress = (width - start) / (end - start)
    return min + (max - min) * progress
}

private fun responsiveDp(width: Float, min: Float, max: Float, start: Float, end: Float): Dp =
    responsiveFloat(width, min, max, start, end).dp

/**
 * 桌面端首页骨架屏。
 */
@Composable
fun DesktopHomeSkeleton(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthValue = maxWidth.value
        val horizontalPadding = responsiveDp(widthValue, min = 24f, max = 48f, start = 1200f, end = 2200f)
        val contentWidth = (widthValue - horizontalPadding.value * 2f)
            .coerceAtMost(CpSpacing.pageMaxWidth.value)
        val playlistColumns = CpSpacing.gridColumns(contentWidth.dp)

        ScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 24.dp,
                bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // 1. 焦点区：轮播图 (2f) + 快捷电台 (1f)
                Row(
                    modifier = Modifier.fillMaxWidth().height(DesktopBannerHeight),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    CpShimmerBox(
                        modifier = Modifier.weight(2f).fillMaxHeight(),
                        shape = MaterialTheme.shapes.large,
                    )
                    CpShimmerBox(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = MaterialTheme.shapes.large,
                    )
                }

                // 2. 每日推荐卡片骨架
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            MaterialTheme.shapes.large,
                        )
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CpShimmerBox(
                                modifier = Modifier.width(130.dp).height(22.dp),
                                shape = MaterialTheme.shapes.extraSmall,
                            )
                            CpShimmerBox(
                                modifier = Modifier.width(220.dp).height(13.dp),
                                shape = MaterialTheme.shapes.extraSmall,
                            )
                        }
                        CpShimmerBox(
                            modifier = Modifier.size(36.dp),
                            shape = MaterialTheme.shapes.small,
                        )
                    }

                    // 歌曲项网格（2 行，列数根据屏幕宽度自适应）
                    val trackCols = if (playlistColumns >= 5) 4 else 3
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        repeat(2) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                repeat(trackCols) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        CpShimmerBox(
                                            modifier = Modifier.size(48.dp),
                                            shape = RoundedCornerShape(12.dp),
                                        )
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            CpShimmerBox(
                                                modifier = Modifier.fillMaxWidth(0.72f).height(14.dp),
                                                shape = MaterialTheme.shapes.extraSmall,
                                            )
                                            CpShimmerBox(
                                                modifier = Modifier.fillMaxWidth(0.42f).height(11.dp),
                                                shape = MaterialTheme.shapes.extraSmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. 继续收听药丸条
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(playlistColumns.coerceAtLeast(4)) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerLow,
                                    MaterialTheme.shapes.medium,
                                )
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CpShimmerBox(
                                modifier = Modifier.size(36.dp),
                                shape = RoundedCornerShape(8.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CpShimmerBox(
                                    modifier = Modifier.fillMaxWidth(0.7f).height(12.dp),
                                    shape = MaterialTheme.shapes.extraSmall,
                                )
                                CpShimmerBox(
                                    modifier = Modifier.fillMaxWidth(0.4f).height(10.dp),
                                    shape = MaterialTheme.shapes.extraSmall,
                                )
                            }
                        }
                    }
                }

                // 4. 发现歌单网格
                HomeSkeletonSectionHeader(
                    titleWidth = 100.dp,
                    subtitleWidth = 160.dp,
                    hasTrailingChips = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    repeat(playlistColumns) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CpShimmerBox(
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                shape = MaterialTheme.shapes.medium,
                            )
                            CpShimmerBox(
                                modifier = Modifier.fillMaxWidth(0.85f).height(14.dp),
                                shape = MaterialTheme.shapes.extraSmall,
                            )
                            CpShimmerBox(
                                modifier = Modifier.fillMaxWidth(0.55f).height(11.dp),
                                shape = MaterialTheme.shapes.extraSmall,
                            )
                        }
                    }
                }

                // 5. 排行榜
                HomeSkeletonSectionHeader(
                    titleWidth = 80.dp,
                    subtitleWidth = 140.dp,
                )
                val rankingCols = if (playlistColumns >= 5) 3 else 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    repeat(rankingCols) {
                        CpShimmerBox(
                            modifier = Modifier.weight(1f).height(130.dp),
                            shape = MaterialTheme.shapes.medium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 移动端首页骨架屏。
 */
@Composable
fun MobileHomeSkeleton(modifier: Modifier = Modifier) {
    ScrollColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = CpSpacing.pageTop,
            bottom = 32.dp,
            start = CpSpacing.pageHorizontal,
            end = CpSpacing.pageHorizontal,
        ),
        verticalArrangement = Arrangement.spacedBy(CpSpacing.section),
    ) {
        // 1. 焦点轮播图
        CpShimmerBox(
            modifier = Modifier.fillMaxWidth().height(MobileBannerHeight),
            shape = MaterialTheme.shapes.large,
        )

        // 2. 快捷电台 3 个按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(3) {
                CpShimmerBox(
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = MaterialTheme.shapes.medium,
                )
            }
        }

        // 3. 每日推荐卡片
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    MaterialTheme.shapes.large,
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CpShimmerBox(
                        modifier = Modifier.width(100.dp).height(18.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    CpShimmerBox(
                        modifier = Modifier.width(140.dp).height(12.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                }
                CpShimmerBox(
                    modifier = Modifier.size(32.dp),
                    shape = MaterialTheme.shapes.small,
                )
            }

            repeat(3) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CpShimmerBox(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(10.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CpShimmerBox(
                            modifier = Modifier.fillMaxWidth(0.65f).height(14.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                        CpShimmerBox(
                            modifier = Modifier.fillMaxWidth(0.4f).height(11.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                    }
                }
            }
        }

        // 4. 发现歌单
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CpShimmerBox(
                modifier = Modifier.width(90.dp).height(18.dp),
                shape = MaterialTheme.shapes.extraSmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(3) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CpShimmerBox(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            shape = MaterialTheme.shapes.medium,
                        )
                        CpShimmerBox(
                            modifier = Modifier.fillMaxWidth(0.85f).height(12.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                    }
                }
            }
        }

        // 5. 排行榜
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CpShimmerBox(
                modifier = Modifier.width(80.dp).height(18.dp),
                shape = MaterialTheme.shapes.extraSmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(2) {
                    CpShimmerBox(
                        modifier = Modifier.weight(1f).height(120.dp),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSkeletonSectionHeader(
    titleWidth: Dp,
    subtitleWidth: Dp? = null,
    hasTrailingChips: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
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
        if (hasTrailingChips) {
            CpShimmerBox(
                modifier = Modifier.width(80.dp).height(28.dp),
                shape = MaterialTheme.shapes.small,
            )
        }
    }
}
