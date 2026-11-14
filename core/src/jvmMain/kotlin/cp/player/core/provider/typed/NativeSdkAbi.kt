package cp.player.core.provider.typed

import cp.player.sdk.ProviderContext
import cp.player.sdk.jni.JniProviderSession
import java.io.Closeable
import java.io.File

/**
 * 原生音源包的 **C ABI**（`cpp_*`）会话工厂 —— 规范见 `docs/dev/providers/NATIVE.md`。
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
 * 探测并打开 [library] 的 C ABI。
 *
 * 返回 [CAbiProbe.Unavailable] 而不是简单 `null`：**探测失败的原因必须留得下来**。
 * 真实事故（2026-11-14，网易云 1.3.0 在 Windows 原生包里提示「没有导出 C ABI」）：
 * native image 要求**每个 FFM downcall 签名在构建期登记**（`reachability-metadata.json`
 * 的 `foreign.downcalls`），漏登记时 `downcallHandle` 抛
 * `MissingForeignRegistrationError`；而旧实现把它 `catch` 成 `null`，用户看到的却
 * 是「音源没导出 C ABI」——把库的问题指成了音源的问题，排查方向直接跑偏。
 *
 * Android 侧恒为 [CAbiProbe.Unavailable]：ART 没有 `java.lang.foreign`，继续走 JNI。
 */
internal expect fun probeCAbiSessionFactory(library: File): CAbiProbe

/** C ABI 探测结果。 */
internal sealed interface CAbiProbe {
    /** 探测通过：库导出 `cpp_abi_version == 1`，四个符号都绑上了。 */
    class Available(val factory: CAbiSessionFactory) : CAbiProbe

    /** 不提供 C ABI（含加载失败 / 缺符号 / 版本不符 / 构建期未登记 FFM 签名）。 */
    class Unavailable(val reason: String) : CAbiProbe
}

/**
 * 只要工厂、不关心原因时的便捷封装（JVM 回落路径与单测用）。
 * `null` = 这个库不提供 C ABI。
 */
internal fun cAbiSessionFactory(library: File): CAbiSessionFactory? =
    (probeCAbiSessionFactory(library) as? CAbiProbe.Available)?.factory
