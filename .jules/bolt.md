# 2023-10-25

## ⚡ Bolt: Optimize API batch lookup in UnifiedMusicSourceImpl

**What:**
Optimized `getTrackDetails` in `UnifiedMusicSourceImpl.kt` by replacing an O(N^2) list search with an O(N) hash map lookup for mapping batch API responses back to input IDs.

**Why:**
The original implementation processed API track IDs in batches of up to 500 items. Inside the loop over the returned `songs` (which can also contain up to 500 items), it used `chunk.find { it.resourceId == rid }` to find the matching requested ID. This O(N) lookup inside an O(N) loop creates an O(N^2) operation, resulting in potentially 250,000 comparisons per batch.

By pre-computing a hash map before the loop using `chunk.associateBy { it.resourceId }`, the inner lookup becomes an O(1) operation (`chunkMap[rid]`).

**Impact:**
Time complexity for matching batch track IDs is reduced from O(N^2) to O(N). This reduces CPU load and shortens execution time, providing a smoother experience and lower power consumption, especially when processing large playlists or retrieving metadata for multiple tracks.

**Measurement:**
The difference should be noticeable in micro-benchmarks or when fetching metadata for long lists (e.g., thousands of items processed in 500-item chunks), where the loop iteration overhead drops substantially.
