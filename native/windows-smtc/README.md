# Windows SMTC bridge

This directory reserves the native Windows SystemMediaTransportControls bridge.

The desktop Kotlin adapter must load the helper only when `os.name` is Windows.
The helper should expose a narrow C ABI:

- `cp_smtc_start(callbacks)`
- `cp_smtc_update(title, artist, album, duration_ms, position_ms, playing)`
- `cp_smtc_stop()`

The implementation belongs in C++/WinRT because SMTC is a WinRT API. The Kotlin
adapter now looks for `cp_windows_smtc.dll` in the working directory, beside the
packaged launcher, or under a custom `-Dcp.player.smtc.dir=...` override before
enabling SMTC. Until the helper DLL is built and packaged, the adapter must fail
closed and retain normal in-app playback.
