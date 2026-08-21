## 2026-08-21 - Dynamic Accessible Labels for Toggle Buttons
**Learning:** For interactive UI elements with toggling or multiple states (e.g., play/pause), using static accessibility descriptions (like "Play/Pause") is ambiguous. Furthermore, defaulting to English descriptions in a primarily Simplified Chinese app causes localization issues for screen readers.
**Action:** Use dynamic `contentDescription` labels (e.g., `if (isPlaying) "暂停" else "播放"`) that reflect the current state to convey the expected action properly, and ensure they match the primary localization language.
