# 2026-10-02

* Avoid using O(N^2) time complexity operations such as `list.none` or `list.contains` over lists when filtering or deduplicating collections within loops (like `getPersonalFmBatch`).
* Always prefer using O(1) membership checks via `Set` (e.g., `mutableSetOf()`) to track and deduplicate IDs and improve performance.
# 2026-10-02

* Avoid using O(N^2) time complexity operations such as `list.none` or `list.contains` over lists when filtering or deduplicating collections within loops (like `getPersonalFmBatch`).
* Always prefer using O(1) membership checks via `Set` (e.g., `mutableSetOf()`) to track and deduplicate IDs and improve performance.
