# FLICK VIP release

FLICK VIP uses manually issued cards. Paste a card in Audio Engine settings.
On Android this unlocks FLICK, including USB direct output on supported DACs.
On Windows it unlocks the WASAPI exclusive option. Previously issued signed
permanent cards continue to work.

## Offline trial cards

Keep the issuer key outside the repository and application build. Set the same
`FLICK_VIP_PUBLIC_KEY` for Kotlin and the private Rust engine.

```powershell
node scripts/mint-flick.mjs keygen E:/private/flick-issuer.pem
$env:FLICK_VIP_PUBLIC_KEY = node scripts/mint-flick.mjs public-key E:/private/flick-issuer.pem
$expiry = [DateTimeOffset]::UtcNow.AddDays(7).ToUnixTimeSeconds()
node scripts/mint-flick.mjs issue E:/private/flick-issuer.pem trial $expiry
```

Send only stdout (the card). The trial ends at the fixed timestamp set at
issuance. Both Kotlin and Rust enforce expiry and also use elapsed time while
the process is running. Offline enforcement relies on the device clock across
restarts. Offline cards can be copied and have no remote revocation.

## Cloud cards

Build the separate service with `./gradlew :flick-license-server:installDist`.
Set `FLICK_ISSUER_KEY_FILE` to the private PKCS#8 PEM and
`FLICK_CLOUD_CARD_DIR` to a private existing directory on persistent storage.
Run the launcher under `flick-license-server/build/install/flick-license-server/bin`
with Java 21 or newer. The service listens on loopback port 8787 by default
(`FLICK_CLOUD_PORT` overrides it).

Put an HTTPS reverse proxy in front of `POST /v1/flick/lease`. Preserve the
Authorization header and exclude it from logs. Build the app with the full
HTTPS endpoint in `FLICK_VIP_LEASE_URL`. The service key must match the
app and native library public key. The app only needs the public key.

```powershell
$expiry = [DateTimeOffset]::UtcNow.AddDays(30).ToUnixTimeSeconds()
node scripts/mint-flick.mjs cloud-issue E:/private/flick-cloud-cards $expiry
node scripts/mint-flick.mjs cloud-set E:/private/flick-cloud-cards <record-id> off
node scripts/mint-flick.mjs cloud-set E:/private/flick-cloud-cards <record-id> $expiry
```

Send stdout from `cloud-issue` and keep the full record ID printed to stderr.
Run those commands against the same card directory as the service. Each card is
a bearer credential. Any tester with a copy can activate it.

Initial activation and app restart require connectivity. The app checks about
once per minute. A server denial clears the entitlement on the next successful
check. A connection failure leaves the existing lease usable until its expiry,
at most three minutes from issuance. The app then returns to standard playback.
The cloud card itself never goes through JNI. Rust receives the signed lease and
independently enforces its signature and expiry.

## APK build and device acceptance

- Publish `librust_lib_flick_player.so` and `rust_lib_flick_player.dll` as assets
  of a dedicated prerelease in this repository. Use a `flick-native-*` tag to
  keep runtime releases outside the app release workflows and update channels.
  Both workflows download from this repository with the built-in Actions token.
  Set `FLICK_NATIVE_RELEASE_TAG`, `FLICK_ANDROID_SHA256`, `FLICK_WINDOWS_SHA256`,
  and `FLICK_VIP_PUBLIC_KEY` as repository variables. Keep the APK signing
  secrets configured for the stable Android build.
  Build the asset from the private engine with ARM64, UAC2, the production
  public key, the `nativeSetVipLicenseV2` JNI entry point and 16 KiB ELF alignment.
  CI checks the digest, embedded bytes,
  public key, JNI exports, ELF/ZIP alignment and signing before publishing.
  From the private engine checkout, use NDK r28 or later and
  `cargo ndk -t arm64-v8a --platform 24 -o <jni-root> build --release`.
- The Windows job checks the DLL digest, native verifier entry point and
  matching public key before packaging.
- Locally point `FLICK_ANDROID_JNI_DIR` at a directory containing
  `arm64-v8a/librust_lib_flick_player.so`; it defaults to
  `app-android/src/main/jniLibs`. Set `FLICK_VIP_PUBLIC_KEY`, then run
  `./gradlew :app-android:assembleFastrelease -Pflick.required=true` for an
  R8 build signed with the debug key. Use `assembleRelease` and the existing
  release keystore variables for a production-signed APK.
- On an ARM64 device, confirm that no license and an altered token both stay
  on Media3. Activate a valid token, restart and select FLICK. Before playback,
  grant access to a supported USB DAC. Start a 44.1 kHz track and then a
  96 kHz track. Check the DAC rate and `adb logcat -s FlickPlatformPlayer`
  for `USB direct session active: true`. Engine selection, device discovery
  and a successful permission grant do not prove USB direct playback.
- Test permission denial, unplug while playing, reconnect, and play without
  a USB device. Android-managed playback is a supported fallback and must
  not count as USB direct in acceptance. A physical DAC is required to
  accept the exclusive output claim.
