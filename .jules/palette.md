## 2024-05-18 - Compose Accessibility Defaults
**Learning:** Found that some default Composable `Icon` implementations when nested inside `IconButton` were missing `contentDescription`, breaking accessibility for screen readers. Default localized text should match the app's primary language (Chinese).
**Action:** Always check `Icon` components nested in interactable containers (`IconButton`, `Surface(onClick=)`) and add appropriate dynamic labels reflecting the action state (e.g., "点赞" / "取消点赞").
