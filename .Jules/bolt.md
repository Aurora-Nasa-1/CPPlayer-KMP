# 2023-10-27

## Bottleneck
The `ProgressRow` in `PlayerScreen` was eagerly calling `formatTimeMs` on every recomposition triggered by the rapid updates of `state.positionMs` during playback. This resulted in continuous string allocations and excessive garbage collection overhead. Furthermore, `info.qualityLabel` was unnecessarily interpolated inside a template string.

## Solution
Wrapped the formatting calls in `remember` block, keying them with seconds (`state.positionMs / 1000` and `duration / 1000`). This ensures that string allocation only happens when the second changes instead of on every millisecond progress tick. Also removed the unnecessary string interpolation around `info.qualityLabel`.
