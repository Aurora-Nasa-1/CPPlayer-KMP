# 2024-10-24

- **Optimization**: Avoided O(N^2) lookups within `resolveQueueInBackground` in `PlaybackControllerImpl.kt`.
- **Details**: When updating track entries in the playback queue with lazy-loaded summary details, the previous implementation iterated over a map and used `_queue.indexOfFirst { it.mediaId == mediaId }` to locate the entry. This resulted in O(N^2) time complexity. Furthermore, it failed to correctly update multiple occurrences of the same track in the queue, as it always updated the first matching index.
- **Solution**: The logic was rewritten to iterate directly over the queue indices (`_queue.indices`) in a single O(N) pass, looking up each track's summary detail from a pre-computed map in O(1) time. This ensures all duplicate entries are properly updated and improves overall performance.
