package cp.player.kmp.download

/**
 * 下载失败（HTTP 错误、IO 错误、取链失败等统一封装）。
 */
class DownloadFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 服务端不支持断点续传：请求携带 Range 头但响应为 200（非 206）。
 * 调用方应清空 .part 文件后从头重下。
 */
class ResumeNotSupportedException(
    message: String = "服务端不支持断点续传"
) : Exception(message)

/**
 * 下载执行器抽象（平台实现：jvmMain 的 JvmDownloadExecutor）。
 *
 * 职责单一：按给定 URL 流式下载并追加写入 .part 文件，逐块回调进度；
 * 取链、重试、状态机、文件命名等编排逻辑全部由 DownloadEngine 负责。
 *
 * 取消信号：依赖结构化协程取消（调用方 cancel 任务 Job 即中断下载，
 * 实现须在挂起点响应 CancellationException，已写入的 .part 保留供续传）。
 */
interface DownloadExecutor {

    /**
     * 执行一次下载（流式分块写盘）。
     *
     * @param url 直链地址
     * @param headers 额外请求头（如 Range / Cookie，由编排层注入）
     * @param targetPartPath 临时文件（.part）绝对路径
     * @param resumeBytes 断点续传起始字节（>0 时实现应附加 Range 头；
     *                    若服务端返回 200 而非 206，抛出 [ResumeNotSupportedException]）
     * @param onProgress 进度回调：本次会话已写入字节数（节流由调用方负责）
     * @return 本次会话写入的字节数
     * @throws DownloadFailedException HTTP/IO 失败
     * @throws ResumeNotSupportedException 服务端忽略 Range 请求
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        targetPartPath: String,
        resumeBytes: Long,
        onProgress: suspend (sessionWrittenBytes: Long) -> Unit
    ): Long
}

/**
 * 平台默认的 [DownloadExecutor] 工厂。
 * JVM（Android + Desktop）由 jvmMain 提供 actual（独立 Ktor/OkHttp 客户端）。
 */
expect fun defaultDownloadExecutor(): DownloadExecutor
