# 2026-09-23

- **What**: Memoized formatted position and duration strings in `ProgressRow` of `PlayerScreen.kt` using `remember` block keyed to seconds rather than milliseconds. Also removed unnecessary string interpolation for `info.qualityLabel`.
- **Why**: `positionMs` is a fast-changing state that updates continuously during playback, causing rapid recompositions. Eagerly executing string building/formatting on every recomposition causes unnecessary object allocation and garbage collection (GC) overhead.
- **Impact**: Reduced CPU usage and GC pressure during continuous playback progress updates by memoizing string building only when the second value changes.
- **Measurement**: Visual smoothness improved during playback; lower profiler GC allocations over a duration tracking session.
