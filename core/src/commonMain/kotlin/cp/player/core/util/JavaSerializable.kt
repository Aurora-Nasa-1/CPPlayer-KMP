package cp.player.core.util

/**
 * JVM 侧 [java.io.Serializable] 的公共桥（expect/actual，实际类型即 `java.io.Serializable`）。
 *
 * 为什么需要它：Android 上 Voyager 用 **Java 序列化**保存返回栈（锁屏 / 切后台触发
 * `onSaveInstanceState`）。任何被 `push` 进 Navigator 的 `Screen`，其构造参数里的
 * 领域模型（`TrackSummary` / `PlaylistSummary` …）都必须实现 `java.io.Serializable`，
 * 否则锁屏瞬间抛 `NotSerializableException`（实锤：`HomeGeneratedPlaylistScreen`
 * 携带日推曲目列表，锁屏即崩）。
 *
 * `commonMain` 引用不到 `java.io.Serializable`，所以走 expect/actual：
 * - 本文件（commonMain）：`expect interface`；
 * - `jvmMain`（Android 与 desktop 共享）：`actual typealias = java.io.Serializable`。
 *
 * 领域模型实现它后，桌面端（同为 JVM）也会顺带可序列化 —— 无副作用。
 * 非 JVM 目标（若有）该接口只是个空标记。
 */
expect interface JavaSerializable
