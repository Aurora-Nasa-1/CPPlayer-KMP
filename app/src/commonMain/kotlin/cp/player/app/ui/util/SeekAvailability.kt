package cp.player.app.ui.util

/**
 * 进度条「此刻能不能拖」的**唯一**判定，以及时长未知时该怎么显示。
 *
 * ### 为什么抽出来
 * 原先三处各写一遍 `duration > 0`：两处滑条（[cp.player.app.ui.screen.PlayerScreen] 的
 * ProgressRow、[cp.player.app.ui.screen.DesktopPlayerScreen]）与桌面快捷键 `seekBy`。
 * 同构判定的代价是改一处忘一处，症状只在某个入口复现——见到同构就该抽。
 *
 * ### 为什么时长未知时要禁用，而不是给个兜底范围
 * 滑条位置必须换算成**绝对时间**才能交给引擎。时长未知时这个换算不存在，
 * 随便挑一个范围（例如 0..1h）只会把用户拖出的比例变成一个**错误的绝对位置**。
 * 真要在时长未知时支持拖动，只能是**相对定位**（按 delta 跳），那是另一件事。
 *
 * 所以这里保持禁用——但必须把「为什么不能拖」明确告诉用户。
 * **静默失效才是真正的 bug**：用户分不清「我拖错了」和「这个音源拖不了」，
 * 观感就是「拖了没反应」。
 */
object SeekAvailability {

    /** 时长未知时进度条右侧显示的内容。 */
    const val UNKNOWN_DURATION_LABEL = "--:--"

    /** 时长未知时的说明文案。 */
    const val UNKNOWN_DURATION_HINT = "时长未知，暂不支持拖动进度"

    /**
     * 无损档位「后台落盘」期间的说明文案。
     *
     * 桌面引擎无法定位 FLAC over HTTP，所以无损曲必须先落盘才能拖（见 `StreamLocalizer`）。
     * 落盘**不阻塞播放**（曲子已经在出声了），但拖动在这段时间里确实不可用 ——
     * 这段限制**必须说明原因**，否则用户拖动没反应只会以为播放器坏了。
     */
    const val LOSSLESS_CACHING_HINT = "正在缓存无损音质，完成后可拖动进度…"

    /**
     * 进度条此刻是否可拖动。
     *
     * @param durationMs 有效时长。注意传入的应是 [cp.player.core.playback.PlaybackUiState.durationMs]：
     *   它已经由控制器回落过曲目元信息，为 0 表示**引擎与元信息都不知道时长**。
     * @param isLocalizing 无损曲是否还在后台落盘。此时引擎放的是**不可定位的流**，
     *   拖了也不会动，所以一律禁用 —— 而不是放行让用户拖了个寂寞。
     *   落盘完成（或命中缓存）后即为 false，拖动会触发切换到本地副本。
     */
    fun isSeekable(durationMs: Long, isLocalizing: Boolean = false): Boolean =
        durationMs > 0L && !isLocalizing

    /**
     * 滑条被禁用时该显示哪条理由；可拖动时返回 `null`。
     *
     * **一次只呈现一条**：两种原因可能同时成立（正在落盘的无损曲，时长也还没解析出来），
     * 同时挂两条互相矛盾的说法比不解释还糟。落盘优先 —— 它更具体、也有明确的结束时刻。
     *
     * 抽在这里而不是各 composable 各写一遍 `if`：两处滑条的呈现必须始终一致，
     * 同构的判定迟早会漂移（见 [SeekAvailability] 的类注释）。
     */
    fun disabledReason(durationMs: Long, isLocalizing: Boolean): String? = when {
        isLocalizing -> LOSSLESS_CACHING_HINT
        durationMs <= 0L -> UNKNOWN_DURATION_HINT
        else -> null
    }

    /**
     * 进度条右侧的时长标签。
     *
     * ⚠️ 不能直接用 [formatTimeMs]：它把 0 显示成 `0:00`，读起来像「这首歌是零长度」，
     * 而不是「还不知道」——这正是时长未知时最误导用户的地方。
     * 当前位置仍应继续用 [formatTimeMs]（位置为 0 时 `0:00` 是正确的）。
     */
    fun durationLabel(durationMs: Long): String =
        if (isSeekable(durationMs)) formatTimeMs(durationMs) else UNKNOWN_DURATION_LABEL
}
