# 2025-01-24

## Slider Recomposition Optimization
- **What:** Introduced local `seekValue` state for the `Slider` to handle `onValueChange` and bound engine seek action (`onSeek`) only to `onValueChangeFinished`.
- **Why:** The `Slider` was triggering `onSeek` on every local UI value change (continuously as the user drags). This caused severe stutter and heavy engine overhead because every pixel movement requested a playback position update.
- **Impact:** Seeking is now smooth. Engine is only called once when the user releases the slider.

## Brush GC Optimization
- **What:** Wrapped `Brush.radialGradient` initialization in a `remember` block keyed to `MaterialTheme.colorScheme` properties.
- **Why:** `DesktopPlayerScreen` frequently recomposes (every tick due to progress changes). Recreating a heavy graphics object like `Brush` on every recomposition causes heavy memory allocations and Garbage Collection (GC) overhead.
- **Impact:** `Brush` is no longer reallocated unnecessarily on every progress tick.
