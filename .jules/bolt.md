# 2023-10-24 Bolt: Performance Optimizations
## Issue: Recomposition overhead in DesktopPlayerScreen
In `DesktopPlayerScreen`, `Slider` and `Brush` are evaluated directly in the function body which means they are recomputed and reallocated continuously when `state.positionMs` updates.
Also `Slider` continuously triggers `onSeek` as the value changes.

* Fix: Extract `Brush` into a `remember` block.
* Fix: Add `seekValue` local state to memoize slider drag value and only send `onSeek` on `onValueChangeFinished`.
