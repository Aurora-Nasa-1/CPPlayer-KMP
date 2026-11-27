@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.theme.CoverFluidPaletteCache
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.theme.cpNestedSurface
import cp.player.app.ui.util.rememberCoverImageRequest
import cp.player.core.music.ArtistSummary
import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.PlaybackSessionSettings

/**
 * 封面舞台容器：柔和主色外晕 + 播放状态圆角/微缩呼吸 + 1dp 玻璃微光内描边。
 *
 * 桌面端与平板端共用同一套视觉质感；不依赖 `AppModel.playback`，可直接用于离屏渲染。
 */
@Composable
internal fun PlayerArtworkStage(
    track: TrackSummary,
    isPlaying: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    playingCorner: Dp = 20.dp,
    pausedCorner: Dp = 28.dp,
    showAmbientGlow: Boolean = true,
    pinTopEdge: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val isDark = LocalIsDarkTheme.current
    val primary = MaterialTheme.colorScheme.primary
    // 订阅当前封面动态色盘，保证异步取色完成后光晕同步平滑切换。
    val currentFluidPalette by AppModel.currentCoverFluidPalette.collectAsState()
    val rawGlowColor = remember(track.coverUrl, isDark, currentFluidPalette, primary) {
        val cached = track.coverUrl?.takeIf { it.isNotBlank() }?.let {
            CoverFluidPaletteCache.get(it, isDark)
        }
        cached?.firstOrNull() ?: currentFluidPalette?.firstOrNull() ?: primary
    }
    val glowColor by animateColorAsState(
        targetValue = rawGlowColor,
        animationSpec = CpMotion.effectsSlow(),
        label = "playerArtworkGlowColor",
    )
    // 20dp/28dp 对应 ShapeTokens 的 largeIncreased / extraLarge，因驱动连续呼吸动画采用 Dp 状态。
    val corner by animateDpAsState(
        targetValue = if (isPlaying) playingCorner else pausedCorner,
        animationSpec = CpMotion.spatialSlow(),
        label = "playerArtworkCorner",
    )
    val scale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.96f,
        animationSpec = CpMotion.spatialSlow(),
        label = "playerArtworkScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (isPlaying) 16.dp else 8.dp,
        animationSpec = CpMotion.spatialSlow(),
        label = "playerArtworkElevation",
    )
    val shape = remember(corner) { RoundedCornerShape(corner) }
    val targetPx = with(LocalDensity.current) { size.toPx() }.toInt().coerceIn(160, 1024)

    val borderBrush = remember(isDark) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (isDark) 0.18f else 0.50f),
                Color.White.copy(alpha = if (isDark) 0.03f else 0.12f),
            ),
        )
    }

    Box(
        modifier = modifier
            .then(
                if (showAmbientGlow) {
                    Modifier.drawBehind {
                        val center = Offset(this.size.width / 2f, this.size.height * 0.54f)
                        val radius = this.size.maxDimension * 0.82f
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to glowColor.copy(alpha = if (isDark) 0.24f else 0.16f),
                                0.55f to glowColor.copy(alpha = if (isDark) 0.08f else 0.05f),
                                1f to Color.Transparent,
                                center = center,
                                radius = radius,
                            ),
                            radius = radius,
                            center = center,
                        )
                    }
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = artworkModifier
                .size(size)
                .aspectRatio(1f)
                .graphicsLayer {
                    if (pinTopEdge) transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = glowColor.copy(alpha = if (isDark) 0.24f else 0.14f),
                    spotColor = Color.Black.copy(alpha = if (isDark) 0.34f else 0.20f),
                )
                .clip(shape)
                .border(BorderStroke(1.dp, borderBrush), shape),
        ) {
            CpArtworkImage(
                request = rememberCoverImageRequest(track.coverUrl, targetPx = targetPx, placeholderBucketPx = 256),
                contentDescription = track.album ?: track.name,
                contentScale = ContentScale.Crop,
                placeholderCorner = corner,
                modifier = Modifier.fillMaxSize(),
            )
            overlay()
        }
    }
}

/**
 * 播放页左栏曲目信息头（专辑眉标 + 曲名 + 歌手 + MV/收藏动作 + 音质/引擎胶囊）。
 */
