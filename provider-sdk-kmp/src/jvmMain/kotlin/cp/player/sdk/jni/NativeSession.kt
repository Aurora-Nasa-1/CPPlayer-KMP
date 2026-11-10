package cp.player.sdk.jni

import cp.player.sdk.ProviderContext

/**
 * Standard JNI library entry. The host loads a separate copy of this bridge for each package.
 * Export Java_cp_player_sdk_jni_NativeSession_{createNative,invokeNative,cancelNative,closeNative}.
 */
class NativeSession(libraryPath: String, context: ProviderContext, settingsJson: String) : JniProviderSession {
    private var handle: Long

    init {
        System.load(libraryPath)
        handle = createNative(context.providerId, context.accountId, context.credential, settingsJson)
        check(handle != 0L) { "Native SDK session creation failed" }
    }

    private external fun createNative(providerId: String, accountId: String, credential: String?, settingsJson: String): Long
    private external fun invokeNative(handle: Long, operation: Int, request: ByteArray): ByteArray
    private external fun cancelNative(handle: Long)
    private external fun closeNative(handle: Long)

    override fun invoke(operation: Int, request: Any): Any {
        check(handle != 0L) { "Native SDK session closed" }
        return NativeWireCodec.decodeResponse(operation, invokeNative(handle, operation, NativeWireCodec.encodeRequest(operation, request)))
    }

    override fun cancel() { if (handle != 0L) cancelNative(handle) }
    override fun close() {
        val previous = handle
        handle = 0L
        if (previous != 0L) closeNative(previous)
    }
}
