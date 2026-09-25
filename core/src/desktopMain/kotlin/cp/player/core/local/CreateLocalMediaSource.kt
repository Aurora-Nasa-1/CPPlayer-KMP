package cp.player.core.local

import cp.player.core.util.PlatformContext

/** Desktop actual：文件系统遍历（[DesktopLocalMediaSource]，实现位于 jvmMain）。 */
actual fun createLocalMediaSource(context: PlatformContext): LocalMediaSource =
    DesktopLocalMediaSource()
