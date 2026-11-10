package cp.player.sdk.jni

import cp.player.sdk.ProviderContext

/**
 * Entry class for installable JNI packages. Implement with a public no-argument constructor.
 * Load native libraries from the package with System.loadLibrary in the adapter's own class.
 * The host creates one session for each immutable account/configuration context.
 */
interface NativeProviderFactory {
    fun open(context: ProviderContext): JniProviderSession
}
