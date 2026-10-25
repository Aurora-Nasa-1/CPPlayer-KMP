package cp.player.app.monitoring

import android.content.Context
import cp.player.app.BuildConfig
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sentry 崩溃与异常监控管理
 *
 * 包含：
 * 1. 本地小时窗口限流（防止单设备死循环崩溃刷爆免费配额）
 * 2. 同类异常频率限制
 * 3. Debug / 本地开发 环境静默屏蔽
 * 4. 常见连锁/非致命异常过滤与聚合（Fingerprint）
 */
object AppMonitoring {

    /**
     * DSN 由 Gradle 通过 BuildConfig 注入（优先读取环境变量 SENTRY_DSN，
     * 其次读取 local.properties 中的 sentry.dsn）。
     * 若未配置（开源构建或本地默认），则为空串，监控不会启动。
     */
    private val SENTRY_DSN: String = BuildConfig.SENTRY_DSN

    // --- 本地小时限流配置 ---
    private const val MAX_TOTAL_PER_HOUR = 8        // 每台设备每自然小时总上报上限
    private const val MAX_SAME_ERROR_PER_HOUR = 2    // 相同异常每自然小时上限

    /** 最低支持上报的版本号。老于此版本的旧包崩溃直接在本地丢弃，不发往网络，不耗费配额。 */
    private const val MIN_SUPPORTED_REPORT_VERSION = "1.0.0"

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

    fun init(context: Context, forceEnable: Boolean = false) {
        // 1. 默认 Debug 模式或未配置 DSN 时不启动，避免开发阶段消耗免费配额
        if ((BuildConfig.DEBUG && !forceEnable) || SENTRY_DSN.isBlank()) {
            return
        }

        // 2. 旧版包拦截：低于最低支持版本的客户端在本地直接丢弃
        if (compareSemVer(BuildConfig.VERSION_NAME, MIN_SUPPORTED_REPORT_VERSION) < 0) {
            return
        }

        SentryAndroid.init(context) { options ->
            options.dsn = SENTRY_DSN
            options.environment = if (BuildConfig.DEBUG) "debug" else "production"
            options.release = "cp.player@${BuildConfig.VERSION_NAME}"
            options.dist = BuildConfig.VERSION_CODE.toString()

            // 针对免费版：关闭全量性能追踪事务，避免短时间刷满 10,000 次 Performance 额度
            options.tracesSampleRate = 0.0

            // 核心拦截钩子：在本地内存丢弃不需要的事件，不发网络请求
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                val ex = event.throwable

                // 2. 协程取消或预期内中断直接抛弃
                if (ex is kotlinx.coroutines.CancellationException) {
                    return@BeforeSendCallback null
                }

                // 3. 网络连锁异常聚合：将连接超时、断网等收敛为统一 Issue，防止分散刷屏
                if (ex is java.net.SocketTimeoutException ||
                    ex is java.net.UnknownHostException ||
                    ex is java.io.IOException) {
                    event.fingerprints = listOf("network-connectivity-issue")
                }

                // 4. 本地小时窗口限流判断
                val errorKey = ex?.javaClass?.name ?: (event.message?.formatted ?: "unknown")
                if (!tryAcquireRateLimit(errorKey)) {
                    return@BeforeSendCallback null
                }

                event
            }
        }
    }

    fun captureException(throwable: Throwable) {
        io.sentry.Sentry.captureException(throwable)
    }

    fun captureMessage(message: String) {
        io.sentry.Sentry.captureMessage(message)
    }
}
