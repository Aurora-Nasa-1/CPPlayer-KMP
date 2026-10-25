package cp.player.app.ui.wall

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.core.playback.SyncedLyricLine
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import cp.player.app.ui.component.CpText

@Composable
internal fun PosterSpatialLyrics(
    style: PosterLyricStyle,
    line: SyncedLyricLine,
    nextLine: SyncedLyricLine?,
    position: State<Long>,
    time: State<Float>,
    modifier: Modifier = Modifier
) {
    val tokens = remember(line, nextLine) { posterLyricWords(line, nextLine) }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val scattered = style == PosterLyricStyle.ColorField
        val desiredSize = (if (scattered) 46f else 36f) / sqrt((tokens.size / 8f).coerceAtLeast(1f))
        val measured = remember(tokens, desiredSize, density) {
            tokens.map { token ->
                val size = textMeasurer.measure(
                    token.text,
                    TextStyle(fontWeight = FontWeight.Bold, fontSize = desiredSize.sp)
                ).size
                PosterWordSize(
                    size.width.toFloat() + with(density) { 16.dp.toPx() },
                    maxOf(size.height.toFloat(), with(density) { (desiredSize * 1.5f).sp.toPx() })
                )
            }
        }
        val fit = remember(measured, widthPx, heightPx, scattered) {
            minOf(
                1f,
                widthPx * (if (scattered) 0.62f else 0.45f) / (measured.maxOfOrNull { it.width } ?: 1f).coerceAtLeast(1f),
                if (scattered) sqrt(widthPx * heightPx * 0.31f / measured.sumOf { (it.width * it.height).toDouble() }.toFloat().coerceAtLeast(1f)) else 1f
            )
        }
        val sizes = remember(measured, fit) { measured.map { PosterWordSize(it.width * fit, it.height * fit) } }
        val drift = with(density) { 3.dp.toPx() }
        val placements = remember(sizes, widthPx, heightPx, line.time, line.text, scattered) {
            if (scattered) {
                posterScatteredWords(sizes, widthPx, heightPx, line.text.hashCode() xor line.time.hashCode(), drift)
            } else emptyList()
        }
        val (radius, orbitHeight) = posterOrbitDimensions(
            widthPx,
            heightPx,
            (sizes.maxOfOrNull { it.width } ?: 0f) + drift * 2f,
            (sizes.maxOfOrNull { it.height } ?: 0f) + drift * 2f
        )

        tokens.forEachIndexed { index, token ->
            key(index, token.time, token.text) {
                val isLit = position.value >= token.time
                val alphaVal = if (isLit) 1f else 0.42f
                val wordWidth = with(density) { sizes.getOrNull(index)?.width?.toDp() ?: 40.dp }

                CpText(
                    text = token.text,
                    color = Color.White.copy(alpha = alphaVal),
                    fontSize = (desiredSize * fit).sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(wordWidth)
                        .graphicsLayer {
                            if (scattered && index in placements.indices) {
                                val placement = placements[index]
                                translationX = placement.x - widthPx / 2f + sin(time.value * 0.25f + placement.phase) * drift
                                translationY = placement.y - heightPx / 2f + cos(time.value * 0.21f + placement.phase) * drift
                                rotationZ = placement.rotation
                            } else {
                                val frame = posterOrbitFrame(index, tokens.size, time.value, radius, orbitHeight).onPosterAxis()
                                translationX = frame.x
                                translationY = frame.y
                                rotationY = frame.rotationY
                                scaleX = frame.scale
                                scaleY = frame.scale
                                alpha = frame.alpha * alphaVal
                                cameraDistance = 24.dp.toPx()
                            }
                        }
                )
            }
        }
    }
}
