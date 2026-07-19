package cp.player.kmp

import cp.player.kmp.provider.BackendProvider

/**
 * 后端状态机。
 *
 * 描述 [MusicBackend] 从未初始化到就绪（或错误）的生命周期。
 * 通过 [MusicBackend.stateFlow] 暴露为 [StateFlow]，UI 可观察瞬态切换。
 *
 * ### 状态转移图
 * ```
 * Uninitialized ──init()──► Initializing
 *   Initializing ┌─ 有 Provider 且激活成功 ──► Ready(provider)
 *                ├─ 无 Provider               ──► NoProvider
 *                └─ 初始化异常                ──► Error(message)
 *   NoProvider ──importModule() 成功并自动激活──► Ready(provider)
 *   NoProvider ──importModule() 成功但无可用──► NoProvider（保持）
 *   Ready ──switchProvider()──► Ready(newProvider)
 *   Ready ──deleteModule() 删除最后一个──► NoProvider
 *   Ready ──switchProvider() 失败──► Ready(old)（保持不变，返回 Error）
 *   任意 ──reset()──► Uninitialized
 * ```
 */
sealed class BackendState {

    /** 尚未调用 [MusicBackend.init]。 */
    object Uninitialized : BackendState()

    /** 正在初始化（扫描模块、恢复上次 Provider）。此状态短暂且同步。 */
    object Initializing : BackendState()

    /**
     * 已初始化但没有已加载的 Provider 模块。
     * UI 应引导用户导入模块（Setup 页面）。
     */
    object NoProvider : BackendState()

    /**
     * 就绪：有活跃的 Provider 正在提供服务。
     * @param provider 当前活跃 Provider
     */
    data class Ready(val provider: BackendProvider) : BackendState()

    /**
     * 初始化失败。
     * 与 [NoProvider] 区分：此状态表示初始化过程本身抛出异常（如 I/O 错误），
     * 而非简单的"没有模块"。
     */
    data class Error(val message: String) : BackendState()

    /** 便捷判断：是否已就绪可调用 API。 */
    val isReady: Boolean get() = this is Ready

    /** 便捷判断：当前活跃 Provider（就绪时非 null）。 */
    val activeProvider: BackendProvider? get() = (this as? Ready)?.provider
}

/**
 * 后端操作结果的类型安全封装。
 *
 * 所有 [MusicBackend] 的可失败操作返回此类型，替代裸 String / null 约定，
 * 使错误路径在编译期可见、UI 侧可穷举处理。
 *
 * @param T 成功时携带的数据类型
 */
sealed class BackendResult<out T> {

    /** 操作成功。 */
    data class Success<T>(val data: T) : BackendResult<T>()

    /**
     * 操作失败（网络错误、Provider 不就绪、解析异常等）。
     * @param message 人类可读错误描述（可直接展示给用户）
     * @param code 错误码（如 HTTP code、API code 或 null）
     * @param cause 原始异常（仅用于日志/调试，不应展示给用户）
     */
    data class Error(
        val message: String,
        val code: Int? = null,
        val cause: Throwable? = null
    ) : BackendResult<Nothing>()

    /**
     * 当前音源不支持该功能（[BackendProvider.apiMap] 映射为 "unsupported"）。
     * 与 [Error] 区分：这并非系统故障，而是功能缺失，
     * UI 应提示"该音源不支持此功能"而非"出错了"。
     */
    data class Unsupported(val message: String) : BackendResult<Nothing>()

    /** 便捷：是否成功。 */
    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    val isUnsupported: Boolean get() = this is Unsupported

    /** 成功时取出数据，否则 null。 */
    fun getOrNull(): T? = (this as? Success)?.data

    /** 成功时取出数据，否则抛出。 */
    fun getOrThrow(): T = (this as Success).data

    /** 成功时取出数据，否则 [default]。 */
    fun getOrDefault(default: @UnsafeVariance T): T = (this as? Success)?.data ?: default

    /** 模式匹配快捷入口。 */
    inline fun <R> fold(
        onSuccess: (T) -> R,
        onError: (String, Int?, Throwable?) -> R,
        onUnsupported: (String) -> R,
    ): R = when (this) {
        is Success -> onSuccess(data)
        is Error -> onError(message, code, cause)
        is Unsupported -> onUnsupported(message)
    }
}

/**
 * 模块导入结果。
 */
sealed class ImportResult {

    /** 导入并已自动激活（此前无活跃 Provider）。 */
    data class Activated(val provider: BackendProvider) : ImportResult()

    /** 导入成功，但已有其他 Provider 处于活跃状态，未切换。 */
    data class Loaded(val provider: BackendProvider) : ImportResult()

    /** 导入失败。 */
    data class Failed(val message: String) : ImportResult()
}