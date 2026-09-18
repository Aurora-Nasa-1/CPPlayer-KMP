# 2025-05-24

## Bolt: Optimize inner loop lookup in UnifiedMusicSourceImpl
- **Bottleneck**: `UnifiedMusicSourceImpl.kt` contained a nested loop in its track details fetching logic (`chunk.find { it.resourceId == rid }` inside a `.forEach`). This resulted in O(N^2) time complexity for looking up item IDs when processing bulk operations (up to 500 items per chunk).
- **Optimization**: Replaced the O(N) list search with an O(1) pre-computed hash map using `associateBy { it.resourceId }`. This reduces the lookup time complexity from O(N^2) to O(N), improving performance during large API batch responses.
