
### 2025-05-15
- **UX/Accessibility**: Purely decorative icons or icons mirroring adjacent text MUST have `contentDescription = null` to avoid redundant screen reader announcements. For example, in `PlaylistDetailScreen`, the action buttons for "播放" (Play), "随机" (Shuffle), "添加" (Add), "排序" (Sort), and "全部下载" (Download All) have both an icon and a text label adjacent to each other. By setting the `contentDescription` of the icon to `null`, we ensure screen readers only announce the text once, providing a clearer experience.
