# Windows Native Image PGO

The configured profile is `default.iprof`.

### Recommended: Cloud Build via GitHub Actions
Run the `.github/workflows/desktop-pgo-instrument.yml` workflow on GitHub Actions. It compiles the instrumented Windows Native Image binary using Oracle GraalVM and publishes `CPPlayer-win-x64-pgo-instrument.zip` as an Artifact (and optionally as a GitHub Release).
1. Download and unzip the archive on your Windows machine.
2. Run `CPPlayer.exe` through representative workflows (playing music, library browsing, search, dynamic lyrics, pause/seek, minimizing to tray, and clean exit).
3. Upon exit, GraalVM automatically generates `default.iprof` next to `CPPlayer.exe`.
4. Copy `default.iprof` into this directory (`app/graalvm/pgo/default.iprof`) and commit it.

### Local Build
Alternatively, compile and run locally on Windows by running:

```powershell
./gradlew :app:runWithPgoInstrument
```

GraalVM compiles with `--pgo-instrument -J-Xmx10g -H:NumberOfThreads=2`. Limiting concurrent compiler threads to 2 prevents compiler memory spikes when compiling the 218k reachable methods.

Use the instrumented app through representative workflows, then exit normally so the profile is written. The Windows Native Image packaging task (`:app:packageGraalvmNative`) consumes this profile when it exists and builds with `-O3`.

Until `default.iprof` exists, Oracle GraalVM's ML Profile Inference is used; when the PGO profile is applied, Native Image automatically disables ML inference. GraalVM 25.3.4.1 also enables loop-vectorization by default for eligible array-arithmetic loops.

No trained profile is included yet; this environment has no Windows runner, so a real Windows workload still needs to be recorded.
