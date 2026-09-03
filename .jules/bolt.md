# 2024-03-24
- In Jetpack Compose, the `ProgressRow` component for media playback experiences continuous recompositions due to the fast-changing `state.positionMs`.
- To avoid high CPU overhead and excessive garbage collection from continuous string allocations, memoize derived fast-changing state variables with `remember`.
- Specifically, truncate timestamps to seconds (`state.positionMs / 1000`) and use it as a key in a `remember` block to avoid re-evaluating `formatTimeMs()` every frame.
- Also, avoid evaluating expensive string operations and property getters (like `buildList` and `joinToString`) on every frame by wrapping them in `remember` blocks. Remove redundant string interpolations (e.g. `"${info.qualityLabel}"`) within frequently recomposed UI elements.