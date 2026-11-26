package cp.player.core.provider.typed

import cp.player.core.provider.*
import cp.player.core.util.PlatformInfo
import cp.player.core.util.PlatformContext
import cp.player.sdk.MusicProvider
import cp.player.sdk.ProviderContext
import cp.player.sdk.jni.JniMusicProvider
import cp.player.sdk.jni.JniProviderSession
import cp.player.sdk.jni.NativeProviderFactory
import cp.player.sdk.jni.NativeSession
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal expect fun nativePackageClassLoader(entry: File, libraryDirectory: File): ClassLoader
internal expect fun nativeLibraryClassLoader(context: PlatformContext?): ClassLoader

/**
 * GraalVM Native Image 运行时判定。
 *
 * `org.graalvm.nativeimage.imagecode` 只在镜像里有值（构建期 `buildtime`、运行期 `runtime`），
 * 普通 JVM / Android 上取不到。**2026-11-14 实测**（GraalVM 25.3.4.1、`-Ob` 迷你镜像）：
 * 镜像里 `getResource("Foo.class")` 一律返回 `null` —— 类文件不是可读资源。
 */
internal val nativeImageRuntime: Boolean
    get() = System.getProperty("org.graalvm.nativeimage.imagecode") != null

internal fun installedNativeSdkProvider(manifest: ModuleManifest, root: File, context: PlatformContext?): BackendProvider {
    val abis = PlatformInfo.supportedAbis
    require(manifest.supportedAbis == null || manifest.supportedAbis.any { it in abis }) {
        "Native provider does not support this platform ABI: ${abis.joinToString()}"
    }
    val className = manifest.nativeFactoryClass
    if (nativeImageRuntime) {
        check(className == null) { nativeAdapterJarUnavailable(manifest) }
        return installedCAbiProvider(manifest, root)
    }
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
    var closable: java.io.Closeable? = null
    try {
        check(root.copyRecursively(snapshot)) { "Cannot prepare native SDK runtime" }
        val libraryDirectory = abis.asSequence().map { File(snapshot, "lib/$it") }
            .firstOrNull { it.isDirectory } ?: snapshot
        val entry = File(snapshot, manifest.entryPoint)
        val packageLoader: ClassLoader = when {
            className == null -> nativeLibraryClassLoader(context)
            else -> nativePackageClassLoader(entry, libraryDirectory)
        }
        closable = packageLoader as? java.io.Closeable
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
        val runtime = NativePackageRuntime(closable, snapshot, factory)
        val descriptor = SdkBackendProvider(
            id = manifest.id, name = manifest.name, version = manifest.version, type = ProviderType.JNI,
            // 同一上下文按需开多个会话：单会话仍是串行队列，但首页并发拉多个接口时
            // 不再把上游往返排队叠加（实测单会话会让平均响应从 ~150ms 涨到 1s+）。
            factory = { context -> JniMusicProvider.pooled { runtime.open(context) } },
            settingsSchema = manifest.settingsSchema,
            sdkAllowedOrigins = manifest.sdkOrigins.toSet(),
            capabilities = manifest.knownCapabilities,
            defaultCountryCode = manifest.resolvedDefaultCountryCode,
            imageResizeRule = manifest.imageResize,
        )
        return object : BackendProvider by descriptor {
            override fun close() {
                descriptor.close()
                runtime.close()
            }
        }
    } catch (error: Throwable) {
        runCatching { closable?.close() }
        snapshot.deleteRecursively()
        throw IllegalArgumentException("Cannot load native SDK package: ${error.message}", error)
    }
}

private fun installedCAbiProvider(manifest: ModuleManifest, root: File): BackendProvider {
    // Keep package bytes stable across import/replace, without mapping the library during the scan.
    val snapshot = File(root.parentFile, ".repository-runtime-${UUID.randomUUID()}")
    try {
        check(root.copyRecursively(snapshot)) { "Cannot prepare native SDK runtime" }
        val owner = NativeImageProviderLibrary(manifest, snapshot)
        val descriptor = SdkBackendProvider(
            id = manifest.id, name = manifest.name, version = manifest.version, type = ProviderType.JNI,
            factory = owner::open,
            settingsSchema = manifest.settingsSchema,
            sdkAllowedOrigins = manifest.sdkOrigins.toSet(),
            capabilities = manifest.knownCapabilities,
            defaultCountryCode = manifest.resolvedDefaultCountryCode,
            imageResizeRule = manifest.imageResize,
        )
        return object : BackendProvider by descriptor {
            override val requiresExclusiveNativeLibrary = true

            override fun close() {
                try { descriptor.close() } finally { owner.retire() }
            }
        }
    } catch (error: Throwable) {
        snapshot.deleteRecursively()
        throw error
    }
}

