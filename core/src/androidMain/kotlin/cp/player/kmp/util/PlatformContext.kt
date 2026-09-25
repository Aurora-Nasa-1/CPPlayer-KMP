package cp.player.kmp.util

import android.content.Context

/** Android：包装 [Context]。 */
actual class PlatformContext(val androidContext: Context? = null) {
    actual val raw: Any? = androidContext
}

/** 由 Android [Context] 构造 [PlatformContext]。 */
fun Context.toPlatformContext(): PlatformContext = PlatformContext(this.applicationContext)

/** 在 Android 源集中取回真实 [Context]（可能为 null）。 */
fun PlatformContext.androidContext(): Context? = androidContext