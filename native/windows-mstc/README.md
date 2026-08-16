# Windows MSTC bridge

This directory reserves the native Windows SystemMediaTransportControls bridge.

The desktop Kotlin adapter must load the helper only when `os.name` is Windows.
The helper should expose a narrow C ABI:

- `cp_mstc_start(callbacks)`
- `cp_mstc_update(title, artist, album, duration_ms, position_ms, playing)`
- `cp_mstc_stop()`

The implementation belongs in C++/WinRT because MSTC is a WinRT API. The MSI
packaging task should place the architecture-specific DLL next to the desktop
launcher. Until the helper is built and packaged, the Kotlin desktop adapter
must fail closed and retain normal in-app playback.
