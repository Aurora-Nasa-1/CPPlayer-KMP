## 2024-05-24 - Initial \n **Learning:** Created journal. \n **Action:** Use it.
## 2026-08-19 - Dynamic Accessibility Labels
**Learning:** Interactive components that change state (e.g. repeat mode, shuffle toggle, play/pause) need dynamic `contentDescription` strings reflecting their *current* action or state to be properly accessible to screen readers, instead of static descriptions.
**Action:** Always ensure that toggle buttons and multi-state icons in Compose UI provide dynamic `contentDescription` values based on the component's state.
