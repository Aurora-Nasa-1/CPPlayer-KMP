package cp.player.app.ui.wall

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cp.player.app.i18n.cpStrings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val PosterInk = Color(0xFF10131B)
internal val PosterPanel = Color(0xFF1D2230)
internal val PosterMuted = Color(0xFFB5BED0)

val PosterWallIcon: ImageVector by lazy {
    ImageVector.Builder("PosterWall", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 3f); lineTo(13f, 3f); lineTo(13f, 13f); lineTo(3f, 13f); close()
            moveTo(15f, 3f); lineTo(21f, 3f); lineTo(21f, 9f); lineTo(15f, 9f); close()
            moveTo(15f, 11f); lineTo(21f, 11f); lineTo(21f, 21f); lineTo(15f, 21f); close()
            moveTo(3f, 15f); lineTo(13f, 15f); lineTo(13f, 21f); lineTo(3f, 21f); close()
        }
    }.build()
}

val PosterFullscreenIcon: ImageVector by lazy {
    ImageVector.Builder("PosterFullscreen", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 3f); lineTo(10f, 3f); lineTo(10f, 5f); lineTo(5f, 5f); lineTo(5f, 10f); lineTo(3f, 10f); close()
            moveTo(14f, 3f); lineTo(21f, 3f); lineTo(21f, 10f); lineTo(19f, 10f); lineTo(19f, 5f); lineTo(14f, 5f); close()
            moveTo(3f, 14f); lineTo(5f, 14f); lineTo(5f, 19f); lineTo(10f, 19f); lineTo(10f, 21f); lineTo(3f, 21f); close()
            moveTo(19f, 14f); lineTo(21f, 14f); lineTo(21f, 21f); lineTo(14f, 21f); lineTo(14f, 19f); lineTo(19f, 19f); close()
        }
    }.build()
}

internal data class ExpandedPoster(
    val song: PosterSong,
    val sourceIndex: Int,
    val origin: PosterRect,
    val originCornerRadius: Float = 13f,
    val stackKey: String? = null,
    val originRotation: Float = 0f
)

