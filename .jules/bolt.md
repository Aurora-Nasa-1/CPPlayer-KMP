# 2024-09-26

## PlaybackControllerImpl.kt Queue Resolution Bug & Performance Bottleneck

*   **Problem:** In `resolveQueueInBackground`, there was an O(N^2) operation because `indexOfFirst` was being called inside a loop over fetched track summary results. Additionally, `indexOfFirst` only finds the *first* occurrence of a track. If the user added the same track multiple times, subsequent occurrences remained unparsed. Also, `indexOfFirst` is prone to `IndexOutOfBoundsException` if items are removed concurrently.
*   **Solution:** Removed the `indexOfFirst` loop entirely. Instead, iterate directly through `_queue.indices` in O(N) time. Check if the current entry lacks a summary (`entry.summary == null`), and map its summary directly using `map[entry.mediaId]`. This ensures all duplicate tracks get parsed, avoids O(N^2), and uses `getOrNull` for bounds safety.

## UnifiedMusicSourceImpl.kt GetTrackDetails Performance Bottleneck

*   **Problem:** In `getTrackDetails`, when resolving track API chunks back to `mediaId` requests, the loop iterates over API response `songs` and uses `chunk.find { it.resourceId == rid }` to match the track. This creates an O(N^2) inner loop overhead.
*   **Solution:** Used `associateBy { it.resourceId }` on `chunk` before processing the inner loop to build a hash map lookup (`apiIdMap`), turning an O(N^2) operation into O(N).
