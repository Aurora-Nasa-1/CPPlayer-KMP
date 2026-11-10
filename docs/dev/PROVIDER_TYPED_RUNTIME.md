# KMP Provider SDK - architecture and migration record

Status: incremental migration, not a replacement of all core functionality.
Date: 2026-11-10.

## Dependency direction

```text
Compose / repositories -> Kotlin unified music data layer -> KMP SDK contract
  -> ProviderHost -> compiled Kotlin / QuickJS / trusted JNI adapter
  -> provider business implementation
       -> account-scoped host services (HTTP, storage, SHA-256)
```

The domain must not depend on a provider implementation. The SDK contains
development types and helpers, not UI, database access, or host cache policy.
The primary development API is `provider-sdk-kmp`. It depends on coroutines,
never on core, Compose, a host database, or an individual provider. Kotlin/JNI
providers use the same typed business contract; the JNI implementation is trusted
host code and is not a sandbox.

## Source audit

Confirmed from the working tree, including pre-existing uncommitted changes:

- `BackendProvider.callApi(String, Map<String, String>): String` is synchronous.
  `ProviderManager` dispatches it to IO, but its catch-all `Exception` also catches
  coroutine cancellation. `MusicApiServiceImpl` parses the returned JSON.
- `JsBackendProvider` uses a single-thread executor, but synchronously waits with
  `runBlocking`. Its dispatcher stringifies business results; fetch, crypto and
  storage also use JSON envelopes. It initializes settings once in `env`.
- JS storage is scoped to provider ID, not account. The current cookie storage
  also only stores one cookie per provider. This is not multi-account isolation.
- `UnifiedMusicSourceImpl` and `MusicSourceFromApi` parse legacy response shapes.
  Batch matching includes a lowercase fallback, which is unsafe for opaque IDs.
- The working-tree FFM provider still calls a string C ABI, with an optional
  release function and unbounded NUL-string reads. Its load/close/call operations
  are not synchronized. This is a confirmed unsafe boundary shape, not evidence
  that a particular crash has occurred.
- Desktop JNI workers provide a process boundary but currently use HTTP/JSON.
  Their existing protocol must not be silently replaced for legacy plugins.
- Legacy API caching includes provider/config/cookie in keys. This does not by
  itself prove that a result started before a switch cannot be published after it.

The requested `.workbuddy-ai/memory/TOPICS.md` and `LOG.md` are absent on this
machine. No additional AGENTS.md was found in the inspected working tree.
Existing TS SDK, FFM work, fixtures, staged deletions and unrelated UI/platform
changes are pre-existing work and must be preserved.

## Decisions and verification gates

1. Keep Kotlin/KMP domain models and mature playback algorithms. Introduce a
   small contract rather than exporting all internal models.
2. Probe the resolved QuickJS 1.0.15 binary/source before changing engines.
   Required gates: nested values, arrays, bytes, Promise completion, exception
   propagation, interrupt support, memory limits, and no hidden JSON bridge.
3. Use the existing KMP build for the SDK and existing QuickJS binding for JS.
   No second engine and no obligatory Rust compiler for Kotlin/JS consumers.
   `provider-contract/schema.mjs` generates the SDK DTOs and TypeScript
   declarations. A production JNI/FFM binding generator and Android native
   package format are still open gates.
4. Host owns immutable per-session settings/credentials, bounded concurrency,
   cancellation, deadlines and publication fencing. Closing is idempotent and
   drains children before releasing the adapter. Cancellation is never an empty
   success. Credentials must not appear in diagnostics.
5. New business results use typed values, not intermediate JSON. Upstream JSON,
   manifests and old HTTP APIs are distinct allowed uses. Necessary copying and
   binary IPC do not constitute zero-copy.
6. Unsupported capabilities are explicit. IDs are opaque case-sensitive strings;
   the host alone adds `{provider}://song/`. Pagination uses item offsets; all
   durations/deadlines/expiry use milliseconds (expiry is Unix epoch).
   Batches preserve input order and duplicates, with one outcome per input.
7. New JS packages opt in using `contract: "kmp-sdk-v1"`. No reinterpretation of
   automatic migration/deletion of existing provider packages or user data.

## Implemented surfaces

- Independent Android/JVM KMP SDK, Maven publication tasks, typed business
  interface, generated DTOs, TS declarations, and compiled Kotlin/JS examples.
