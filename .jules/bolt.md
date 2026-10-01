## 2024-05-24 - Memoizing high-frequency string allocations in Compose UI
**Learning:** In Jetpack Compose, state that updates at a sub-second frequency (like `positionMs` for media playback) will cause continuous recompositions. Binding a string formatting function like `formatTimeMs(state.positionMs)` directly in the composition loop forces frequent string allocations and excessive garbage collection, even when the displayed text (formatted to seconds/minutes) hasn't changed.
**Action:** Isolate this high-frequency state by either breaking it down into smaller components, or use `remember` keyed by the lower-frequency unit (e.g. `state.positionMs / 1000`) to memoize the string formatting so it only recalculates when the visible output actually changes.
# 2026-09-30

- When updating track entries in a playback queue (e.g., `_queue`), iterating directly over the queue indices (`_queue.indices`) and looking up updates from a pre-computed map avoids `indexOfFirst` with `mediaId`, which causes O(N^2) performance issues and fails to update duplicate tracks in the queue.

## 2024-10-01 - Optimizing Batch API Processing
**Learning:** Processing large batches of data from API responses (like parsing up to 500 tracks in `getTrackDetailsBatch`) by linearly searching a list (`chunk.find { it.id == rid }`) for every item creates an O(N^2) bottleneck. This wastes CPU cycles when parsing huge playlists or library syncing operations.
**Action:** Always pre-compute a hash map using `associateBy` before iterating through the batch results to ensure O(1) lookups during the mapping phase.
