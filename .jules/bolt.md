# 2026-09-27

## Avoid O(N^2) Lookup in Playback Queue Resolution
When updating track entries in a playback queue (e.g., `_queue`), iterating over the queue indices (e.g., `_queue.indices`) and looking up updates from a pre-computed map is much more efficient than iterating over the map and using `indexOfFirst` with `mediaId`. `indexOfFirst` causes O(N^2) performance issues and fails to update duplicate tracks in the queue.
