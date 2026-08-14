## 2024-08-14 - Optimize nested loops for list lookup
**Learning:** Found an O(N^2) list traversal in `UnifiedMusicSourceImpl.kt` during track details lookup from local media source. Iterating over `localIds` and doing a `find` in `list` which scales poorly.
**Action:** Always consider `.associateBy` to create an O(1) hash map when looking up multiple items against a large source list to reduce time complexity to O(N+M).
