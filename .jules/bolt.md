# 2024-11-20

* Replaced `O(N^2)` lookup with an `O(1)` hash map inside the `UnifiedMusicSourceImpl.kt` batch chunk loop. This should avoid major performance problems when `N` is large.
* Replaced `O(N^2)` deduplication with a `Set` for `O(1)` membership checks in `MusicRepository.kt`'s `getPersonalFmBatch`. This is a better practice that avoids significant CPU cost.
