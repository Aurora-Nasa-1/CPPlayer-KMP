## 2023-10-27 - Centralized Image Resizing
**Learning:** Found existing `.resized(size)` extension function natively built in for NetEase CDN resizing in `app/src/commonMain/kotlin/cp/player/app/ui/util/CoverUrls.kt` that was heavily underutilized in various list views (e.g. `MiniPlayer`, `QueueBottomSheet`, `HomeScreen`).
**Action:** When working on memory and network optimization for lists, prioritize looking for existing utility functions in `ui/util` packages before manually modifying image logic.
