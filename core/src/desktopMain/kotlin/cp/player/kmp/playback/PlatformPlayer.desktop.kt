package cp.player.kmp.playback

import cp.player.kmp.util.PlatformContext

actual fun createPlatformPlayer(context: PlatformContext): PlatformPlayer = AudioPlayerImpl()
