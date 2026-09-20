## 2024-05-18 - Avoid instantiating Brush in composition phase
**Learning:** In Jetpack Compose, instantiating heavy graphics objects like `Brush` directly in the composition phase (e.g., `Brush.radialGradient`) causes significant memory allocations and GC overhead when a component recomposes frequently (e.g., in a player screen where progress causes constant recomposition).
**Action:** Wrap such graphics objects in a `remember` block to reuse them across recompositions and reduce overhead.
