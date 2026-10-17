## 2024-11-20 - Localizing Hardcoded Accessibility Attributes
**Learning:** Found hardcoded English strings ("Prev", "Next") used as `contentDescription` for `IconButton` elements in `MiniPlayer.kt`, which breaks the accessibility experience for non-English users (this app primarily uses Simplified Chinese).
**Action:** Always inject `cpStrings()` (e.g., `s.player.previousTrack`) to map accessibility labels properly to the user's localized environment instead of directly embedding raw strings.
