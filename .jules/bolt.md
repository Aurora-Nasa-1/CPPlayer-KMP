# 2026-09-30

- When updating track entries in a playback queue (e.g., `_queue`), iterating directly over the queue indices (`_queue.indices`) and looking up updates from a pre-computed map avoids `indexOfFirst` with `mediaId`, which causes O(N^2) performance issues and fails to update duplicate tracks in the queue.
