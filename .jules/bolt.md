
## 2024-05-24 - Dynamic getters in Compose data classes cause performance issues
**Learning:** Using dynamic getters (`val prop get() = list.filter { ... }`) in Compose data classes (like `UiState`) causes the filtering logic (O(N) operations) to run on every access during UI recomposition. This leads to excessive CPU overhead and unnecessary object allocations/garbage collection.
**Action:** Always use eagerly evaluated properties (`val prop = list.filter { ... }`) in data classes that represent UI state so the computation is performed exactly once when the state is created/copied.
