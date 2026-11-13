package cp.player.core.provider.typed

import cp.player.core.provider.*
import cp.player.core.util.PlatformInfo
import cp.player.core.util.PlatformContext
import cp.player.sdk.ProviderContext
import cp.player.sdk.jni.JniMusicProvider
import cp.player.sdk.jni.JniProviderSession
import cp.player.sdk.jni.NativeProviderFactory
import cp.player.sdk.jni.NativeSession
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal expect fun nativePackageClassLoader(entry: File, libraryDirectory: File): ClassLoader
internal expect fun nativeLibraryClassLoader(context: PlatformContext?): ClassLoader

internal fun installedNativeSdkProvider(manifest: ModuleManifest, root: File, context: PlatformContext?): BackendProvider {
    val abis = PlatformInfo.supportedAbis
    require(manifest.supportedAbis == null || manifest.supportedAbis.any { it in abis }) {
        "Native provider does not support this platform ABI: ${abis.joinToString()}"
    }
    val className = manifest.nativeFactoryClass
    if (className != null) {
        ZipFile(File(root, manifest.entryPoint)).use { archive ->
            val classEntry = if (PlatformInfo.isAndroid) "classes.dex" else className.replace('.', '/') + ".class"
            require(archive.getEntry(classEntry)?.isDirectory == false) {
                "Native SDK entryPoint is missing $classEntry"
            }
        }
    }
    // Loaded DLLs must never lock the installation directory or follow its replacement.
    val snapshot = File(root.parentFile, ".repository-runtime-${UUID.randomUUID()}")
    var loader: ClassLoader? = null
    try {
        check(root.copyRecursively(snapshot)) { "Cannot prepare native SDK runtime" }
        val libraryDirectory = abis.asSequence().map { File(snapshot, "lib/$it") }
            .firstOrNull { it.isDirectory } ?: snapshot
        val entry = File(snapshot, manifest.entryPoint)
        val packageLoader = if (className == null) nativeLibraryClassLoader(context)
            else nativePackageClassLoader(entry, libraryDirectory)
        loader = packageLoader
        val factory: (ProviderContext) -> JniProviderSession = if (className == null) {
            val bridge = packageLoader.loadClass(NativeSession::class.java.name)
                .asSubclass(JniProviderSession::class.java)
            val constructor = bridge.getConstructor(String::class.java, ProviderContext::class.java, String::class.java)
            val open: (ProviderContext) -> JniProviderSession = { sessionContext ->
                try {
                    constructor.newInstance(entry.absolutePath, sessionContext, Json.encodeToString(sessionContext.settings))
                } catch (error: java.lang.reflect.InvocationTargetException) {
                    throw IllegalArgumentException(
                        "Native SDK library must export NativeSession create/invoke/cancel/close methods: ${error.targetException.message}",
                        error.targetException,
                    )
                }
            }
            open
        } else {
            val factoryType = Class.forName(className, false, packageLoader).asSubclass(NativeProviderFactory::class.java)
            val constructor = factoryType.getConstructor()
            val open: (ProviderContext) -> JniProviderSession = { sessionContext ->
                constructor.newInstance().open(sessionContext)
            }
            open
        }
        val runtime = NativePackageRuntime(packageLoader, snapshot, factory)
        val descriptor = SdkBackendProvider(
            id = manifest.id, name = manifest.name, version = manifest.version, type = ProviderType.JNI,
            factory = { JniMusicProvider(runtime.open(it)) },
            settingsSchema = manifest.settingsSchema,
            sdkAllowedOrigins = manifest.sdkOrigins.toSet(),
            capabilities = manifest.knownCapabilities,
        )
        return object : BackendProvider by descriptor {
            override fun close() {
                descriptor.close()
                runtime.close()
            }
        }
    } catch (error: Throwable) {
        runCatching { (loader as? java.io.Closeable)?.close() }
        snapshot.deleteRecursively()
        throw IllegalArgumentException("Cannot load native SDK package: ${error.message}", error)
    }
}

/** Keep the adapter and its files alive until cancelled JNI calls have drained and closed. */
internal class NativePackageRuntime(
    private val loader: ClassLoader,
    private val snapshot: File,
    private val factory: (ProviderContext) -> JniProviderSession,
) {
    private var retired = false
    private var sessions = 0
    private var cleanupAttempted = false

    @Synchronized
    fun open(context: ProviderContext): JniProviderSession {
        check(!retired) { "Native provider closed" }
        val session = factory(context)
        sessions++
        return object : JniProviderSession by session {
            private val closed = java.util.concurrent.atomic.AtomicBoolean()
            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    try { session.close() } finally { release() }
                }
            }
        }
    }

    @Synchronized
    fun close() {
        if (retired) return
        retired = true
        cleanupIfDrained()
    }

    @Synchronized
    private fun release() {
        sessions--
        cleanupIfDrained()
    }

    private fun cleanupIfDrained() {
        if (!retired || sessions != 0 || cleanupAttempted) return
        cleanupAttempted = true

        var failure: Throwable? = null
        try { (loader as? java.io.Closeable)?.close() } catch (error: Throwable) { failure = error }
        try {
            // Windows can retain a DLL until its defining class loader is collected.
            if (!snapshot.deleteRecursively() && snapshot.exists()) {
                throw IllegalStateException("Cannot delete native SDK runtime snapshot: ${snapshot.absolutePath}")
            }
        } catch (error: Throwable) {
            val closeFailure = failure
            if (closeFailure == null) failure = error
            else if (closeFailure !== error) closeFailure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}
