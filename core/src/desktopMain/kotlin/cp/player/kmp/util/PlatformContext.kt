package cp.player.kmp.util

/** Desktop：无平台上下文，空占位。 */
actual class PlatformContext(val dummy: Any? = null) {
    actual val raw: Any? = dummy
    companion object {
        /** Desktop 平台的默认空上下文。 */
        val EMPTY = PlatformContext()
    }
}