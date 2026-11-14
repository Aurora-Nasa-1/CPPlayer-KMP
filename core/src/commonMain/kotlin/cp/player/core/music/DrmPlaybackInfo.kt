package cp.player.core.music

/**
 * DRM playback signaling handed from a Provider to the platform player.
 *
 * This object never contains a content key or a cached license. [exchangeLicense]
 * asks the Provider to exchange the platform CDM's challenge; the returned bytes
 * are passed straight to the CDM and must not be logged or persisted.
 *
 * [licenseUrl] is non-null only when the platform may POST the challenge directly.
 * A null [licenseUrl] requires [exchangeLicense].
 */
data class DrmPlaybackInfo(
    val keySystem: String,
    val licenseUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val schemeIdUri: String? = null,
    val initData: ByteArray? = null,
    val keyId: ByteArray? = null,
    val keyUri: String? = null,
    val serverCertificate: ByteArray? = null,
    val persistent: Boolean = false,
    val exchangeLicense: (suspend (challenge: ByteArray) -> ByteArray)? = null,
)
