## 2024-05-24 - Localizing Accessibility Labels to Simplified Chinese
**Learning:** Hardcoded accessibility strings like `contentDescription = "Settings"` default to English but conflict with the application's primary UI language (Simplified Chinese), leading to a disjointed screen reader experience for Chinese users.
**Action:** When adding or updating `contentDescription` or other accessibility labels in the future, ensure they are translated to Simplified Chinese to match the localization of the rest of the app's UI elements (e.g., using `"设置"` instead of `"Settings"`).
