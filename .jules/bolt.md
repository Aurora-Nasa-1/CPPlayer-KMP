## 2024-05-14 - Compose Local State for Frequent Updates
**Learning:** In Jetpack Compose, when implementing progress `Slider` components for media playback, use a local state (e.g., `seekValue`) to handle `onValueChange` updates locally, and only trigger the actual engine seek action on `onValueChangeFinished`. Binding the seek action directly to `onValueChange` causes continuous engine calls resulting in severe UI and playback stutter.
**Action:** Verify if progress sliders in desktop use `onValueChangeFinished` and memoize timestamp string formatting.
