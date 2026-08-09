## 2024-10-27 - [Playback Lyric Index Lookup Optimization]
**Learning:** `computeLyricIndex` in `PlaybackControllerImpl.kt` used a linear search `O(N)` for a list of lyrics lines that are already sorted by `time`. For long songs, this can be slightly heavy to run on every position update from the player.
**Action:** Implemented a binary search algorithm `O(log N)` replacing the `O(N)` loop to fetch the active lyric index faster.
