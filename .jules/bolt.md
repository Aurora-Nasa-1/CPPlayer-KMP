# 2024-08-28

- **Slider Continuous Recomposition Stutter:** Resolved a major performance bottleneck where the `Slider` components bound their engine seek methods directly to `onValueChange`, resulting in severe frame dropping during playback dragging. The fix involves using a local buffer state `seekProgress` for `onValueChange` and deferring the engine method execution to `onValueChangeFinished`.
- **Memoizing Graphic Objects:** Prevented the excessive recreation of `Brush.radialGradient` on every single frame during playback updates by successfully wrapping it in a `remember` block.
