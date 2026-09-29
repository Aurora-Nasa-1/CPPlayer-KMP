## 2026-09-29 - O(N^2) Queue Lookup Bottleneck
**Learning:** Using `indexOfFirst` to match items in a playback queue inside a resolution loop causes an O(N^2) bottleneck and fails to update duplicate items. Iterating over the queue directly and performing an O(1) hash map lookup fixes both issues.
**Action:** Avoid `indexOfFirst` within batch processing loops in Kotlin, especially for playback queues with potentially duplicate entries; iterate over the queue indices and use pre-computed maps.
