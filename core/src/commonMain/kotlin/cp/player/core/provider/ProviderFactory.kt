package cp.player.core.provider

/**
 * 根据模块 manifest 与解压目录构造 [BackendProvider]。
 *
 * commonMain 声明 expect；jvmMain 只加载 KMP SDK JavaScript 包。
 * Kotlin 与原生 worker 由宿主显式注册，不从 manifest 获得进程内信任。
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
