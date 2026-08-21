
## 2024-05-19 - Eager Evaluation of Derived Lists in Compose State Objects
**Learning:** In Compose Multiplatform architecture using `data class` for UI state (e.g., `DownloadsUiState`), derived properties that involve collection filtering (`list.filter { ... }`) shouldn't use dynamic getters (`get() = ...`). Because UI state objects are accessed frequently during recomposition, dynamic getters cause O(N) operations and new list allocations on *every read*, causing high CPU overhead and GC pressure.
**Action:** Use eager property initialization (`val filteredList = baseList.filter { ... }`) inside the `data class` body. Since the state object is immutable and updated via `.copy()`, this ensures the filtering is only performed once at the time of creation.
