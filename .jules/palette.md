## 2024-05-24 - Dynamic localized descriptions for Player control states
**Learning:** Screen readers often announce toggled player buttons (Play/Pause, Repeat) with generic descriptions like "Play" or "Repeat" even when the state represents the opposite ("Pause" or "Repeat One"). Also, ensure UI copy matches the app's localized string pool (Simplified Chinese).
**Action:** Replace static descriptions with dynamically evaluated strings based on current UI state, specifically matching the localized terminology of the app (e.g. `if (isPlaying) "暂停" else "播放"`).