- `UnifiedMusicSourceImpl` maps SDK objects once into domain models. Search UI's
  `MusicRepository` receives this source; playback and downloads already use it.
  Legacy normalization lives in `provider/compat/LegacyMusicSourceAdapter`.
- `ProviderHost` owns session child tasks, deadline including queue wait, permits,
  validation, caller cancellation/draining and dispose-once release.
- `ProviderSdkSessions` replaces instances on provider/account/config/credential
  changes. Metadata close/stop invalidates sessions and retires their resources.
  A credential epoch fences change-away/change-back, not only string equality.
- SDK audio-cache keys include account and credential epoch. Typed metadata
  bypasses the old JSON API cache; no second provider-owned global cache is added.
- JS uses a dedicated owner dispatcher, actual JsObject/list/byte bindings,
  asynchronous host functions, 32 MiB engine heap, 512 KiB stack, 250 ms execution
  slice, bounded result tree and host deadline. Heap limits do not measure or cap
  all JVM allocations. The binary signedness probe confirmed Int8Array, and the
  array probe accepts the binding's actual list representation.
- HTTP is deny-by-default, exact-origin scoped, redirect-disabled and bounded.
  Plain HTTP is loopback-only. Storage is ephemeral, at most 1 MiB per session.
  Persistence and additional crypto algorithms are not claimed.

## Ownership and isolation

Kotlin providers registered in-process are explicitly trusted application code.
They can call JVM/native APIs and are not sandboxed by the SDK. Imported new JS
providers receive only their own immutable context and capability bindings;
the host injects no filesystem, dynamic loader, other provider or database API.
This is not a proof against vulnerabilities inside the QuickJS native library.

## Version and semantic rules

SDK artifact version is `0.1.0` (preview). Contract major is 1. Incompatible
changes require a new major and explicit adapter; do not silently reinterpret
v1/v3 package tags. Unknown capability names are tolerated; invoking an absent
capability returns Unsupported. Unknown quality strings are preserved.

IDs are opaque case-sensitive strings, 1..1024 UTF-16 characters without control
characters. Only the domain mapper adds the provider namespace. Durations are
milliseconds; expiry is Unix epoch milliseconds; sizes are bytes; bitrate is
bits/second. All cross-JS integers must be exact and no larger than 2^53-1.
Search uses item offset (not page number), limit 1..500; nullable total/hasMore
means unknown, not zero/false. Batches contain 1..500 IDs, preserve order and
duplicates, and return exactly one track XOR failure per ID. The domain's old
list-only API drops per-item failures; callers needing them use getTrackOutcomes.

Resolve reports actualQuality independently of requested quality, explicit
headers and nullable absolute expiry. The new domain mapper does not guess
codec/bitrate from field names or audio quality. Null fields mean unavailable.
Failure categories are extensible strings (Unsupported, NotFound, Authentication,
Network, RateLimit, Timeout, InvalidResponse, Runtime); cancellation remains a
CancellationException, never a Failure or successful empty page.

## Migration checklist and retirement conditions

- [x] A: independent KMP contract and actual QuickJS value/async/interrupt probe.
- [x] B: search/detail/batch/resolve SDK route into unified music source and UI
  repository wiring (not a claim of an observed interactive UI playback session).
- [x] C: automated Kotlin/JS local HTTP contract suite and desktop native worker
  gate; generated PCM downloaded and checked. See validation record for outcomes.
- [ ] D: account/cache/auth and remaining business capability migration.
- [ ] E: packaging matrix, legacy retirement and removal after reference audit.

| Surface | Current | Required before legacy removal |
|---|---|---|
| Search / track / batch / URL | SDK route, explicit legacy fallback | Migrate installed providers and verify UI |
| Lyrics / playlists / albums / artists | Legacy; SDK Unsupported | Separate typed capability interfaces + suites |
| Authentication | Scoped contexts and cookie storage foundation | Migrate AuthRepository and account UI flows |
| Cache | Account-fenced SDK audio keys, no typed metadata cache | Host typed cache policy, downloads/account restore audit |
| UnifiedMediaResolver video/audio facade | Historical API route | Separate typed video/audio resolver migration |
| Native distribution | Trusted JNI host adapter only | ABI packaging/signing/install trust UX |
| Other desktop OS / Android device | Build gates only | Device and distribution matrix |

Existing user data is not rewritten. Default-account cookie keys stay unchanged;
new account cookie keys use length-delimited provider/account namespaces. Old
packages remain opt-in compatibility packages. Removing the new package restores
legacy behavior without a data conversion. Ephemeral SDK storage intentionally
does not pretend to migrate or persist old JS provider storage.

