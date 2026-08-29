## 2024-10-24 - Slider Performance in Jetpack Compose
**Learning:** Binding a Jetpack Compose `Slider`'s `onValueChange` directly to a media engine's `onSeek` action causes severe UI stutter and CPU overhead, because the engine seek command is continuously triggered on every drag coordinate update.
**Action:** Use a local state (e.g., `seekValue`) to memoize the slider's value during the drag in `onValueChange`, and only trigger the actual engine seek action in `onValueChangeFinished`.
