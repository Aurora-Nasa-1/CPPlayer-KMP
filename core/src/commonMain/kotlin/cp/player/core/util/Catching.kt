package cp.player.core.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] 的**取消感知**版本：捕获普通异常，[CancellationException] 原样上抛。
 *
 * 标准库 `runCatching` 会把取消也当成失败吞掉 —— 协程里被 cancel 的调用会
 * 「伪装成网络失败」继续走回退分支：快速切歌时旧请求不中断、反而再发一次
 * 请求（CODE_REVIEW B5 的 AMLL 取词就是这种形态），同时破坏结构化并发。
 *
 * 规则（同 [cp.player.core.cache.CachedMusicApiService] 的既有约定）：
 * **凡是可能运行在可取消协程里的 `runCatching`，包网络/挂起调用时一律用本函数。**
 */
inline fun <T> runCatchingExceptCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
