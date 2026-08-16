## 2024-08-14 - Optimize nested loops for list lookup
**Learning:** Found an O(N^2) list traversal in `UnifiedMusicSourceImpl.kt` during track details lookup from local media source. Iterating over `localIds` and doing a `find` in `list` which scales poorly.
**Action:** Always consider `.associateBy` to create an O(1) hash map when looking up multiple items against a large source list to reduce time complexity to O(N+M).
## 2024-05-14 - Initial Setup
**Learning:** Checking for performance optimizations.
**Action:** Let's look for common issues.
## 2024-05-14 - Active Lyric Binary Search Optimization
**Learning:** Functions invoked continuously by UI tick flows (like `platform.positionMs.onEach`) should avoid O(n) loops. We were using O(n) linear search over the lyric list up to 60 times a second to find the active line index.
**Action:** Replaced linear search with binary search since parsed lyric lines are already guaranteed sorted. This reduces index lookup from O(n) to O(log n), providing a measurable win for long songs or dense YRC word-level lyrics.
## 2024-05-14 - Compose High-Frequency Recomposition Optimizations
**Learning:** Continuous state emissions like playback `positionMs` cause expensive garbage collection when string formatting (like `formatTimeMs`) is performed directly in the view on every frame.
**Action:** Use `remember` with a truncated key (like `positionMs / 1000`) to memoize strings that only visibly change once per second. This stops redundant string allocations on high-frequency Compose updates.
