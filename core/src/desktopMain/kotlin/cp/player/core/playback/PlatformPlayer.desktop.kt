package cp.player.core.playback

import cp.player.core.util.PlatformContext

actual fun createPlatformPlayer(context: PlatformContext): PlatformPlayer = AudioPlayerImpl()
