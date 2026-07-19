package cp.player.kmp.util

/**
 * 平台上下文抽象（不透明包装）。
 *
 * - Android：内部持有 `android.content.Context`
 * - Desktop：无等价物（占位）
 *
 * 通过 [androidContext] / 工厂在平台源集中访问真实上下文。
 */
expect class PlatformContext {
    /** 平台原始对象引用（Android: Context；Desktop: null） */
    val raw: Any?
}