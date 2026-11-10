package cp.player.core.provider

/**
 * 根据模块 manifest 与解压目录构造 [BackendProvider]。
 *
 * commonMain 声明 expect；jvmMain 加载 KMP SDK JavaScript 或 JNI 包。
 * JNI 包使用宿主统一入口或自带适配器，不要求宿主预先注册。
 */
expect object ProviderFactory {
    /**
     * 创建 Provider。
     *
     * @param manifest 模块清单
     * @param moduleDir 模块解压根目录绝对路径
     * @return 就绪的 [BackendProvider]，或该类型在当前平台不支持时返回 null
     */
    fun create(
        manifest: ModuleManifest,
        moduleDir: String,
        context: cp.player.core.util.PlatformContext? = null,
    ): BackendProvider?
}
