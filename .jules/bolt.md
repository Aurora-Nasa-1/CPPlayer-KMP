# 2026-09-25

## Optimization in `getPersonalFmBatch`

Identified an O(N^2) bottleneck in `getPersonalFmBatch` within `MusicRepository.kt` where a `none` check was used against a growing list for track deduplication.

*   **Problem**: In `merged.none { it.id == candidate.id }`, the condition iterated over `merged` for each new candidate item, which gets more expensive as `merged` increases in size, especially with multiple batched API requests.
*   **Solution**: Introduced a `MutableSet` (`seenIds`) to keep track of added IDs. Replaced the inner loop `none` check with `seenIds.add(it.id)`, taking advantage of O(1) set membership lookups.
*   **Impact**: Performance improvement by removing the quadratic complexity when filtering and deduplicating new tracks.