@Composable
internal fun PlayerTrackInfoBlock(
    state: PlaybackUiState,
    track: TrackSummary,
    titleStyle: TextStyle,
    artistStyle: TextStyle,
    onArtistClick: ((ArtistSummary) -> Unit)?,
    onLike: () -> Unit,
    onOpenMv: ((Long) -> Unit)?,
    onOpenAudioEngine: (() -> Unit)?,
    showMetaChips: Boolean = true,
    touchFriendly: Boolean = false,
    outlinedQualityBadge: Boolean = false,
    titleModifier: Modifier = Modifier,
    artistModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val colors = MaterialTheme.colorScheme
    val actionSize = if (touchFriendly) CpSpacing.touchTarget else 36.dp
    val iconSize = if (touchFriendly) 22.dp else 19.dp

    Column(modifier = modifier.fillMaxWidth()) {
        if (!track.album.isNullOrBlank()) {
            AlbumEyebrow(
                album = track.album,
                fallback = s.player.nowPlaying,
            )
            Spacer(Modifier.height(4.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                CpText(
                    text = track.name,
                    style = titleStyle,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    emphasized = true,
                    marqueeEnabled = state.isPlaying,
                    modifier = titleModifier.fillMaxWidth(),
                )
                TrackArtistText(
                    track = track,
                    onArtistClick = onArtistClick,
                    style = artistStyle.copy(textAlign = TextAlign.Start),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = artistModifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                track.mvId?.takeIf { it > 0 }?.let { mvId ->
                    if (onOpenMv != null) {
                        PlayerInlineActionButton(
                            icon = Icons.Rounded.SmartDisplay,
                            label = s.player.playMv,
                            size = actionSize,
                            iconSize = iconSize,
                            showTooltip = !touchFriendly,
                            onClick = { onOpenMv(mvId) },
                        )
                    }
                }
                PlayerInlineActionButton(
                    icon = if (state.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    label = if (state.isFavorite) s.player.unfavoriteTrack else s.player.favoriteTrack,
                    active = state.isFavorite,
                    size = actionSize,
                    iconSize = CpSpacing.playerFavoriteIcon,
                    showTooltip = !touchFriendly,
                    onClick = onLike,
                )
            }
        }
        if (showMetaChips) {
            PlayerMetaBadgeRow(
                state = state,
                onOpenAudioEngine = onOpenAudioEngine,
                outlinedQualityBadge = outlinedQualityBadge,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

@Composable
internal fun PlayerInlineActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    size: Dp = 36.dp,
    iconSize: Dp = 19.dp,
    showTooltip: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(size).cpPressScale(),
            shape = CircleShape,
            color = Color.Transparent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(iconSize),
                    tint = if (active) colors.primary else colors.onSurfaceVariant,
                )
            }
        }
    }
    if (showTooltip) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { CpText(label) } },
            state = rememberTooltipState(),
        ) { content() }
    } else {
        content()
    }
}

@Composable
internal fun PlayerMetaBadgeRow(
    state: PlaybackUiState,
    onOpenAudioEngine: (() -> Unit)?,
    modifier: Modifier = Modifier,
    outlinedQualityBadge: Boolean = false,
) {
    val s = cpStrings()
    val colors = MaterialTheme.colorScheme
    val formatQuality = state.formatInfo?.qualityLabel?.takeIf { it.isNotBlank() }
    val sourceQuality = state.sourceQualityLabel?.takeIf { it.isNotBlank() }
    val qualityLabel = when {
        sourceQuality != null && formatQuality != null -> "$sourceQuality · $formatQuality"
        sourceQuality != null -> sourceQuality
        else -> formatQuality
    }
    val isAndroid = cp.player.app.platform.isAndroidPlatform()
    val vipActive = cp.player.core.license.VipLicenseManager.licenseState.collectAsState().value.isActivated
    val engineKey = AppModel.audioEngineFlow.collectAsState().value
    val exclusiveOn = AppModel.wasapiExclusiveFlow.collectAsState().value
    val engineChip = when {
        isAndroid && vipActive && engineKey == PlaybackSessionSettings.ENGINE_FLICK_HIRES -> "Flick Hi-Res"
        !isAndroid && vipActive && exclusiveOn -> s.player.exclusiveBadge
        else -> null
    }
    if (qualityLabel == null && engineChip == null) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        qualityLabel?.let {
            PlayerBadgePill(
                text = it,
                container = if (outlinedQualityBadge) colors.onSurface.copy(alpha = 0.07f) else cpNestedSurface().copy(alpha = 0.72f),
                content = colors.onSurfaceVariant,
                outlined = outlinedQualityBadge,
            )
        }
        if (engineChip != null) {
            PlayerBadgePill(
                text = engineChip,
                container = colors.tertiaryContainer,
                content = colors.onTertiaryContainer,
                onClick = onOpenAudioEngine,
            )
        }
    }
}

@Composable
private fun PlayerBadgePill(
    text: String,
    container: Color,
    content: Color,
    onClick: (() -> Unit)? = null,
    outlined: Boolean = false,
) {
    val shape = if (outlined) CircleShape else MaterialTheme.shapes.small
    val label: @Composable () -> Unit = {
        CpText(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = container) { label() }
    } else {
        Surface(
            shape = shape,
            color = container,
            border = if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)) else null,
        ) { label() }
    }
}
