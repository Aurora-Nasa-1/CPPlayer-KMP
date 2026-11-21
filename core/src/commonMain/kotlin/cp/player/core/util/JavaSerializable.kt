package cp.player.core.util

/**
 * JVM 侧 [java.io.Serializable] 的公共桥（expect/actual，实际类型即 `java.io.Serializable`）。
 *
 * 为什么需要它：Android 应用的导航返回栈通过 Java 序列化保存状态（锁屏 / 切后台触发
 * `onSaveInstanceState`）。导航条目携带的领域模型（`TrackSummary` / `PlaylistSummary` …）
 * 因此需要实现 `java.io.Serializable`，否则保存状态时可能抛出 `NotSerializableException`。
 *
 * `commonMain` 引用不到 `java.io.Serializable`，所以走 expect/actual：
 * - 本文件（commonMain）：`expect interface`；
 * - `jvmMain`（Android 与 desktop 共享）：`actual typealias = java.io.Serializable`。
 *
 * 领域模型实现它后，桌面端（同为 JVM）也会顺带可序列化。非 JVM 目标（若有）该接口只是
 * 空标记，不会将 JVM 类型暴露到公共构造参数中。
 */
expect interface JavaSerializable
