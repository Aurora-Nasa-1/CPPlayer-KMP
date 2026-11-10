package cp.player.sdk.example

import cp.player.sdk.ProviderContext
import cp.player.sdk.jni.JniProviderSession

/** Load the library once in host composition code before calling open(). */
class JniCatalogSession private constructor(private var handle: Long) : JniProviderSession {
    override external fun invoke(operation: Int, request: Any): Any
    override external fun cancel()
    override external fun close()
    external fun isWaiting(): Boolean

    companion object {
        fun open(context: ProviderContext): JniCatalogSession = JniCatalogSession(create(context))
        @JvmStatic private external fun create(context: ProviderContext): Long
    }
}
