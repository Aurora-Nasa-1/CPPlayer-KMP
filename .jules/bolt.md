# 2026-09-01

- **Bottleneck Identified**: Found memory allocations occurring during constant recompositions on the `DesktopPlayerScreen.kt`. `Brush.radialGradient()` was being newly instantiated on every recomposition tick (e.g. from the continuously updated media progress).
- **Optimization Applied**: Wrapped `Brush.radialGradient` in a `remember` block, keyed to its static color dependencies (`MaterialTheme.colorScheme.surfaceContainerHigh` and `MaterialTheme.colorScheme.background`).
- **Expected Impact**: Reduces garbage collection (GC) pressure and avoids redundant object creation overhead by memoizing heavy graphic objects in continuous recomposition scenarios.
