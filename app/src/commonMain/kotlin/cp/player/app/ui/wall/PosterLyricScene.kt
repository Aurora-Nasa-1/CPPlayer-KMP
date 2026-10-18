package cp.player.app.ui.wall

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.core.playback.SyncedLyricLine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal enum class PosterLyricStyle {
    Orbit, Clock, ColorField, Glow;
    companion object {
        fun fromSetting(value: Int) = entries.getOrElse(value) { Orbit }
    }
}

/** A local visual clock; pausing or leaving the theater suspends the frame loop. */
@Composable
internal fun rememberPosterSceneTime(playing: Boolean): State<Float> {
    val elapsed = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var previous = 0L
        while (true) {
            val frame = withFrameNanos { it }
            if (previous != 0L) {
                elapsed.floatValue = (elapsed.floatValue + (frame - previous) / 1_000_000_000f) % 3600f
            }
            previous = frame
        }
    }
    return elapsed
}

@Composable
internal fun PosterLyricScene(
    style: PosterLyricStyle,
    lyrics: List<SyncedLyricLine>,
    currentIndex: Int,
    position: State<Long>,
    time: State<Float>,
    fallbackTitle: String,
    modifier: Modifier = Modifier
) {
    val safeIndex = currentIndex.takeIf { it in lyrics.indices } ?: lyrics.indices.firstOrNull() ?: -1
    val line = lyrics.getOrNull(safeIndex)
    val previous = lyrics.getOrNull(safeIndex - 1)
    val next = lyrics.getOrNull(safeIndex + 1)

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(Color(0xFF07080C))) {
        PosterSceneBackdrop(style, time, Modifier.fillMaxSize())

        if (style == PosterLyricStyle.ColorField) {
            val bgChar = line?.text?.firstOrNull { it.isLetterOrDigit() }?.toString().orEmpty()
            if (bgChar.isNotBlank()) {
                Text(
                    text = bgChar,
                    color = Color.White.copy(alpha = 0.085f),
                    fontSize = 210.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = maxWidth * 0.10f)
                        .graphicsLayer {
                            rotationZ = -9f
                            translationX = sin(time.value * 0.15f) * 24.dp.toPx()
                        }
                )
            }
        }

        val diagonal = style == PosterLyricStyle.Orbit
        val detachedCaption = diagonal || style == PosterLyricStyle.ColorField
        val clockCaptionWidth = minOf(maxWidth * 0.62f, maxHeight * (0.20f / sin(14f * PI.toFloat() / 180f)))

        previous?.text?.takeIf { it.isNotBlank() && !detachedCaption }?.let { text ->
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.46f),
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (style == PosterLyricStyle.Clock) {
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = maxWidth * 0.31f, y = -maxHeight * 0.18f)
                        .width(clockCaptionWidth)
                        .graphicsLayer { rotationZ = -14f; transformOrigin = TransformOrigin(0f, 0.5f) }
                } else {
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = maxHeight * 0.17f)
                        .width(maxWidth * 0.56f)
                },
                textAlign = if (style == PosterLyricStyle.Clock) TextAlign.Start else TextAlign.Center
            )
        }

        next?.text?.takeIf { it.isNotBlank() && !detachedCaption }?.let { text ->
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.20f),
                fontSize = 19.sp,
                maxLines = if (style == PosterLyricStyle.Clock) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (style == PosterLyricStyle.Clock) {
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = maxWidth * 0.31f, y = maxHeight * 0.18f)
                        .width(clockCaptionWidth)
                        .graphicsLayer { rotationZ = 14f; transformOrigin = TransformOrigin(0f, 0.5f) }
                } else {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = maxHeight * 0.08f)
                        .width(maxWidth * 0.65f)
                }
            )
        }

        if (detachedCaption && line != null) {
            AnimatedContent(
                targetState = line,
                transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(240)) },
                label = "posterSpatialLyric",
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(maxWidth * 0.90f)
                    .height(maxHeight * 0.70f)
            ) { activeLine ->
                PosterSpatialLyrics(
                    style = style,
                    line = activeLine,
                    nextLine = next,
                    position = position,
                    time = time,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .then(
                        if (style == PosterLyricStyle.Clock) Modifier.padding(start = maxWidth * 0.31f, end = 28.dp).fillMaxWidth()
                        else Modifier.width(maxWidth * if (diagonal) 0.74f else 0.80f)
                    )
                    .graphicsLayer {
                        if (diagonal) {
                            rotationZ = -35f + sin(time.value * 0.22f) * 2f
                            translationY = sin(time.value * 0.30f) * 6.dp.toPx()
                        }
                        if (style == PosterLyricStyle.ColorField) {
                            rotationZ = sin(time.value * 0.18f) * 1.8f
                            translationX = sin(time.value * 0.12f) * 10.dp.toPx()
                        }
                    }
            ) {
                AnimatedContent(
                    targetState = line,
                    transitionSpec = {
                        (fadeIn(tween(380)) + slideInVertically(tween(460)) { it / 7 }) togetherWith
                            (fadeOut(tween(220)) + slideOutVertically(tween(380)) { -it / 7 })
                    },
                    label = "posterSceneLyric"
                ) { activeLine ->
                    if (activeLine == null) {
                        Text(
                            text = fallbackTitle,
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (style == PosterLyricStyle.Clock) Alignment.Start else Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = activeLine.text,
                                color = if (style == PosterLyricStyle.Glow) Color(0xFFFFF6DC) else Color.White,
                                fontSize = when (style) {
                                    PosterLyricStyle.Orbit -> 40.sp
                                    PosterLyricStyle.Clock -> 30.sp
                                    PosterLyricStyle.ColorField -> 42.sp
                                    PosterLyricStyle.Glow -> 36.sp
                                },
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = if (style == PosterLyricStyle.Clock) TextAlign.Start else TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            val activeTrans = activeLine.translation
                            if (!activeTrans.isNullOrBlank()) {
                                Text(
                                    text = activeTrans,
                                    color = Color.White.copy(alpha = 0.58f),
                                    fontSize = 16.sp,
                                    textAlign = if (style == PosterLyricStyle.Clock) TextAlign.Start else TextAlign.Center,
                                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }

        val lineTrans = line?.translation
        if (detachedCaption && lineTrans != null && lineTrans.isNotBlank()) {
            Text(
                text = lineTrans,
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = maxHeight * 0.09f)
                    .width(maxWidth * 0.78f)
            )
        }
    }
}

@Composable
private fun PosterSceneBackdrop(style: PosterLyricStyle, time: State<Float>, modifier: Modifier) {
    val points = remember { Random(71).let { random -> List(120) { Triple(random.nextFloat(), random.nextFloat(), random.nextFloat()) } } }
    val grain = remember { Random(43).let { random -> List(900) { Offset(random.nextFloat(), random.nextFloat()) } } }

    Canvas(modifier) {
        val phase = time.value
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        when (style) {
            PosterLyricStyle.Orbit -> {
                rotate(POSTER_ORBIT_AXIS_DEGREES, center) {
                    drawLine(Color.White.copy(alpha = 0.23f), center - Offset(0f, size.height * 0.62f), center + Offset(0f, size.height * 0.62f), 1.dp.toPx())
                    drawOval(
                        Color.White.copy(alpha = 0.07f),
                        center - Offset(size.height * 0.22f, 14.dp.toPx()),
                        Size(size.height * 0.44f, 28.dp.toPx()),
                        style = Stroke(0.6.dp.toPx())
                    )
                }
            }
            PosterLyricStyle.Clock -> {
                val wheel = Offset(size.width * 0.02f, size.height * 0.56f)
                val radius = size.height * 0.65f
                rotate(phase * 2.5f, wheel) {
                    for (ring in listOf(0.25f, 0.58f, 0.68f, 0.83f, 1f)) {
                        drawCircle(Color.White.copy(alpha = 0.19f), radius * ring, wheel, style = Stroke(0.8.dp.toPx()))
                    }
                    for (tick in 0 until 72) {
                        val angle = tick * PI.toFloat() / 36f
                        val direction = Offset(cos(angle), sin(angle))
                        drawLine(
                            Color.White.copy(alpha = if (tick % 6 == 0) 0.45f else 0.19f),
                            wheel + direction * radius * 0.86f,
                            wheel + direction * radius * if (tick % 6 == 0) 0.97f else 0.91f,
                            0.7.dp.toPx()
                        )
                    }
                    drawPath(posterGearPath(wheel, radius * 0.79f, 30), Color.White.copy(alpha = 0.28f), style = Stroke(1.dp.toPx()))
                    drawPath(posterGearPath(wheel, radius * 0.36f, 18), Color.White.copy(alpha = 0.20f), style = Stroke(0.8.dp.toPx()))
                }
                val small = wheel + Offset(radius * 0.40f, -radius * 0.69f)
                rotate(-phase * 5f, small) {
                    drawPath(posterGearPath(small, radius * 0.24f, 16), Color.White.copy(alpha = 0.28f), style = Stroke(1.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.22f), radius * 0.17f, small, style = Stroke(0.7.dp.toPx()))
                }
                drawLine(Color.White.copy(alpha = 0.38f), wheel, Offset(size.width * 0.30f, wheel.y), 0.7.dp.toPx())
            }
            PosterLyricStyle.ColorField -> {
                drawRect(
                    Brush.linearGradient(
                        listOf(Color(0xFF3E444E), Color(0xFF12141E), Color(0xFF45434A)),
                        start = Offset(size.width * (0.2f + sin(phase * 0.12f) * 0.12f), 0f),
                        end = Offset(size.width, size.height)
                    )
                )
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), center, size.width * 0.65f),
                    size.width * 0.65f,
                    center
                )
                grain.forEach {
                    drawCircle(Color.White.copy(alpha = 0.035f), 0.55.dp.toPx(), Offset(it.x * size.width, it.y * size.height))
                }
                rotate(8f + sin(phase * 0.2f), center) {
                    drawLine(Color.White.copy(alpha = 0.27f), Offset(size.width * 0.80f, -size.height), Offset(size.width * 0.80f, size.height * 2), 0.8.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.19f), Offset(-size.width, size.height * 0.48f), Offset(size.width * 2, size.height * 0.48f), 0.6.dp.toPx())
                    for (tick in 0..10) {
                        val y = size.height * tick / 10f
                        drawLine(Color.White.copy(alpha = 0.16f), Offset(size.width * 0.77f, y), Offset(size.width * 0.83f, y), 0.6.dp.toPx())
                    }
                }
            }
            PosterLyricStyle.Glow -> {
                val light = center + Offset(sin(phase * 0.17f) * size.width * 0.08f, cos(phase * 0.14f) * size.height * 0.05f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFFFFD88D).copy(alpha = 0.20f), Color(0xFFF5D9A2).copy(alpha = 0.035f), Color.Transparent),
                        light,
                        size.width * 0.44f
                    ),
                    size.width * 0.44f,
                    light
                )
                rotate(-18f + phase * 0.8f, light) {
                    repeat(5) { ring ->
                        val w = size.width * (0.08f + ring * 0.08f)
                        val h = w * 0.24f
                        drawOval(Color(0xFFFFE2A3).copy(alpha = 0.09f), light - Offset(w / 2f, h / 2f), Size(w, h), style = Stroke(0.7.dp.toPx()))
                    }
                }
                points.forEachIndexed { index, point ->
                    val x = point.first * size.width
                    val y = ((point.second + phase * (0.004f + point.third * 0.005f)) % 1f) * size.height
                    val alpha = (0.15f + (sin(phase * 0.8f + index) + 1f) * 0.3f).coerceIn(0f, 1f)
                    val spot = Offset(x, y)
                    val r = (1f + point.third * 2f).dp.toPx()
                    drawCircle(
                        Brush.radialGradient(listOf(Color(0xFFFFE6AA).copy(alpha = alpha * 0.34f), Color.Transparent), spot, r * 4f),
                        r * 4f,
                        spot
                    )
                    drawCircle(Color(0xFFFFF3D8).copy(alpha = alpha), r * 0.45f, spot)
                    if (index % 4 == 0) {
                        drawLine(Color(0xFFFFEABF).copy(alpha = alpha * 0.35f), spot - Offset(0f, r * 12f), spot + Offset(0f, r * 12f), 0.5.dp.toPx())
                        drawLine(Color(0xFFFFEABF).copy(alpha = alpha * 0.50f), spot - Offset(r * 3f, 0f), spot + Offset(r * 3f, 0f), 0.5.dp.toPx())
                    }
                }
            }
        }
        if (style != PosterLyricStyle.ColorField && style != PosterLyricStyle.Glow) {
            points.take(18).forEachIndexed { index, point ->
                val at = Offset(point.first * size.width, point.second * size.height)
                val radius = (12f + point.third * 28f).dp.toPx()
                rotate(phase * 0.7f + index * 31f, at) {
                    when (index % 3) {
                        0 -> drawCircle(Color.White.copy(alpha = 0.045f), radius, at, style = Stroke(0.6.dp.toPx()))
                        1 -> {
                            drawLine(Color.White.copy(alpha = 0.055f), at - Offset(radius, 0f), at + Offset(radius, 0f), radius * 0.4f)
                            drawLine(Color.White.copy(alpha = 0.055f), at - Offset(0f, radius), at + Offset(0f, radius), radius * 0.4f)
                        }
                        else -> {
                            val triangle = Path().apply {
                                moveTo(at.x, at.y - radius)
                                lineTo(at.x + radius, at.y + radius)
                                lineTo(at.x - radius, at.y + radius)
                                close()
                            }
                            drawPath(triangle, Color.White.copy(alpha = 0.035f))
                        }
                    }
                }
            }
        }
    }
}

private fun posterGearPath(center: Offset, radius: Float, teeth: Int) = Path().apply {
    repeat(teeth * 4) { step ->
        val angle = step * PI.toFloat() * 2f / (teeth * 4)
        val r = radius * if (step % 4 < 2) 1f else 0.94f
        val x = center.x + cos(angle) * r
        val y = center.y + sin(angle) * r
        if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
