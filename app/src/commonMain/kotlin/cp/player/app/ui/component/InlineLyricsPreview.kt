package cp.player.app.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.shareText
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.model.MobilePlayerCustomConfig
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.core.lyrics.RubyText
import cp.player.core.playback.LyricsState
import cp.player.core.playback.SyncedLyricLine

/**
 * 嵌入在封面与播放控制之间的两行式（0.5 + 1 + 0.5）紧凑歌词预览组件。
 *
 * M3 Expressive 优化：
 * 1. 结构精巧：中间 1 行展示当前正在唱的歌词（全亮、加粗、支持逐字卡拉OK平滑变色），
 *    上部展示 0.5 行上一句歌词，下部展示 0.5 行下一句歌词；
 * 2. 上下边缘应用柔和的 Alpha 线性渐变遮罩，使前句与后句自然隐退；
 * 3. 具有 M3 Expressive 弹性按压反馈（cpPressScale）与轻量触觉震动反馈；
 * 4. 点击即可平滑展开至 Apple Music 风格的全屏沉浸歌词界面，长按可复制当前歌词；
 * 5. 高度随用户字号微调设置项动态缩放，与现代 Android 界面保持一致质感。
 */
@Composable
fun InlineLyricsPreview(
    lyricsState: LyricsState,
    positionMs: Long,
    isPlaying: Boolean,
    config: MobilePlayerCustomConfig,
    trackId: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val isDark = LocalIsDarkTheme.current
    val haptics = LocalCpHaptics.current
    val lyricOffsetMs = if (!trackId.isNullOrBlank()) {
        cp.player.app.ui.util.LyricOffsetManager.getOffset(trackId)
    } else 0L

    // 平滑位置外推源，保持逐字动效高帧率
    val positionSource = remember(trackId) { SmoothPositionSource() }
    positionSource.update(positionMs, isPlaying)
    val smoothPosition = remember(trackId) { mutableLongStateOf(positionMs) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            smoothPosition.longValue = positionSource.currentMs()
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { smoothPosition.longValue = positionSource.currentMs() }
        }
    }

    LaunchedEffect(positionMs, isPlaying) {
        if (!isPlaying) {
            smoothPosition.longValue = positionSource.currentMs()
        }
    }

    val effectivePos = (smoothPosition.longValue + lyricOffsetMs).coerceAtLeast(0L)

    val containerShape = RoundedCornerShape(18.dp)
    val containerBg = MaterialTheme.colorScheme.surfaceContainerHighest.copy(
        alpha = if (isDark) 0.38f else 0.52f
    )
    val containerBorder = BorderStroke(
        width = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.18f else 0.28f)
    )

    val currentLineText = remember(lyricsState, effectivePos) {
        if (lyricsState is LyricsState.Success && lyricsState.lines.isNotEmpty()) {
            val idx = lyricsState.lines.indexOfLast { it.time <= effectivePos }
            if (idx >= 0) RubyText.strip(lyricsState.lines[idx].text) else ""
        } else ""
    }

    Surface(
        onClick = {
            haptics.perform(CpHaptic.Tick)
            onClick()
        },
        shape = containerShape,
        color = containerBg,
        border = containerBorder,
        modifier = modifier
            .fillMaxWidth()
            .cpPressScale(pressedScale = 0.985f)
            .clip(containerShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            when (lyricsState) {
                is LyricsState.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CpLoadingIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        CpText(
                            text = s.player.lyricsLoading,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is LyricsState.NoLyrics -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.width(6.dp))
                        CpText(
                            text = s.player.lyricsEmpty,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is LyricsState.Error -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CpText(
                            text = s.player.lyricsError(lyricsState.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                is LyricsState.Success -> {
                    val lines = lyricsState.lines
                    if (lines.isEmpty()) {
                        CpText(
                            text = s.player.lyricsEmpty,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LyricsThreeLineContent(
                            lines = lines,
                            currentPos = effectivePos,
                            config = config,
                        )
                    }
                }
                LyricsState.Idle -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CpText(
                            text = s.player.lyricsIdle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 右上角微型「展开歌词」胶囊角标
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (isDark) 0.55f else 0.70f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Lyrics,
                        contentDescription = "展开完整歌词",
                        modifier = Modifier.size(11.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 0.5 + 1 + 0.5 经典双行歌词展示核心区。
 */
@Composable
private fun LyricsThreeLineContent(
    lines: List<SyncedLyricLine>,
    currentPos: Long,
    config: MobilePlayerCustomConfig,
) {
    val textAlign = if (config.lyricsAlignStart) TextAlign.Start else TextAlign.Center
    val contentAlignment = if (config.lyricsAlignStart) Alignment.Start else Alignment.CenterHorizontally

    val curIdx = lines.indexOfLast { it.time <= currentPos }

    val prevLine = if (curIdx > 0) lines.getOrNull(curIdx - 1) else null
    val curLine = if (curIdx >= 0) lines.getOrNull(curIdx) else lines.firstOrNull()
    val nextLine = if (curIdx >= 0) lines.getOrNull(curIdx + 1) else lines.getOrNull(1)

    // 上下 0.5 行高度与文字大小
    val baseFontSize = config.lyricsFontSizeSp
    val subFontSize = (baseFontSize * 0.76f).sp
    val mainFontSize = baseFontSize.sp
    val subLineHeight = (baseFontSize * 0.95f).sp
    val mainLineHeight = (baseFontSize * 1.35f).sp

    // 上下边界渐变羽化遮罩，营造 0.5 行半隐渐退感
    val fadeBrush = remember {
        Brush.verticalGradient(
            0.00f to Color.Transparent,
            0.22f to Color.Black,
            0.78f to Color.Black,
            1.00f to Color.Transparent,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(brush = fadeBrush, blendMode = BlendMode.DstIn)
            }
            .padding(vertical = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = contentAlignment,
            verticalArrangement = Arrangement.spacedBy(1.5.dp),
        ) {
            // 上半行（0.5 行露出）：前一句歌词
            val prevText = prevLine?.let { RubyText.strip(it.text) }.orEmpty()
            CpText(
                text = if (prevText.isNotBlank()) prevText else " ",
                fontSize = subFontSize,
                lineHeight = subLineHeight,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            // 中间整行（1.0 行）：当前歌词（带逐字卡拉OK高亮与翻译）
            if (curLine != null) {
                ActiveLyricLineView(
                    line = curLine,
                    currentPos = currentPos,
                    fontSize = mainFontSize,
                    lineHeight = mainLineHeight,
                    textAlign = textAlign,
                    karaokeEnabled = config.karaokeEnabled,
                    showTranslation = config.showTranslation,
                )
            } else {
                CpText(
                    text = "♪ 音乐前奏 ♪",
                    fontSize = mainFontSize,
                    lineHeight = mainLineHeight,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    textAlign = textAlign,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 下半行（0.5 行露出）：后一句歌词
            val nextText = nextLine?.let { RubyText.strip(it.text) }.orEmpty()
            CpText(
                text = if (nextText.isNotBlank()) nextText else " ",
                fontSize = subFontSize,
                lineHeight = subLineHeight,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 正在发声的中央主歌词行。
 *
 * 继承已有逐字（words）时间模型，逐音节平滑点亮；若无逐字时间则以高对比度强调色整行呈现。
 */
@Composable
private fun ActiveLyricLineView(
    line: SyncedLyricLine,
    currentPos: Long,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    textAlign: TextAlign,
    karaokeEnabled: Boolean,
    showTranslation: Boolean,
) {
    val rawText = RubyText.strip(line.text)
    val hasWords = line.words.isNotEmpty() && karaokeEnabled

    val primaryColor = MaterialTheme.colorScheme.primary
    val activeColor = MaterialTheme.colorScheme.onSurface
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)

    if (hasWords) {
        // 构建带有逐字染色效果的 AnnotatedString
        val annotated = buildAnnotatedString {
            for (w in line.words) {
                val cleanWordText = RubyText.strip(w.text)
                val isSung = currentPos >= w.endTime
                val isCurrent = currentPos in w.beginTime..w.endTime

                val color = when {
                    isSung -> primaryColor
                    isCurrent -> primaryColor
                    else -> inactiveColor
                }
                val weight = if (isSung || isCurrent) FontWeight.Bold else FontWeight.Medium

                withStyle(SpanStyle(color = color, fontWeight = weight)) {
                    append(cleanWordText)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (textAlign == TextAlign.Start) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            CpText(
                text = annotated,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = FontWeight.Bold,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = primaryColor,
                emphasized = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 可选翻译行
            if (showTranslation && !line.translation.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                CpText(
                    text = line.translation,
                    fontSize = (fontSize.value * 0.72f).sp,
                    lineHeight = (fontSize.value * 0.95f).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    } else {
        // 普通整行同步模式
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (textAlign == TextAlign.Start) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            CpText(
                text = rawText,
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                emphasized = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (showTranslation && !line.translation.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                CpText(
                    text = line.translation,
                    fontSize = (fontSize.value * 0.72f).sp,
                    lineHeight = (fontSize.value * 0.95f).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
