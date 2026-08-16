## 2023-10-27 - Centralized Image Resizing
**Learning:** Found existing `.resized(size)` extension function natively built in for NetEase CDN resizing in `app/src/commonMain/kotlin/cp/player/app/ui/util/CoverUrls.kt` that was heavily underutilized in various list views (e.g. `MiniPlayer`, `QueueBottomSheet`, `HomeScreen`).
**Action:** When working on memory and network optimization for lists, prioritize looking for existing utility functions in `ui/util` packages before manually modifying image logic.
## 2024-08-14 - Optimize nested loops for list lookup
**Learning:** Found an O(N^2) list traversal in `UnifiedMusicSourceImpl.kt` during track details lookup from local media source. Iterating over `localIds` and doing a `find` in `list` which scales poorly.
**Action:** Always consider `.associateBy` to create an O(1) hash map when looking up multiple items against a large source list to reduce time complexity to O(N+M).
## 2024-05-14 - Initial Setup
**Learning:** Checking for performance optimizations.
**Action:** Let's look for common issues.
## 2024-05-14 - Active Lyric Binary Search Optimization
**Learning:** Functions invoked continuously by UI tick flows (like `platform.positionMs.onEach`) should avoid O(n) loops. We were using O(n) linear search over the lyric list up to 60 times a second to find the active line index.
**Action:** Replaced linear search with binary search since parsed lyric lines are already guaranteed sorted. This reduces index lookup from O(n) to O(log n), providing a measurable win for long songs or dense YRC word-level lyrics.
