package cp.player.core.playback

/**
 * Canonical key systems this platform can actually feed a CDM.
 *
 * Desktop has no CDM integration and returns an empty list, so the source never
 * asks a Provider for an encrypted stream it cannot play.
 */
expect fun supportedDrmKeySystems(): List<String>
