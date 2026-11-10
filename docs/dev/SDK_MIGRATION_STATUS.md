# SDK Migration Status

As of 2026-11-10, the schema defines 160 business operations and 165 total
protocol operations. A declared contract is not proof that an installed provider
implements it. Unsupported defaults remain mandatory for additive capabilities.

## This Increment

Tags 127..165 add 39 operations:

- MV, video, user and DJ-radio search.
- Cursor comment pages and user comment history.
- Paginated new-album browsing, latest discovery and dated album charts as
  distinct operations.
- Comment notifications, mentions, system notices and resource messages.
- Playlist ordering, track ordering, cover updates and import tasks.
- Avatar/playlist-image uploads and a chunked cloud-upload lifecycle.
- Video categories, grouped videos, recommended timeline and subscriptions.
- DJ categories, category radios, subscribers and new/hot ranking selection.
- Recent albums, playlists, videos and DJ radios.
- Account binding information, phone binding/replacement and media qualities.

All operations have generated Kotlin/TypeScript types, capability mappings,
JS/JNI dispatch, ProviderHost entrypoints and UnifiedMusicSource forwarding.
Existing tags and existing record field order are unchanged. Generated nonempty
fixtures exercise the same typed chain; they do not implement a production music
service.

## Application Entry Points

Use the request-object methods on UnifiedMusicSource for current-session calls.
IDs are opaque provider-local strings, not CPMediaId values. The application
does not need JSON endpoint names or old response envelopes.

- searchPage supports song/album/artist/playlist plus user (1002), MV (1004),
  DJ radio (1009) and video (1014), preserving pagination and opaque IDs.
- getCommentsPage accepts a namespaced media ID, validates provider ownership
  and preserves the input cursor. Offset-only providers may serve offset pages,
  but are never used as fallback for a non-null cursor.
- Domain CommentPage.nextCursor distinguishes null (offset-only) from an empty
  opaque cursor. CommentScreenModel resets it on refresh/sort changes and sends
  it unchanged on subsequent pages. The server may change the effective sort.
- getMediaQualityOptions and UnifiedMediaResolver.getAvailableQualities validate
  provider ownership and return only available qualities from media.qualities.

## Upload Rules

Images and audio chunks are at most 1 MiB. They cross the JS bridge as typed
byte arrays and JNI as typed DTOs, never as paths/base64 JSON. Binary request
buffers are snapshotted before asynchronous dispatch.

Cloud uploads have three phases:

1. prepareCloudUpload receives a basename, file size, MD5 and bitrate and returns
   an opaque account-session upload handle and negotiated chunk size.
2. uploadCloudChunk submits offset/content/last. Success acknowledges the entire
   chunk, the exact next offset and complete equal to last.
3. completeCloudUpload publishes the uploaded file with optional metadata.

The provider owns upload credentials, allowed-origin HTTP, file-size tracking,
handle ownership, retry/resume rules and final-chunk validation. It must discard
session state on close. Host checks do not replace those upstream checks.
Phone passwords are plaintext inputs; upstream hashing belongs to the provider.

## Remaining Scope

This is not a complete port of every module in the reference service. Remaining
families include specialized MV/DJ charts and creator/voice features, Mlog,
topics, user social-status and statistics, VIP/Yunbei/musician tasks, audio
recognition and other service-specific tools. Some existing catalog operations
also still need richer filters and pagination. The reference endpoint count
cannot be subtracted from the SDK operation count: aliases and multi-step
business operations are not one-to-one.

Real providers must implement/declare each capability and map actual upstream
responses. Production accounts, native libraries, device playback and public
service conformance require separate integration validation. Raw API passthrough
and JSON batch are intentionally not migration targets.
