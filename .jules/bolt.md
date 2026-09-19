# 2024-10-27

## ProgressRow UI State Memoization Optimization
* **What**: Optimized `ProgressRow` in Jetpack Compose UI (used in `PlayerScreen.kt`) by extracting and memoizing string formatting functions `formatTimeMs()` during continuous UI progress updates. Replaced redundant string interpolation (`"${info.qualityLabel}"`) with direct property reference.
* **Why**: The `positionMs` state changes continuously during media playback (often every 16-50ms). Running `formatTimeMs()` directly inside the `Text` composable results in rapid, continuous string allocation. This creates excessive garbage collection (GC) pressure and causes CPU overhead which might lead to jitter. Also, string interpolation inside `Text` when not necessary just creates more overhead.
* **Impact**: GC pressure during active track playback will be greatly reduced because `positionStr` is now only recalculated when the truncated integer seconds change (once a second, rather than potentially up to 60 times a second), and `durationStr` is calculated once per track.
* **Measurement**: Visual profiling should show less frequent short-lived GC collections during playback in Android Studio Profiler (Memory). `positionMs` layout node updates remain smooth but textual re-evaluation is minimized.
