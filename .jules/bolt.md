# 2025-01-20

## O(N^2) Lookup Optimization in Playback Queue

**Issue:** In `PlaybackControllerImpl.kt`, the `resolveQueueInBackground` function had a performance bottleneck when attempting to update track metadata. It iterated through an unordered map of results and, for every entry, used `indexOfFirst { it.mediaId == mediaId }` on the entire playback queue array to find the corresponding track item. This created O(N^2) time complexity, causing performance degradation when large tracks collections were parsed into the queue.

**Solution:** The logic was reversed to iterate directly over the queue elements using `_queue.indices`. Within this single O(N) loop, it immediately looks up the corresponding updated metadata from the pre-computed track map (using `val summary = map[entry.mediaId] ?: continue`). This reduces complexity from O(N^2) to O(N) for queue track resolution, ensuring responsiveness regardless of queue size.
