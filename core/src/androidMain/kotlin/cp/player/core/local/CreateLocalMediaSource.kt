package cp.player.core.local

import cp.player.core.util.PlatformContext
import cp.player.core.util.androidContext

/** Android actual：MediaStore 扫描 + SAF 树导入，需要 Android Context。 */
actual fun createLocalMediaSource(context: PlatformContext): LocalMediaSource =
    AndroidLocalMediaSource(context.androidContext() ?: error("PlatformContext needs Android Context"))
