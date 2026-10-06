## 2024-05-24 - Memoizing high-frequency string allocations in Compose UI
**Learning:** In Jetpack Compose, state that updates at a sub-second frequency (like `positionMs` for media playback) will cause continuous recompositions. Binding a string formatting function like `formatTimeMs(state.positionMs)` directly in the composition loop forces frequent string allocations and excessive garbage collection, even when the displayed text (formatted to seconds/minutes) hasn't changed.
**Action:** Isolate this high-frequency state by either breaking it down into smaller components, or use `remember` keyed by the lower-frequency unit (e.g. `state.positionMs / 1000`) to memoize the string formatting so it only recalculates when the visible output actually changes.
# 2026-09-30

- When updating track entries in a playback queue (e.g., `_queue`), iterating directly over the queue indices (`_queue.indices`) and looking up updates from a pre-computed map avoids `indexOfFirst` with `mediaId`, which causes O(N^2) performance issues and fails to update duplicate tracks in the queue.
# 2026-10-01

- **Bottleneck**: In `UnifiedMusicSourceImpl.kt`, `getTrackDetails` uses `apiIds.chunked(500)` to fetch details in batches. Inside the loop processing the response, it iterates over `songs?.forEach` and for each song, it used `chunk.find { it.resourceId == rid }`. Since `chunk` can be up to 500 items, and `songs` can be up to 500, this resulted in an O(N^2) lookup within the loop.
- **Optimization**: Converted the `chunk` list to a hash map using `val chunkMap = chunk.associateBy { it.resourceId }` prior to the `songs?.forEach` loop. The inner lookup was changed to `val matchedApiId = chunkMap[rid]`, changing the time complexity from O(N^2) to O(N) for that batch processing step.
## 2024-12-08 - O(N) Lookups in Jetpack Compose UI Callbacks
**Learning:** Performing `indexOfFirst` or `indexOf` inside contextual menu or options sheet callbacks (like `onPlay` or `onOptionsClick`) causes O(N) performance penalties on large lists when the index can simply be memoized upfront.
**Action:** Always capture and store both the item object and its pre-calculated index (e.g., `Pair<Int, Object>`) in the `MutableState` state variable used to open the menu/dialog. This eliminates the need to scan the list when the callback action is ultimately executed.
