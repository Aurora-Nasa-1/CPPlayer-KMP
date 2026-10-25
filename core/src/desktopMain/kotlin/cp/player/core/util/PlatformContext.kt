package cp.player.core.util

/** Desktop：无平台上下文，空占位。 */
actual class PlatformContext actual constructor() {
    constructor(dummy: Any?) : this()
    actual val raw: Any? = null
    companion object {
        /** Desktop 平台的默认空上下文。 */
        val EMPTY = PlatformContext()
    }
}