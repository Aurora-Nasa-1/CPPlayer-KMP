/*
 * Ported from Halcyon — Apache-2.0.
 * Upstream reference: app/src/main/java/com/ella/music/player/SuperLyricBridge.kt
 *          （`superLyricRetryDelayMs`，230-237 行）
 * Changes: 从 bridge 的内部函数提成 commonMain 的顶层函数，让它可以在 desktopTest 里
 *          直接单测（androidMain 的代码在本仓库测不到）；退避曲线与封顶值原样保留 ——
 *          30s 起步、5 分钟封顶是 Halcyon 实测出来的经验值。
 * See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricpush

/** 首次失败后的退避起点。 */
internal const val SUPER_LYRIC_INITIAL_RETRY_MS = 30_000L

/** 退避上限：再失败也不超过 5 分钟，否则用户手动打开 SuperLyric 后要等太久才生效。 */
internal const val SUPER_LYRIC_MAX_RETRY_MS = 5 * 60_000L

/** 退避指数的最大档位（0..4 ⇒ 1x..16x）。 */
private const val SUPER_LYRIC_MAX_EXPONENT = 4

/**
 * 第 [failureCount] 次失败后应等待多久再重试注册 SuperLyric 发布者。
 *
 * 指数退避 + 封顶：`30s → 30s → 60s → 120s → 240s → 300s → 300s …`
 *
 * 为什么要退避：注册失败通常意味着**系统里没有 SuperLyric 接收方**（未安装 / 未激活），
 * 而注册调用本身是一次跨进程广播。每帧重试会把「没有接收方」变成持续的广播风暴。
 */
internal fun superLyricRetryDelayMs(failureCount: Int): Long {
    val exponent = (failureCount.coerceAtLeast(1) - 1).coerceAtMost(SUPER_LYRIC_MAX_EXPONENT)
    return (SUPER_LYRIC_INITIAL_RETRY_MS * (1L shl exponent))
        .coerceAtMost(SUPER_LYRIC_MAX_RETRY_MS)
}