/** One loaded C ABI package per process. A host owns a lease until all its lanes have closed. */
private class NativeImageProviderLibrary(
    private val manifest: ModuleManifest,
    private val snapshot: File,
) {
    private companion object {
        val lock = Any()
        var loaded: NativeImageProviderLibrary? = null
    }

    private var factory: CAbiSessionFactory? = null
    private var leases = 0
    private var retired = false
    private var failed = false

    fun open(context: ProviderContext): MusicProvider {
        val settings = Json.encodeToString(context.settings)
        val library = synchronized(lock) {
            check(!retired && !failed) { "Native provider closed" }
            check(loaded == null || loaded === this) { "Previous native provider has not unloaded" }
            val acquired = factory ?: run {
                loaded = this
                try {
                    when (val probe = probeCAbiSessionFactory(File(snapshot, manifest.entryPoint))) {
                        is CAbiProbe.Available -> probe.factory.also { factory = it }
                        is CAbiProbe.Unavailable -> error(nativeJniUnavailable(manifest, probe.reason))
                    }
                } catch (error: Throwable) {
                    // An uncertain unload must never let a second library into the process.
                    if (error is NativeLibraryUnloadException) failed = true else loaded = null
                    throw error
                }
            }
            leases++
            acquired
        }
        val pool = JniMusicProvider.pooled { library.open(context, settings) }
        return object : MusicProvider by pool {
            private val closing = AtomicBoolean()
            override suspend fun close() {
                if (!closing.compareAndSet(false, true)) return
                try {
                    pool.close()
                } catch (error: Throwable) {
                    synchronized(lock) { failed = true }
                    throw error
                } finally {
                    release()
                }
            }
        }
    }

    fun retire() = synchronized(lock) {
        retired = true
        deleteSnapshotIfUnused()
    }

    private fun release() = synchronized(lock) {
        leases--
        if (leases == 0 && !failed) {
            try { checkNotNull(factory).close() } catch (error: Throwable) {
                failed = true
                throw error
            }
            factory = null
            loaded = null
        }
        deleteSnapshotIfUnused()
    }

    private fun deleteSnapshotIfUnused() {
        if (retired && leases == 0 && !failed && !snapshot.deleteRecursively() && snapshot.exists()) {
            error("Cannot delete native SDK runtime snapshot: ${snapshot.absolutePath}")
        }
    }
}

/** 原生构建 + 「只有 JNI、没有 C ABI」时给出的原因（见 docs/dev/providers/NATIVE.md）。 */
private fun nativeJniUnavailable(manifest: ModuleManifest, probeFailure: String?): String =
    "当前是原生构建（GraalVM Native Image），音源模块「${manifest.name}」只导出了 JNI 符号，" +
        "没有导出 C ABI（cpp_abi_version / cpp_create / cpp_invoke / cpp_cancel / cpp_close）：" +
        "JNI 要求宿主按包各自定义一次 cp.player.sdk.jni.NativeSession，而 native image " +
        "不提供类文件资源、也不支持运行时定义类。请升级该音源模块到支持 C ABI 的版本，" +
        "或改用 JS 音源模块 / JVM 版客户端。" +
        // 探测失败的具体原因要看得到：宿主侧没登记 FFM 签名（MissingForeignRegistrationError）
        // 长得很像「音源没导出 C ABI」，不给这一行就会把排查方向指到音源仓库去。
        (probeFailure?.let { "\n（宿主侧 C ABI 探测失败原因：$it）" } ?: "")

/** 原生构建 + 适配器 JAR（`nativeFactoryClass`）时的原因。 */
private fun nativeAdapterJarUnavailable(manifest: ModuleManifest): String =
    "当前是原生构建（GraalVM Native Image），无法加载带适配器 JAR 的音源模块「${manifest.name}」：" +
        "原生构建不支持运行时从 JAR 加载类。请改用直接导出 C ABI 的原生库，或 JS 音源模块。"

/** Keep the adapter and its files alive until cancelled calls have drained and closed. */
internal class NativePackageRuntime(
    /** 包的运行时资源：JNI 路径是类加载器，C ABI 路径是库句柄持有者；都没有时为 null。 */
    private val resources: java.io.Closeable?,
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
        try { resources?.close() } catch (error: Throwable) { failure = error }
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
