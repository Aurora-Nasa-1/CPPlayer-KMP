## 2025-01-20 - Use Correct Localization for Accessibility Strings

**Learning:** Hardcoding English accessibility strings (e.g., `contentDescription = "Back"`) in a project primarily localized for Simplified Chinese creates an inconsistent and confusing experience for screen reader users.

**Action:** Always ensure that `contentDescription` and other accessibility labels use the correct localized strings (e.g., "返回") that match the primary language of the application's UI, rather than defaulting to English.
