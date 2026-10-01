## 2025-01-20 - Dynamic Accessibility Labels for Toggle Buttons
**Learning:** For interactive UI elements with multiple states (like shuffle or repeat mode buttons), using static `contentDescription` (e.g., just "循环") fails to communicate the current state or the resulting action to screen reader users. The label should dynamically reflect the action or state (e.g., "开启单曲循环" vs "关闭循环").
**Action:** When updating or creating multi-state toggle buttons (like `CpModeToggle`), always use dynamic accessibility labels (`contentDescription` or `label`) that depend on the current state.
