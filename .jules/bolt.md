## 2026-08-23 - Derived Properties in UI State Data Classes
**Learning:** Found multiple data classes like `DownloadsUiState` where derived lists (e.g., `activeTasks`, `completedTasks`) use dynamic getters (`get() = tasks.filter { ... }`). This is an O(N) operation running on every property access, which is especially bad in Compose UI where recomposition accesses properties frequently.
**Action:** Convert these dynamic getters to eagerly evaluated `val` properties so the filtering is only done once when the state object is created.
