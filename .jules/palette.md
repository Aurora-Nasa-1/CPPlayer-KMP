## 2024-10-24 - Dynamic Accessibility Labels for State-Driven Icons
**Learning:** For toggle buttons (like Play/Pause), a static description (e.g. "播放/暂停") does not provide sufficient context to a screen reader about the _current_ action.
**Action:** Use dynamic descriptions (e.g., `if (isPlaying) "暂停" else "播放"`) mirroring the conditional icon to accurately announce the subsequent action to screen readers.
