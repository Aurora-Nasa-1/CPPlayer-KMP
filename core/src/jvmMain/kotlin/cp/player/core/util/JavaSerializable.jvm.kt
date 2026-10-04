package cp.player.core.util

/**
 * JVM 实际类型 = [java.io.Serializable]（Android 与 desktop 共享 jvmMain）。
 * 语义与用途见 commonMain 的 [JavaSerializable]。
 */
actual typealias JavaSerializable = java.io.Serializable
