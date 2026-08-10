## 2024-06-25 - Fingerprinter Collection Eagerness Optimization
**Learning:** Found sequence optimization on JSON processing collection chaining, where applying `asSequence()` greatly improved speed of executing chained commands like `filter()`, `map()`, and specifically `take(64)` where lazy eval meant items above 64 were not evaluated at all.
**Action:** Be on the lookout for eager chained list evaluations with terminal short-circuits like `take()` or `first()` in KMP projects, and utilize sequence processing instead.
