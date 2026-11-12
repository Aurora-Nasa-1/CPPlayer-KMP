# Windows Native Image PGO

The configured profile is `default.iprof`. Generate it on Windows with Oracle GraalVM 25.3.4.1 by running:

```powershell
./gradlew :app:runWithPgoInstrument
```

Use the instrumented app through representative workflows (startup, library browsing/search, normal playback, pause/seek, and shutdown), then exit normally so the profile is written. The Windows Native Image packaging task consumes this profile when it exists. A profile generated only from a cold start or from a different operating system is not a substitute for representative Windows usage.

Until `default.iprof` exists, Oracle GraalVM's ML Profile Inference is used; when the PGO profile is applied, Native Image automatically disables ML inference. GraalVM 25.3.4.1 also enables loop-vectorization by default for eligible array-arithmetic loops.

No trained profile is included yet; this environment has no Windows runner, so a real Windows workload still needs to be recorded.
