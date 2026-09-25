# Windows SMTC bridge

**Status: superseded — no native code is planned here.**

This directory is kept only as a record of a decision. It contains no code, is not
referenced by any build script, and nothing loads it at runtime.

## What actually ships

Windows System Media Transport Controls are **already implemented**, via **JMTC**
(Java Media Transport Controls) in the desktop app:

- Adapter: `app/src/desktopMain/kotlin/cp/player/app/platform/JmtcMediaControls.desktop.kt`
- It extracts JMTC's bundled `SMTCAdapter.dll` (resource `/win32-x86-64/` or
  `/win32-x86/`) from the JMTC JAR into a temp directory at startup, points
  `jna.library.path` at it, and calls in through JNA.
- The same JMTC dependency also provides MPRIS on Linux.
- Fail-closed: if the native resource is missing, the adapter logs the failure and
  continues — normal in-app playback is unaffected.

## The abandoned approach

An earlier plan was to hand-roll a C++/WinRT helper exposing a narrow C ABI as
`cp_windows_smtc.dll`, discovered via the working directory, the packaged launcher,
or a `-Dcp.player.smtc.dir=...` override:

- `cp_smtc_start(callbacks)`
- `cp_smtc_update(title, artist, album, duration_ms, position_ms, playing)`
- `cp_smtc_stop()`

**That plan was dropped in favour of JMTC.** None of those symbols, that DLL name,
or that system property exist anywhere in the codebase — verified by grep across
the whole repository. JMTC already ships a working WinRT adapter, so the hand-rolled
bridge would have been duplicated effort plus a second thing to package and sign.

Do not re-implement it without first confirming JMTC is insufficient.

## Deleting this directory

Nothing breaks — it is an empty placeholder plus this note. The only thing lost is
the record of why the hand-rolled bridge was never built.
