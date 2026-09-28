# 2026-09-28

## O(N^2) Bottleneck in Playback Queue Updates
Fixed a critical performance issue in `PlaybackControllerImpl` where track details updates were performing an O(N^2) operation.

Previously, when detailed track info was fetched in batches, the code iterated over the updated items and used `_queue.indexOfFirst { it.mediaId == mediaId }` to find the corresponding track in the queue.
- `indexOfFirst` has a time complexity of O(N) where N is the size of the queue.
- Since this lookup was performed inside a loop over the batch of M updated tracks, the overall time complexity was O(N * M), leading to severe O(N^2) stutter and CPU overhead, especially with large queues.
- This approach also had a logic flaw: it would only update the first occurrence of a track, leaving duplicate tracks in the queue without their resolved track summaries.

**Resolution:**
Refactored the loop to iterate linearly over `_queue.indices` instead, checking each item against a pre-computed map of updated tracks using O(1) hash map lookups.
- This drops the complexity to O(N).
- It natively handles duplicate tracks since every item in the queue is visited and updated if its `mediaId` exists in the update map.
