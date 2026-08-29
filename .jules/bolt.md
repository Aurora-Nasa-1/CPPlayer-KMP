# 2024-08-29
- Memoize continuous text formats (`formatTimeMs`) during UI playback emissions to avoid GC stuttering in Compose Multiplatform.
- Avoid string interpolation on constant values (e.g. `"${info.qualityLabel}"`) where direct reference is simpler and allocates less.
