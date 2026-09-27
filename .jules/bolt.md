## 2026-09-27

### Avoid O(N^2) Lookups in Collections

When mapping API data to a track playback queue inside of a loop in Kotlin, avoid calling `list.indexOfFirst` or similar search functions (e.g., `_queue.indexOfFirst { it.mediaId == mediaId }`) inside of the `forEach`/`for` block, as it runs an O(N) search per iteration, resulting in O(N^2) complexity.

Instead, loop natively over the list indices (`_queue.indices`) and do an O(1) hash map lookup from an already pre-computed data map (`map[entry.mediaId]`).

This change ensures performance scalability as queue sizes increase and prevents queue update issues when duplicate tracks are present.