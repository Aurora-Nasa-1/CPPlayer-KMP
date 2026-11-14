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
        // C ABI **只在原生构建里探测**：
        // - 原生构建：JNI 要按包 defineClass，native image 做不到 ⇒ C ABI 是唯一可行路径；
        // - JVM / Android：继续走原 JNI 路径（线上跑了几年的那条），行为一点不变；
        //   这里也不去 `libraryLookup`，免得白白把 DLL 加载进来钉住快照目录。
        var cAbiFailure: String? = null
        val cAbi = if (className == null && nativeImageRuntime) {
            when (val probe = probeCAbiSessionFactory(entry)) {
                is CAbiProbe.Available -> probe.factory
                is CAbiProbe.Unavailable -> {
                    cAbiFailure = probe.reason
                    null
                }
            }
        } else null
        val packageLoader: ClassLoader? = when {
            cAbi != null -> null
            className == null -> nativeLibraryClassLoader(context)
            else -> nativePackageClassLoader(entry, libraryDirectory)
        }
        closable = cAbi ?: (packageLoader as? java.io.Closeable)
        val factory: (ProviderContext) -> JniProviderSession = if (cAbi != null) {
            { sessionContext -> cAbi.open(sessionContext, Json.encodeToString(sessionContext.settings)) }
        } else if (className == null) {
            // 没有 C ABI ⇒ 只能用「按包 defineClass 一份 NativeSession」的 JNI 旁路，
            // 而 native image 既没有类文件资源、也不支持运行时定义类（见 NativePackageClassLoader）。
            check(!nativeImageRuntime) { nativeJniUnavailable(manifest, cAbiFailure) }
            val bridge = packageLoader!!.loadClass(NativeSession::class.java.name)
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
            // 自定义适配器 JAR 同样依赖运行时类加载，原生构建里没有可行路径。
            check(!nativeImageRuntime) { nativeAdapterJarUnavailable(manifest) }
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
        runCatching { closable?.close() }
        snapshot.deleteRecursively()
        throw IllegalArgumentException("Cannot load native SDK package: ${error.message}", error)
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
