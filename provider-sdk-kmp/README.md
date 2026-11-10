# KMP Provider SDK (preview)

Primary chain: **unified Kotlin data layer -> KMP SDK -> Kotlin / JS / native
adapter -> provider**. A provider author implements business methods, never
`callApi`, `apiMap`, CPPlayer JSON envelopes or v1/v3 response conventions.
This module has no dependency on core, Compose or the host database.

## Kotlin

Depend on `project(":provider-sdk-kmp")` in this repository. Publication tasks
are supplied by `maven-publish`; version 0.1.0 is a local preview, not a claim of
a published Maven release. `publishAllPublicationsToProviderPreviewRepository`
writes a file-based preview repository to `artifacts/provider-sdk/maven`, never
to a remote repository or the user's Maven local cache. Android and desktop JVM targets are configured.
The JVM artifact includes a typed JNI session adapter generated from the same
contract. JNI packages can be installed directly without host registration or
trust approval. A native library can use the standard `NativeSession` JNI exports
without a JAR or `nativeFactoryClass`. A custom `NativeProviderFactory` adapter
is optional. See the
[native package manifest](../docs/dev/MANIFEST_REFERENCE.md#原生-sdk-包).
Kotlin/Native compiler targets are not supplied by this SDK.

```kotlin
class MyProvider(private val context: ProviderContext) : MusicProvider {
    override suspend fun capabilities() =
        Capabilities(listOf(Capability.SEARCH, Capability.GET, Capability.RESOLVE))

    override suspend fun search(request: SearchRequest): TrackPage =
        catalog.search(request.keyword, request.limit, request.offset)

    override suspend fun get(request: IdRequest): Track = catalog.track(request.id)

    override suspend fun resolve(request: ResolveRequest): MediaResource =
        catalog.resolve(request.id, request.quality)
}
```

`getMany` has a cancellation-safe, ordered per-item default. Override it only if
your upstream has a true batch endpoint. NotFound is a typed ProviderException,
not an empty Track. The compiled [catalog example](examples/kotlin/CatalogProvider.kt)
uses `context.runtime.http` and parses only its own upstream format.
Provider implementations should use `ProviderContext.runtime.http` for upstream
requests so origin restrictions and account-session ownership remain with the
host. JNI adapts the same DTO operations and must not reintroduce endpoint maps
or host-specific JSON envelopes.

The application registers trusted compiled providers with `SdkBackendProvider`
and a factory `(ProviderContext) -> MusicProvider`. This metadata adapter is a
host concern, not an interface every provider author has to implement.

## JavaScript / TypeScript

Implement the same named business methods on `globalThis.provider`. Every method
receives a request object and the immutable account-scoped context:

```javascript
globalThis.provider = {
    capabilities: ["tracks.search", "tracks.get", "tracks.resolve"],
    async search(request, context) { return catalog.search(request, context); },
    async get(request, context) { return catalog.track(request.id, context); },
    async resolve(request, context) { return catalog.resolve(request, context); }
};
```

[The runnable example](examples/js/index.js) contains actual HTTP calls. Context
offers HTTP, SHA-256, UTF-8 conversion and session storage; it does not expose the
host filesystem or database. Bytes cross QuickJS as typed arrays (Kotlin
ByteArray appears as Int8Array). Hosts return Promise results on the owner thread.
Standard business results cross as objects/arrays, not stringify/parse.
Parsing your own upstream JSON is normal provider code, not a host protocol.

`js/index.mjs` exports `defineProvider`, an optional author-time check for
capability/method consistency. `js/index.d.mts` and `js/types.generated.ts` provide
the matching TS declarations. Bundle author-time imports before installation;
installed plugins do not carry platform DLLs or require Node.

```powershell
node provider-sdk-kmp/examples/js/upstream.mjs
# In another shell:
pwsh -File provider-sdk-kmp/examples/js/pack.ps1
```

Import `artifacts/provider-sdk/kmp-catalog-0.1.0.zip`, search `all`, and play the
locally generated 9.5 second tone. The server binds only to 127.0.0.1:48482.
On Android that address means the device; use adb reverse for a manual device
check. Cross-device HTTP is intentionally not enabled by the preview runtime.

## Verification

```powershell
pwsh -File scripts/verify-provider-sdk.ps1
pwsh -File scripts/verify-provider-sdk.ps1 -Android
```

Node and a configured JDK/Android SDK are required by their corresponding gates.
Native adapter validation is separate from these commands. The fixture server is a local
generated catalog/audio source, not a public music service or evidence of a
complete production provider migration.

Contract DTOs/codecs are generated from `provider-contract/schema.mjs`.
`provider-contract/documentation.mjs` supplies method and field semantics for
Kotlin KDoc, TypeScript editor comments and generated reference documents:

```powershell
node provider-contract/generate.mjs
node provider-contract/generate.mjs --check
```

The schema contains 161 typed business operations. Tags 61..126 cover comment
deletion, messaging, listen-together rooms/sync, MV/video, DJ, calendar/events,
playlist metadata and account/library operations. Tags 127..165 add extended
search, cursor comments, distinct album browsing, notifications, ordering,
imports, binary uploads, video/DJ browsing, recent media, account bindings and
media-quality enumeration. Tag 166 adds bounded liked-ID pagination
(`accounts.likes.page`); the host aggregates complete favorite collections.
See the generated [operation table](../docs/dev/SDK_OPERATIONS.md).
The generated [field/type reference](../docs/dev/SDK_TYPES.md) covers every DTO,
its defaults, nullability, units and operation-specific constraints.
The [provider workflows](../docs/dev/PROVIDER_WORKFLOWS.md) describe domain flows
and the [development guide](../docs/dev/PROVIDER_DEV_GUIDE.md) is the main entry point.
`MusicProvider` inherits `ExtendedOperations`; Kotlin capability constants for
these additions live in `ExtendedCapability`. All additions have Unsupported
defaults, so existing providers need not implement them.

The generator also maintains additive JS/JNI dispatch, host entrypoints, unified
source forwarding and nonempty cross-language test cases. `--check` covers all
of these files. Existing operation tags and record layouts are unchanged.
Capabilities are bounded to 256 entries per session (previously 64), so a provider
can declare the entire current contract.

Message bodies are plain text and message pages are newest-first; cursors are
opaque. Listen-together uses milliseconds, including heartbeat `intervalMs`.
Absent and empty playlists are distinct. Optional playlist tags distinguish no
change (`null`) from clear (`[]`). The typed contract and local dispatch tests
are not a claim of production upstream support.

`CommentQuery` adds input cursors without changing `CommentPageRequest` or any
other existing wire layout. Album browsing, latest discovery and dated charts
have separate methods. Search includes MV, video, user and DJ-radio pages.

Upload methods take bytes, never host paths or base64 envelopes. Image uploads
and audio chunks are capped at 1 MiB. Cloud uploads use
`prepareCloudUpload` -> `uploadCloudChunk` -> `completeCloudUpload`; the provider
owns all upstream tokens and session handles. A chunk either succeeds entirely
with the exact next offset or fails. Providers must validate session ownership,
declared file size, offset order and final chunk boundaries. Phone-binding
passwords are plaintext inputs; the provider owns upstream hashing.
See [migration status and remaining work](../docs/dev/SDK_MIGRATION_STATUS.md).

Do not hand-edit generated files. Check declaration/documentation consistency with:

```powershell
node provider-contract/generate.mjs --check
node --test provider-sdk-kmp/js/test.mjs provider-contract/documentation.test.mjs
npm ci --prefix provider-sdk-kmp/js --ignore-scripts
npm run --prefix provider-sdk-kmp/js check:types
```

Ownership, ID/time/batch semantics, security limits and migration rules are in the
current development guide; PROVIDER_TYPED_RUNTIME.md is a historical migration record.
The existing `provider-sdk/` TS SDK remains a legacy compatibility SDK; it is not
deleted or silently reinterpreted as this contract.
