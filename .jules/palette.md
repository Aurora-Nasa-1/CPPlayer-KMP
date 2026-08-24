## 2024-08-24 - Dynamic Content Descriptions for Stateful Buttons
**Learning:** For interactive UI elements with toggling or multiple states (e.g., play/pause), using static accessibility labels like "Play/Pause" forces screen reader users to guess the current state.
**Action:** Always use dynamic `contentDescription` labels that reflect the actual action that will occur (e.g., `if (isPlaying) "暂停" else "播放"`) to accurately convey the expected interaction, and remember to match the localization of the application.
