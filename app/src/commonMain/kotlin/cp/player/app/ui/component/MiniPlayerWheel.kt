package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private const val WheelSeekStepMs = 5_000L
private const val WheelVolumeStep = 0.04f
private const val MaxWheelSteps = 8f

internal fun miniPlayerWheelSeekTarget(positionMs: Long, durationMs: Long, deltaY: Float): Long {
    if (durationMs <= 0L || deltaY == 0f) return positionMs.coerceAtLeast(0L)
    val steps = deltaY.coerceIn(-MaxWheelSteps, MaxWheelSteps)
    val target = positionMs - (steps * WheelSeekStepMs).toLong()
    return target.coerceIn(0L, durationMs)
}

internal fun miniPlayerWheelVolume(volume: Float, deltaY: Float): Float {
    val steps = deltaY.coerceIn(-MaxWheelSteps, MaxWheelSteps)
    return (volume - steps * WheelVolumeStep).coerceIn(0f, 1f)
}

/**
 * Desktop-only vertical wheel input. Nested controls consume their event before it
 * reaches the player surface, allowing the volume button to take precedence.
 */
@Composable
internal expect fun Modifier.miniPlayerMouseWheel(
    handleConsumed: Boolean = false,
    onScroll: (deltaY: Float) -> Unit,
): Modifier
