
## 2026-08-31 - Localize Hardcoded Accessibility Strings
**Learning:** Found hardcoded English strings ("Back", "Settings") used for `contentDescription` in core shared components (`AppScaffold.kt`, `MainScreen.kt`), whereas the rest of the application uses Simplified Chinese. This creates an inconsistent screen reader experience.
**Action:** When working on UI and accessibility in this app, ensure all new and existing `contentDescription` labels match the primary localized language (Simplified Chinese) rather than defaulting to English.
