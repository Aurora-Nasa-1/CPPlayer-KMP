## 2026-09-26 - Optimize O(N^2) lookups to O(N) by using hash map
**Learning:** When performing repeated lookups in loops, particularly in data class filtering and unified queries, using `.find` can cause O(N^2) bottlenecks. Pre-computing a hash map using `associateBy` significantly improves performance.
**Action:** Optimize lookups in batch processing and unified sources to O(N) using `associateBy` where `.find` is inside a loop, taking care to extract invariant structures when applicable to avoid redundant allocations.
