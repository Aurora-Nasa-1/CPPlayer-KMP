package cp.player.app.monitoring

import io.sentry.Sentry
import io.sentry.SentryOptions
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 桌面端 (Compose Desktop) Sentry 异常监控与保护管理器
 *
 * 核心机制：
 * 1. 本地小时窗口频率限制（每台设备每小时总上限与单错误上限，防刷死免费配额）
 * 2. Debug / 开发环境自动静默
 * 3. 旧版包拦截：低于最低支持版本的客户端在本地直接丢弃
 * 4. 常见连锁/预期内异常过滤（CancellationException / 网络抖动聚合）
 */
object DesktopMonitoring {

    /** 最低支持上报的客户端版本。低于此版本的旧客户端报错在本地直接抛弃，不发网络。 */
    private const val MIN_SUPPORTED_REPORT_VERSION = "1.0.0"

    private const val MAX_TOTAL_PER_HOUR = 8        // 每台设备每自然小时总上限
    private const val MAX_SAME_ERROR_PER_HOUR = 2    // 相同错误每自然小时上限

    @Volatile
    private var currentHourEpoch = currentHour()
    private val totalCounter = AtomicInteger(0)
    private val errorTypeCounter = ConcurrentHashMap<String, Int>()

    private fun currentHour(): Long = System.currentTimeMillis() / (1000 * 3600)

    @Synchronized
    private fun tryAcquireRateLimit(errorKey: String): Boolean {
        val now = currentHour()
        if (now != currentHourEpoch) {
            currentHourEpoch = now
            totalCounter.set(0)
            errorTypeCounter.clear()
        }
        if (totalCounter.get() >= MAX_TOTAL_PER_HOUR) return false
        val count = errorTypeCounter.getOrDefault(errorKey, 0)
        if (count >= MAX_SAME_ERROR_PER_HOUR) return false

        totalCounter.incrementAndGet()
        errorTypeCounter[errorKey] = count + 1
        return true
    }

    /** 比较语义版本号：若 v1 < v2 返回负数，v1 == v2 返回 0，v1 > v2 返回正数 */
    private fun compareSemVer(v1: String, v2: String): Int {
        val p1 = v1.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
        val p2 = v2.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(p1.size, p2.size)
        for (i in 0 until maxLen) {
            val num1 = p1.getOrElse(i) { 0 }
            val num2 = p2.getOrElse(i) { 0 }
            if (num1 != num2) return num1.compareTo(num2)
        }
        return 0
    }

    /** 读取 DSN：环境变量优先，其次当前工作目录的 local.properties */
    private fun resolveDsn(): String {
        System.getenv("SENTRY_DSN")?.takeIf { it.isNotBlank() }?.let { return it }
        val localFile = File("local.properties")
        if (localFile.exists()) {
            runCatching {
                Properties().apply {
                    localFile.inputStream().use { load(it) }
                }.getProperty("sentry.dsn")
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    fun init(versionName: String, isDebug: Boolean = false) {
        // 1. Debug 模式拦截
        if (isDebug || System.getProperty("app.debug") == "true") {
            return
        }

        // 2. 旧版包硬闸门拦截：如果当前客户端版本低于最低支持版本，不初始化 Sentry
        if (compareSemVer(versionName, MIN_SUPPORTED_REPORT_VERSION) < 0) {
            return
        }

        val dsn = resolveDsn()
        if (dsn.isBlank()) {
            return
        }

        Sentry.init { options: SentryOptions ->
            options.dsn = dsn
            options.environment = "desktop-production"
            options.release = "cp.player@$versionName"
            options.setTag("platform", "desktop")
            options.setTag("os", System.getProperty("os.name") ?: "unknown")

            // 免费版关闭全量性能打桩
            options.tracesSampleRate = 0.0

            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                val ex = event.throwable

                // 过滤协程取消
                if (ex is kotlinx.coroutines.CancellationException) {
                    return@BeforeSendCallback null
                }

                // 连锁网络异常聚合统一指纹
                if (ex is java.net.SocketTimeoutException ||
                    ex is java.net.UnknownHostException ||
                    ex is java.io.IOException) {
                    event.fingerprints = listOf("desktop-network-connectivity-issue")
                }

                // 本地小时频率限制
                val errorKey = ex?.javaClass?.name ?: (event.message?.formatted ?: "unknown")
                if (!tryAcquireRateLimit(errorKey)) {
                    return@BeforeSendCallback null
                }

                event
            }
        }

        // 注册未捕获异常兜底
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Sentry.captureException(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun captureException(throwable: Throwable) {
        Sentry.captureException(throwable)
    }
}
