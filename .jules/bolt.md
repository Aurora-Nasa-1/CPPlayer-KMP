# 2024-05-24

## Memoize Fast-Changing Formatted Playback Times
**What:** Wrapped `formatTimeMs()` calls in `remember` blocks within `PlayerScreen` and `DesktopPlayerScreen`.
**Why:** The continuous progression of playback (position updates) causes frequent recompositions of the progress row. `formatTimeMs()` was allocating new String objects (using string interpolation inside formatTimeMs) on every recomposition even if the time string (by second) hasn't changed.
**Impact:** Reduced string allocation and garbage collection overhead during playback.
**Measurement:** String objects created from time formatting during playback is reduced from dozens of objects per second down to 1 object per second.
