## 2024-05-14 - Initial Setup
**Learning:** Checking for performance optimizations.
**Action:** Let's look for common issues.
## 2024-05-14 - Active Lyric Binary Search Optimization
**Learning:** Functions invoked continuously by UI tick flows (like `platform.positionMs.onEach`) should avoid O(n) loops. We were using O(n) linear search over the lyric list up to 60 times a second to find the active line index.
**Action:** Replaced linear search with binary search since parsed lyric lines are already guaranteed sorted. This reduces index lookup from O(n) to O(log n), providing a measurable win for long songs or dense YRC word-level lyrics.
