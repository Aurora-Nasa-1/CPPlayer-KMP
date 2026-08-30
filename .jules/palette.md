## 2024-05-24 - Localizing Content Descriptions for Icons
**Learning:** Found an `IconButton` used for Settings where the icon's `contentDescription` was hardcoded to English ("Settings"). Since the primary UI language of this app is Simplified Chinese, this fallback causes an inconsistent screen reader experience.
**Action:** Replaced `contentDescription = "Settings"` with the localized `contentDescription = "设置"`. Future UI components should ensure non-decorative interactive icons have meaningful, localized `contentDescription`s.
