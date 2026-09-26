package cp.player.core.integration

/**
 * 对外播控的**收窄**接口。
 *
 * ### 为什么不直接暴露 `PlaybackController`
 * 1. `PlaybackController` 的方法集是**按播放器内部需要**长出来的（seek、切 provider、
 *    队列增删改……）。直接暴露等于让「对外能做什么」被上游接口的形状决定，
 *    而且每加一个内部方法就静默多出一项对外能力。
 * 2. 它有 suspend / 非 suspend 混用与 god class 问题（见 `docs/ARCHITECTURE.md`），
 *    直接暴露会把这些坏味道外溢成**对外契约**，之后就再也改不动了。
 *
 * 收窄之后，「对外有哪些写操作」是一个可以在测试里逐条钉住的短清单 ——
 * [PlaybackAction.SUPPORTED] 与本接口的方法一一对应。
 *
 * ### 线程约定
 * 实现**可以假定**自己被调用在控制线程上（见 [IntegrationService] 的写路径）。
 * 所以实现里不需要再做线程切换 —— 切换是调用方的责任，而且只切一次。
 *
 * ### 刻意不做的动作
 * `stop` 与 `seek` 等不在此接口里，原因见 [PlaybackAction.DELIBERATELY_ABSENT]。
 */
interface IntegrationPlaybackControl {

    /** 继续播放。 */
    suspend fun play()

    /** 暂停。 */
    suspend fun pause()

    /** 下一首。 */
    suspend fun next()

    /** 上一首。 */
    suspend fun previous()
}
