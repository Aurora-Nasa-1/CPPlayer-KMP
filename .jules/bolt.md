## 2025-02-13 - Format Time Optimization
**Learning:** Found an opportunity to format time using cached formatted values, but since it's just a simple string formatting logic, we can also look for memoization in compose components like `SongItem` or others to prevent unnecessary re-renders.
**Action:** Let's find a component that might be causing unnecessary re-renders or an expensive calculation that can be memoized using `remember`.
## 2025-02-13 - State Flow Collection Optimization
**Learning:** In `PlayerScreenContent`, `state.positionMs` is collected from a flow every millisecond causing rapid re-compositions of the entire large component. We should look at isolating fast-changing state like `positionMs` into a smaller component.
**Action:** Let's look for how to isolate `positionMs` in `PlayerScreen.kt`.
## 2025-02-13 - Format Time string memoization
**Learning:** Found `formatTimeMs` usage in `PlayerScreen.kt` and `PlaylistDetailScreen.kt`. Every composition invokes `formatTimeMs`, calculating division and modulo in `PlaylistDetailScreen.kt`.
However, for `PlayerScreen.kt`, the whole player UI is being updated on every position tick, which causes huge CPU overhead! I should fix this layout rendering logic by passing position state locally or using distinct `State` object for current position.
**Action:** The state flow of playback emits `positionMs` to update `PlayerScreenContent`. I can change this. But it might be too large of an architecture change since `PlaybackUiState` contains it. I should look for a simpler micro-optimization inside `PlaybackUiState` rendering.
## 2025-02-13 - Format Time string memoization
**Learning:** Currently, in `ProgressRow` inside `PlayerScreen.kt`, `formatTimeMs` is invoked on every single re-composition of `ProgressRow`. Because `positionMs` changes every millisecond, `formatTimeMs` creates new strings `$m:$s` extremely rapidly, which causes continuous memory allocation and garbage collection overhead. Since `formatTimeMs` truncates to seconds (`val totalSec = ms / 1000`), we only need to recompute the formatted string when the second changes!
**Action:** We can optimize `formatTimeMs(state.positionMs)` by using `remember(state.positionMs / 1000) { formatTimeMs(state.positionMs) }`. This simple change will drop the re-evaluations from 1000x per second to 1x per second, significantly reducing string allocations and easing GC pressure without changing architecture.
