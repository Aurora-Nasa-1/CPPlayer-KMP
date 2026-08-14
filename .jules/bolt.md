
## 2024-05-24 - Sequence Lazy Evaluation
**Learning:** For large arrays, using eager evaluation like `.mapNotNull {...}` can create intermediate lists that take time and memory. `.asSequence()` allows short circuiting like `.take(64)` to avoid evaluating all the elements in a large list.
**Action:** Use `.asSequence()` in collection chains when filtering or mapping large inputs if an early termination condition like `.take()` is used in the chain.
