package cp.player.core.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import cp.player.core.util.PlatformContext
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 独立的 JNI Worker 进程宿主（仅限 Desktop）。
 *
 * 在宿主进程之外运行，实现 100% 隔离：
 * 1. 每个 Worker 进程只加载单一 .dll / .so 动态库，彻底解决 HotSpot JVM 符号冲突。
 * 2. 宿主切换 Provider 时强杀该进程，OS 立即卸载动态库并释放文件锁与端口。
 * 3. 动态库内部如果崩溃，不会击垮主 App 进程。
 */
object JniWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        var dllPath: String? = null
        var port = 3000
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--dll" -> {
                    dllPath = args.getOrNull(i + 1)
                    i += 2
                }
                "--port" -> {
                    port = args.getOrNull(i + 1)?.toIntOrNull() ?: 3000
                    i += 2
                }
                else -> i++
            }
        }
        if (dllPath == null) {
            System.err.println("[JniWorker] 缺少参数: --dll")
            System.exit(1)
            return
        }

        val file = File(dllPath)
        if (!file.exists()) {
            System.err.println("[JniWorker] SO/DLL 文件不存在: $dllPath")
            System.exit(2)
            return
        }

        val provider = JniProvider(
            id = "worker",
            name = "worker",
            version = "1.0.0",
            soPath = file.absolutePath,
        )

        try {
            provider.startServer(PlatformContext.EMPTY, port)
        } catch (e: Throwable) {
            System.err.println("[JniWorker] provider.startServer 异常: ${e.message}")
        }

        // 检查端口是否已被 native startNativeServer 监听
        val isNativeServerListening = try {
            java.net.Socket("127.0.0.1", port).use { true }
        } catch (e: Throwable) {
            false
        }

        if (!isNativeServerListening) {
            try {
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
                server.createContext("/api/") { exchange ->
                    handleRequest(provider, exchange)
                }
                server.executor = java.util.concurrent.Executors.newCachedThreadPool()
                server.start()
            } catch (e: Throwable) {
                System.err.println("[JniWorker] HttpServer 启动失败 (port=$port): ${e.message}")
                System.exit(4)
                return
            }
        }

        println("[JniWorker] READY:$port")
        System.out.flush()

        // 保持主线程阻塞，直到父进程关闭输入管道或被强杀
        try {
            while (System.`in`.read() != -1) {
                // 阻塞等待
            }
        } catch (ignored: Throwable) {}
        System.exit(0)
    }

    private fun handleRequest(provider: JniProvider, exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path.removePrefix("/api/").removePrefix("/")
            val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val result = dispatchWorkerRequest(path, body, provider::callApiJson, provider::analyzeAudio)

            val bytes = result.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (e: Throwable) {
            val err = """{"code":500,"msg":"${e.message?.replace("\"", "\\\"")}"}"""
            val bytes = err.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(500, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}

internal fun dispatchWorkerRequest(
    path: String,
    body: String,
    callApi: (String, String) -> String,
    analyzeAudio: (String) -> String,
): String {
    if (path != "analyzeAudio") return callApi(path, body.ifBlank { "{}" })
    val params = Json.parseToJsonElement(body) as? JsonObject
    return analyzeAudio((params?.get("path") as? JsonPrimitive)?.content.orEmpty())
}
