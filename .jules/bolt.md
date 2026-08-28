## 2024-11-20 - UnifiedMusicSourceImpl batch lookups
**Learning:** Replaced an O(N^2) linear search within a batch processing loop (up to chunk sizes of 500) with an O(N) hash map lookup (`associateBy`). This prevents substantial CPU overhead during large playlist fetching operations.
**Action:** When mapping over chunks or batches, always check if inner loop ID matching can be converted to an `associateBy` map first to prevent O(N^2) bottlenecks.
