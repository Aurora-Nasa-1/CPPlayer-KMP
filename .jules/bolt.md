## 2025-02-26 - Memoizing Graphic Objects in Compose
**Learning:** Fast-changing state (like `positionMs` in media players) can cause continuous recomposition of screens. Heavy graphic objects, such as `Brush`, instantiated directly in the composition phase can lead to significant memory allocation and Garbage Collection (GC) overhead.
**Action:** Wrap heavy objects like `Brush` with a `remember` block keyed to their specific dependencies (e.g., `MaterialTheme.colorScheme`) to memoize the instance and avoid unnecessary allocations during rapid state updates.
