# 2026-09-26

Optimized $O(N^2)$ lookup to $O(N)$ when parsing batch track details in `UnifiedMusicSourceImpl.kt`. Replaced `chunk.find { it.resourceId == rid }` inside the loop of up to 500 API-returned songs with an `associateBy { it.resourceId }` map created prior to the loop. This minimizes CPU overhead for matching parsed track objects back to their API resource identifiers during batch processing.
