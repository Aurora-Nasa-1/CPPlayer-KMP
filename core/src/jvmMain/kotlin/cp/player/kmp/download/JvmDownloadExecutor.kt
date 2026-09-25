package cp.player.kmp.download

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * JVM 下载执行器（Android 与 Desktop 共用）。
 *
 * 使用**独立的** Ktor HttpClient（OkHttp 引擎），与 [cp.player.kmp.util.createHttpClient]
 * 完全隔离：
 * - **不设 requestTimeout**：大文件下载整体耗时不设上限；
 * - socket（读写空闲）超时 5 分钟：仅防御连接彻底僵死；
 * - connectTimeout 30 秒。
 *
 * 流式分块写盘：`bodyAsChannel()` 逐块读 → RandomAccessFile 追加写 →
 * 逐块回调 [DownloadExecutor.download] 的 onProgress（节流由调用方负责）。
 *
 * 取消：响应协程取消（readAvailable 挂起点天然支持），中断后 .part 保留。
 */
class JvmDownloadExecutor : DownloadExecutor {

    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(5, TimeUnit.MINUTES)
                    writeTimeout(5, TimeUnit.MINUTES)
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 300_000
                // 刻意不设 requestTimeoutMillis：下载整体耗时不设上限
            }
        }
    }

    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        targetPartPath: String,
        resumeBytes: Long,
        onProgress: suspend (sessionWrittenBytes: Long) -> Unit
    ): Long {
        val partFile = File(targetPartPath)
        partFile.parentFile?.mkdirs()

        val statement = client.prepareGet(url) {
            headers.forEach { (key, value) -> header(key, value) }
        }
        return statement.execute { resp ->
            when {
                // 携带 Range 但服务端返回 200 → 不支持续传，交由编排层清空重下
                resumeBytes > 0L && resp.status == HttpStatusCode.OK ->
                    throw ResumeNotSupportedException()
                // 残留 .part 大于服务端文件 → Range 不可满足（416），同样走
                // 编排层「清空 .part 重下」自愈逻辑，不归入通用失败永久 FAILED
                resumeBytes > 0L && resp.status.value == 416 ->
                    throw ResumeNotSupportedException("续传偏移超出文件大小（HTTP 416），清空重下")
                !resp.status.isSuccess() ->
                    throw DownloadFailedException("HTTP ${resp.status.value}")
                else -> writeBody(resp, partFile, resumeBytes, onProgress)
            }
        }
    }

    private suspend fun writeBody(
        response: HttpResponse,
        partFile: File,
        resumeBytes: Long,
        onProgress: suspend (Long) -> Unit
    ): Long {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L
        RandomAccessFile(partFile, "rw").use { raf ->
            if (resumeBytes > 0L) {
                raf.seek(resumeBytes)
            } else {
                raf.setLength(0L)
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(buffer)
                if (read < 0) break
                raf.write(buffer, 0, read)
                written += read
                onProgress(written)
            }
        }
        return written
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}

/** JVM（Android + Desktop）默认下载执行器。 */
actual fun defaultDownloadExecutor(): DownloadExecutor = JvmDownloadExecutor()
