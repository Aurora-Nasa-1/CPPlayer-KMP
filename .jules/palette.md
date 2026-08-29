# 2025-01-24

## Dynamic Content Description Accessibility
- **What:** Updated static accessibility labels for the play/pause, shuffle, repeat, and like buttons in `DesktopPlayerScreen` to use dynamic translations (e.g., `if (state.isPlaying) "暂停" else "播放"`).
- **Why:** The labels for multi-state toggle buttons used to remain the same regardless of current state. By providing dynamic labels, screen reader users can now understand the current state and the action they are about to take, making the interface more accessible and predictable.
- **Accessibility:** Improves screen reader UX by providing contextual action hints in Simplified Chinese (the primary UI language).
