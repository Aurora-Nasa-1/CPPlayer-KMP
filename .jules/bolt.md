# 2026-10-06

## O(N) Lookups in Jetpack Compose
Replaced `indexOfFirst` lookup inside `AlbumDetailScreen.kt` when executing `onPlay` via `optionsTarget` contextual menu by capturing and storing the index along with the object `Pair<Int, TrackSummary>` in the state itself. This prevents O(N) complexity during heavy interactive UI workflows.
