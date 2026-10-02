## 2024-10-02 - Dynamic Content Descriptions for Multi-State Toggles
**Learning:** For interactive UI elements with toggling or multiple states (e.g., play/pause, repeat modes, shuffle, sleep timer), static `contentDescription` labels (like just "循环" or "随机播放") fail to convey the current state or the expected action upon interaction. Screen reader users need to know what action will occur if they activate the control.
**Action:** Always use dynamic `contentDescription` labels that reflect the current state (e.g., "开启列表循环", "关闭随机播放") instead of static ones.
