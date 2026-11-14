package cp.player.core.provider.typed

import cp.player.sdk.ProviderContext
import cp.player.sdk.jni.JniProviderSession
import java.io.Closeable
import java.io.File

/**
 * 原生音源包的 **C ABI**（`cpp_*`）会话工厂 —— 规范见 `docs/dev/PROVIDER_NATIVE_ABI.md`。
 *
 * 存在的理由：JNI 契约要求「每个包独立拿到 JNI 绑定」，宿主的做法是把
 * [cp.player.sdk.jni.NativeSession] 按包 `defineClass` 一次；而 GraalVM Native Image
 * 既没有类文件资源、也不支持运行时定义类，于是那条路在原生构建里彻底走不通。
 * C ABI 直接按符号名调用导出函数：每包一个库句柄，绑定天然独立，JVM 与原生镜像同一套代码。
 *
 * 库没有导出 `cpp_abi_version`（或版本不为 1）时返回 `null`，调用方回落到 JNI 路径。
 */
internal interface CAbiSessionFactory : Closeable {
    /**
     * 打开一个会话。失败抛 [IllegalStateException]，消息取自库回填的 `err` 缓冲。
     * 生命周期与 [NativePackageRuntime] 一致：会话关闭后才 [close] 工厂（释放库句柄）。
     */
    fun open(context: ProviderContext, settingsJson: String): JniProviderSession
}

/**
 * 探测并打开 [library] 的 C ABI。`null` = 这个库不提供 C ABI（也包含「加载失败」，
 * 由调用方在 JVM 上回落 JNI；原生构建则给出明确错误）。
 *
 * Android 侧恒为 `null`：ART 没有 `java.lang.foreign`，Android 继续走 JNI。
 */
internal expect fun cAbiSessionFactory(library: File): CAbiSessionFactory?
