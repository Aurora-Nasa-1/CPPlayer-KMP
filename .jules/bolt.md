# 2026-09-24

## Performance Optimizations
* **Optimized `getPersonalFmBatch` in `MusicRepository`**: Removed an `O(N^2)` deduplication bottleneck caused by using `merged.none { it.id == candidate.id }` within a batch fetch loop. Replaced this with an `O(1)` membership check using a `seenIds: MutableSet<String>`, effectively bringing the time complexity to `O(N)` for filtering newly fetched tracks. This ensures smoother performance, especially when batch sizes grow.
