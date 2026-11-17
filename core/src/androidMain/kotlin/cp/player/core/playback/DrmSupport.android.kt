package cp.player.core.playback

/** Media3 delegates to the platform MediaDrm; Widevine is the only scheme we wire. */
actual fun supportedDrmKeySystems(): List<String> = listOf("widevine")
