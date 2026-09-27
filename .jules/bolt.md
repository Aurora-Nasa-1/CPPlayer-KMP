## 2024-09-27 - O(N^2) Bottleneck in Playback Queue Updates
**Learning:** Updating playback queue entries by iterating over a map of resolved summaries and using `indexOfFirst` for each `mediaId` results in O(N^2) complexity and fails to update duplicate queue entries.
**Action:** Iterate directly over the queue indices (`_queue.indices`) and perform an O(1) lookup against the pre-computed map (`map[entry.mediaId]`). This correctly handles duplicates and runs in O(N) time.
