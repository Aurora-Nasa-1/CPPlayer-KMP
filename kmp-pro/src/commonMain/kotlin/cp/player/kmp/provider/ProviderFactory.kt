package cp.player.kmp.provider

/**
 * 根据模块 manifest 与解压目录构造 [BackendProvider]。
 *
 * commonMain 声明 expect；jvmMain 提供 actual，统一负责 `http`/`binary` 类型，
 * `jni` 类型经平台通过 [createJniProvider] 期望函数解析：
 * - Android：返回 [JniProvider]（androidMain）
 * - Desktop：返回 [JniProvider]（desktopMain，支持 Win/Linux/Mac）
 */
expect object ProviderFactory {
    /**
     * 创建 Provider。
     *
     * @param manifest 模块清单
     * @param moduleDir 模块解压根目录绝对路径
     * @return 就绪的 [BackendProvider]，或该类型在当前平台不支持时返回 null
     */
    fun create(manifest: ModuleManifest, moduleDir: String): BackendProvider?
}

/**
 * 创建 JNI Provider（平台期望）。
 *
 * @param manifest 模块清单
 * @param soPath 已解析的 .so/.dll/.dylib 绝对路径
 * @return 所有 JVM 平台返回 [JniProvider]
 */
expect fun createJniProvider(manifest: ModuleManifest, soPath: String): BackendProvider?