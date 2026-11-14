package cp.player.core.playback

/** No desktop CDM is bundled, so encrypted streams must not be requested. */
actual fun supportedDrmKeySystems(): List<String> = emptyList()