See [PROVIDER_KMP_VALIDATION.md](PROVIDER_KMP_VALIDATION.md) for actual test/build
results, limitations and measured costs. Neither these fixtures nor a passing
build proves production-service integration, UI playback, zero-copy or a speedup.

## Legacy JSON layer removal (2026-11-10)

The transitional JSON surface is gone. Deleted from `core`:

- `api/MusicApiService`, `MusicApiServiceImpl`, `MusicApiServiceFactory`, `MusicApiMethod`,
  `ResourceMusicApi`, `ApiFieldContract`, `ApiResponseCodes`, `LoginStatus`,
  `PlaybackUrlResponse`, the SDK output projections (`SdkApiProjection`,
  `SdkExtendedApiProjection`, `SdkAdditionalApiProjection`).
- `cache/*`: `CachedMusicApiService` and the JSON cache (`ApiCache`, `CacheConfig`,
  `CacheEntry`, `CacheResult`, `CacheStats`, `Fingerprinter`). Provider-side caching is
  now only the audio-file cache, keyed by provider/account/credential/settings.
- `music/MusicSourceFromApi`, `music/TrackJsonMapper`, `provider/compat/LegacyMusicSourceAdapter`.
- `listentogether/NeteaseListenTogetherBackend` (it dispatched `listentogether/...` over the
  JSON bridge); replaced by `UnsupportedListenTogetherBackend`, which reports the capability
  as absent instead of failing at call time.

Consumer changes:

- `MusicRepository`, `AuthRepository`, `SocialRepository` take only a `UnifiedMusicSource`.
  Authentication goes through `authenticate(AuthActionRequest)`; `getCurrentAccount()` is the
  only login-status read.
- `MusicBackend.playbackController`, the lyrics registry (`createLyricsSourceRegistry`) and
  `PlaybackLyricsLoader` no longer accept an API object; provider lyrics come from
  `UnifiedMusicSource.getLyrics`.
- The diagnostics page dropped its "API test" and "apiMap" tabs: there is no dispatchable raw
  endpoint any more.

## Diagnostics follow-up (same day)

- `HealthMonitor` is fed from `UnifiedMusicSourceImpl.boundary(op) { ... }` again. Every call
  reports provider id, operation name (the source method, e.g. `getTrackDetail`), duration and
  a three-way level: `OK` = result, `WARNING` = `BackendResult.Unsupported` (capability gap, not
  a fault), `ERROR` = failure. This is the only place that knows all four facts, so no
  provider-level instrumentation is needed.
- The record model lost the JSON-era fields (`responseWarnings`, `responseCode`, `errorCode`,
  `rawResponse`, `expectedField`, fallback flags) together with the warning taxonomy they fed.
  `getStatsByMethod()` became `getStatsByOperation()`, and the diagnostics tab is now
  "操作统计" / "操作统计（按 UnifiedMusicSource 操作名）".
- `BackendProvider.apiMap`, `ModuleManifest.apiMap`, `SdkBackendProvider.apiMap` and
  `FfmProvider.apiMap` are deleted. They were always `null` and never read; both manifest
  decoders run with `ignoreUnknownKeys = true`, so already-installed manifests that still
  contain the key keep loading.
- `PlaybackControllerImpl`'s unused `cookieProvider` parameter is gone (cache namespacing goes
  through `cacheNamespace`), as are `MusicBackend.localMusic` (a `@Deprecated` alias nobody
  assigned) and the dead `SettingsAnimationKit` / `LocalSongMetadata` leftovers.
- `BackendProvider.startServer` / `stopServer` / `callApi` / `analyzeAudio` are deleted.
  `retireSessions()` is now a standalone default member, and `SdkBackendProvider` overrides it
  directly. Tests that only asserted "the retired stub throws" were replaced with a check that
  rejecting a legacy descriptor does not create an SDK session.

- `ProviderType` now has exactly two values: `JNI` (trusted native SDK, registered via
  `jniSdkProvider`) and `SCRIPT` (KMP SDK JS/TS package, loaded via `ProviderFactory`).
  `FFM` / `BINARY` / `WEBSOCKET` / `HTTP` had no constructor and were removed; the stale
  hot-swap branch in `MusicBackend.installPackage` collapsed to `if (previous != null)` —
  in-process SDK providers always need a restart to be replaced.