@Composable
internal fun PosterArtwork(
    song: PosterSong,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    sizePx: Int = 384
) {
    val hue = remember(song.artist, song.album) {
        ((song.artist + song.album).hashCode().toLong() and 0x7fffffff).rem(360).toFloat()
    }
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(Color.hsl(hue, 0.38f, 0.32f), Color.hsl((hue + 35) % 360, 0.45f, 0.12f))
            )
        )
    ) {
        if (!song.coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = song.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                song.title.take(1).uppercase(),
                color = Color.White.copy(alpha = 0.15f),
                fontSize = if (large) 112.sp else 64.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
internal fun PosterTile(
    song: PosterSong,
    rect: PosterRect,
    current: Boolean,
    artwork: @Composable (PosterSong, Modifier) -> Unit,
    onExpand: () -> Unit,
    onMore: (() -> Unit)?,
    selected: Boolean = false,
    showInfo: Boolean = true,
    modifier: Modifier = Modifier,
    stackSize: Int = 0,
    dropTarget: Boolean = false,
    dropBefore: Boolean = false
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(13.dp)
    val small = rect.width < 140f
    val s = cpStrings()

    Box(
        Modifier
            .fillMaxSize()
            .shadow(if (current) 12.dp else if (showInfo) 3.dp else 0.dp, shape)
            .clip(shape)
            .focusProperties { canFocus = false }
            .combinedClickable(
                onClickLabel = s.wall.posterWallSelected,
                onClick = onExpand,
                onLongClickLabel = s.wall.moreOptions,
                onLongClick = onMore
            )
            .then(modifier)
            .then(
                when {
                    dropTarget -> Modifier.border(4.dp, accent, shape)
                    selected -> Modifier.border(4.dp, Color.White, shape)
                    current -> Modifier.border(2.dp, accent.copy(alpha = 0.9f), shape)
                    else -> Modifier.border(0.6.dp, Color.White.copy(alpha = 0.12f), shape)
                }
            )
    ) {
        artwork(song, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.13f), Color.Black.copy(alpha = 0.86f))
                    )
                )
        )
        if (dropTarget) {
            Text(
                if (dropBefore) s.wall.posterWallInsertBelow else s.wall.posterWallInsertAbove,
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(accent)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        if (showInfo && stackSize > 1) {
            Text(
                "$stackSize / $MAX_POSTER_STACK_SIZE",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        if (current && showInfo) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimary)
                )
                if (!small) {
                    Text(
                        s.wall.posterWallCurrent,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 5.dp)
                    )
                }
            }
        }
        if (showInfo) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(if (small) 11.dp else 16.dp)
            ) {
                Text(
                    song.title.ifBlank { song.id },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (small) 15.sp else 22.sp,
                    lineHeight = if (small) 19.sp else 27.sp,
                    maxLines = if (rect.height > 140f) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!small || rect.height > 140f) {
                    Text(
                        song.artist.ifBlank { s.downloads.unknownArtist },
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun PosterIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .border(
                if (focused) 2.dp else 0.dp,
                if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                CircleShape
            )
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private val LocalPosterTransportFocus = staticCompositionLocalOf<FocusRequester?> { null }

@Composable
internal fun PosterPlayControls(
    playing: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    showSkipButtons: Boolean = true
) {
    val s = cpStrings()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showSkipButtons) {
            PosterIconButton(s.wall.skipPrevious, onPrevious) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = s.wall.skipPrevious,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        val focus = LocalPosterTransportFocus.current
        PosterIconButton(
            if (playing) s.wall.pause else s.wall.posterPlay,
            onToggle,
            Modifier
                .background(Color.White, CircleShape)
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) s.wall.pause else s.wall.posterPlay,
                tint = PosterInk,
                modifier = Modifier.size(26.dp)
            )
        }
        if (showSkipButtons) {
            PosterIconButton(s.wall.skipNext, onNext) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = s.wall.skipNext,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
internal fun PosterExpandedCard(
    poster: ExpandedPoster,
    viewport: PosterRect,
    current: Boolean,
    artwork: @Composable (PosterSong, Modifier) -> Unit,
    lyrics: @Composable (Modifier) -> Unit,
    seekBar: @Composable (Boolean) -> Unit,
    controls: @Composable () -> Unit,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    onClosed: () -> Unit,
    onFullscreen: (() -> Unit)? = null,
    onStackSwipe: ((Int) -> Unit)? = null,
    stackPosition: String? = null,
    stackSongs: List<PosterSong> = emptyList(),
    stackIndex: Int = 0
) {
    val progress = remember(poster.origin) { Animatable(0f) }
    var closing by remember(poster.origin) { mutableStateOf(false) }
    val closed by rememberUpdatedState(onClosed)
    val animation = spring<Float>(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = 0.01f)
    val density = LocalDensity.current.density
    val s = cpStrings()

    var swipeOffset by remember(poster.origin) { mutableFloatStateOf(0f) }
    val swipe by rememberUpdatedState(onStackSwipe)
    val scope = rememberCoroutineScope()
    var releasing by remember(poster.origin) { mutableStateOf(false) }
    val canSwipe = onStackSwipe != null && stackSongs.size > 1

    LaunchedEffect(poster.origin) { progress.animateTo(1f, animation) }
    LaunchedEffect(closing) {
        if (closing) {
            progress.animateTo(0f, animation)
            closed()
        }
    }
    val close = { closing = true }
    val transportFocus = remember { FocusRequester() }
    val controlsReady = !closing && progress.value > 0.85f

    val width = minOf(440f, viewport.width - 32f)
    val height = minOf(580f, viewport.height - 24f)
    val target = PosterRect((viewport.width - width) / 2, (viewport.height - height) / 2, width, height)
    val frame = posterMorphFrame(poster.origin, target, poster.originCornerRadius, progress.value)
    val rect = frame.rect
    val shape = RoundedCornerShape(frame.cornerRadius.dp)

    fun settle(commit: Boolean, widthPx: Float) {
        if (releasing) return
        releasing = true
        val start = swipeOffset
        scope.launch {
            try {
                val motion = Animatable(start)
                if (commit && kotlin.math.abs(start) >= 48f * density) {
                    motion.animateTo(if (start < 0f) -widthPx * 1.25f else widthPx * 1.25f, tween(160)) {
                        swipeOffset = value
                    }
                    if (!closing) {
                        swipe?.invoke(if (start < 0f) 1 else -1)
                    }
                } else {
                    motion.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 420f)) {
                        swipeOffset = value
                    }
                }
            } finally {
                swipeOffset = 0f
                releasing = false
            }
        }
    }

    // Backdrop
    Box(
        Modifier
            .fillMaxSize()
            .background(PosterInk.copy(alpha = progress.value * 0.78f))
            .pointerInput(Unit) { detectTapGestures(onTap = { close() }) }
            .semantics { onClick(label = s.common.close) { close(); true } }
    )

    // Morphing Card
    Box(
        Modifier
            .offset { IntOffset((rect.x * density).roundToInt(), (rect.y * density).roundToInt()) }
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .requiredSize(rect.width.dp, rect.height.dp)
            .graphicsLayer {
                val currentFrame = posterMorphFrame(poster.origin, target, poster.originCornerRadius, progress.value)
                translationX = (currentFrame.rect.centerX - target.centerX) * density + swipeOffset
                translationY = (currentFrame.rect.centerY - target.centerY) * density
                scaleX = currentFrame.rect.width / target.width
                scaleY = currentFrame.rect.height / target.height
                this.shape = RoundedCornerShape(currentFrame.cornerRadius.dp / maxOf(scaleX, scaleY))
                clip = true
                shadowElevation = (3f + 29f * progress.value) * density
                rotationZ = poster.originRotation * (1f - progress.value) + (swipeOffset / (width * density)).coerceIn(-1f, 1f) * 12f
            }
            .then(
                if (!canSwipe || !controlsReady || releasing) Modifier
                else Modifier.pointerInput(poster.origin, width, density) {
                    detectHorizontalDragGestures(
                        onDragCancel = { settle(false, width * density) },
                        onDragEnd = { settle(true, width * density) },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            swipeOffset += amount
                        }
                    )
                }
            )
            .background(PosterPanel)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
    ) {
        artwork(poster.song, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.72f), Color.Black.copy(alpha = 0.97f))
                    )
                )
        )

        if (!closing && progress.value > 0.85f) {
            val compact = height < 350f
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(if (compact) 14.dp else 22.dp)
            ) {
                // Top header row with status bar padding for edge-to-edge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val headerTitle = if (compact) {
                        poster.song.title
                    } else {
                        listOfNotNull(
                            if (current) s.wall.posterWallCurrent else s.wall.posterWallSelected,
                            stackPosition
                        ).joinToString(" · ")
                    }
                    Text(
                        headerTitle,
                        color = Color.White.copy(alpha = if (compact) 1f else 0.72f),
                        fontSize = if (compact) 20.sp else 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (current && onFullscreen != null) {
                        PosterIconButton(
                            s.wall.posterWallTheater,
                            onFullscreen,
                            if (compact) Modifier.size(32.dp) else Modifier
                        ) {
                            Icon(
                                PosterFullscreenIcon,
                                contentDescription = s.wall.posterWallTheater,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    PosterIconButton(
                        s.common.close,
                        close,
                        if (compact) Modifier.size(32.dp) else Modifier
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = s.common.close,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (current && !compact) {
                    lyrics(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }

                if (!compact) {
                    Text(
                        poster.song.title,
                        color = Color.White,
                        fontSize = 24.sp,
                        lineHeight = 29.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    poster.song.artist.ifBlank { s.downloads.unknownArtist },
                    color = PosterMuted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (compact) 0.dp else 6.dp, bottom = if (compact) 4.dp else 12.dp)
                )

                if (current) {
                    seekBar(compact)
                } else {
                    Spacer(Modifier.height(10.dp))
                }

                // Bottom control row with navigation bar padding for edge-to-edge
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (current) {
                            CompositionLocalProvider(LocalPosterTransportFocus provides transportFocus) {
                                controls()
                            }
                        } else {
                            var focused by remember { mutableStateOf(false) }
                            Row(
                                Modifier
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .focusRequester(transportFocus)
                                    .onFocusChanged { focused = it.isFocused }
                                    .border(if (focused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    .clickable(onClick = onPlay)
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = s.wall.posterPlay,
                                    tint = PosterInk,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    s.wall.posterPlay,
                                    color = PosterInk,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                    PosterIconButton(s.wall.moreOptions, onMore) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = s.wall.moreOptions,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

internal data class PosterMorphFrame(val rect: PosterRect, val cornerRadius: Float)

internal fun posterMorphFrame(origin: PosterRect, target: PosterRect, originRadius: Float, progress: Float): PosterMorphFrame {
    val fraction = progress.coerceIn(0f, 1f)
    fun mix(start: Float, end: Float) = start + (end - start) * fraction
    return PosterMorphFrame(
        PosterRect(
            mix(origin.x, target.x),
            mix(origin.y, target.y),
            mix(origin.width, target.width),
            mix(origin.height, target.height)
        ),
        mix(originRadius, 24f)
    )
}
