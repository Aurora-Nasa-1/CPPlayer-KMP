package cp.player.app.monitoring

import cp.player.core.util.DesktopDataDir
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 桌面端 (Compose Desktop) Sentry 异常监控与保护管理器
 *
 * 核心机制：
 * 1. 本地小时窗口频率限制（每台设备每小时总上限与单错误上限，防刷死免费配额）
 * 2. Debug / 开发环境自动静默
 * 3. 旧版包拦截：低于最低支持版本的客户端在本地直接丢弃
 * 4. 常见连锁/预期内异常过滤（CancellationException / 网络抖动聚合）
 * 5. libsentry (Sentry Native) 原生动态库探测与 FFM (Project Panama) 零外部依赖原生崩溃捕获
 * 6. JVM 历史致命错误日志 (hs_err_pid*.log) 自动溯源上报
 */
object DesktopMonitoring {

    private val logger = Logger.getLogger("DesktopMonitoring")

    /** 最低支持上报的客户端版本。低于此版本的旧客户端报错在本地直接抛弃，不发网络。 */
    private const val MIN_SUPPORTED_REPORT_VERSION = "1.0.0"

    private const val MAX_TOTAL_PER_HOUR = 8        // 每台设备每自然小时总上限
    private const val MAX_SAME_ERROR_PER_HOUR = 2    // 相同错误每自然小时上限

    @Volatile
    private var currentHourEpoch = currentHour()
    private val totalCounter = AtomicInteger(0)
    private val errorTypeCounter = ConcurrentHashMap<String, Int>()

    @Volatile
    private var _isNativeAvailable = false
    val isNativeAvailable: Boolean get() = _isNativeAvailable

    @Volatile
    private var _nativeLibraryPath: String? = null
    val nativeLibraryPath: String? get() = _nativeLibraryPath

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

    /**
     * 探测桌面端 Sentry Native 动态库文件 (sentry.dll / libsentry.so / libsentry.dylib)
     */
    private fun findNativeLibrary(): File? {
        val os = System.getProperty("os.name", "").lowercase()
        val candidateNames = when {
            os.contains("win") -> listOf("sentry.dll", "libsentry.dll")
            os.contains("mac") -> listOf("libsentry.dylib", "sentry.dylib")
            else -> listOf("libsentry.so", "sentry.so")
        }

        val searchCandidates = linkedSetOf<File>()

        // (a) 当前正在运行的可执行程序（CPPlayer.exe）所在目录探测
        try {
            ProcessHandle.current().info().command().ifPresent { exePath ->
                val exeFile = File(exePath)
                val appRoot = exeFile.parentFile
                if (appRoot != null && appRoot.exists()) {
                    for (name in candidateNames) {
                        searchCandidates += File(appRoot, name)
                        searchCandidates += File(appRoot, "runtime/bin/$name")
                        searchCandidates += File(appRoot, "app/$name")
                        searchCandidates += File(appRoot, "lib/$name")
                        searchCandidates += File(appRoot, "lib/app/$name")
                        searchCandidates += File(appRoot, "lib/runtime/lib/$name")
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // (b) 当前代码包（JAR）位置推导安装根目录
        try {
            val codeSourceLoc = DesktopMonitoring::class.java.protectionDomain?.codeSource?.location
            if (codeSourceLoc != null) {
                val jarFile = File(codeSourceLoc.toURI())
                val jarDir = jarFile.parentFile
                if (jarDir != null && jarDir.exists()) {
                    for (name in candidateNames) {
                        searchCandidates += File(jarDir, name)
                    }
                    val appRoot = jarDir.parentFile
                    if (appRoot != null && appRoot.exists()) {
                        for (name in candidateNames) {
                            searchCandidates += File(appRoot, name)
                            searchCandidates += File(appRoot, "runtime/bin/$name")
                            searchCandidates += File(appRoot, "lib/$name")
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // (c) Compose 应用资源目录环境变量
        System.getProperty("compose.application.resources.dir")?.let { resDir ->
            for (name in candidateNames) {
                searchCandidates += File(resDir, name)
                searchCandidates += File(resDir, "../$name")
            }
        }

        // (d) 常规工作区与持久化目录
        val userDir = File(System.getProperty("user.dir"))
        for (name in candidateNames) {
            searchCandidates += DesktopDataDir.file("native/$name")
            searchCandidates += DesktopDataDir.file(name)
            searchCandidates += File(userDir, name)
            searchCandidates += File(userDir, "native/$name")
            searchCandidates += File(userDir, "../native/$name")
            searchCandidates += File(userDir, "app/native/$name")
            searchCandidates += File(userDir, "../app/native/$name")
        }

        return searchCandidates.firstOrNull { it.isFile && it.exists() }
    }

    /**
     * 探测伴侣 Crashpad Handler 可执行文件 (crashpad_handler.exe / crashpad_handler)
     */
    private fun findCrashpadHandler(preferredDir: File?): File? {
        val os = System.getProperty("os.name", "").lowercase()
        val handlerName = if (os.contains("win")) "crashpad_handler.exe" else "crashpad_handler"

        val searchDirs = linkedSetOf<File>()
        if (preferredDir != null && preferredDir.isDirectory) {
            searchDirs += preferredDir
        }
        try {
            ProcessHandle.current().info().command().ifPresent { exePath ->
                File(exePath).parentFile?.let { searchDirs += it }
            }
        } catch (_: Throwable) {
        }
        val userDir = File(System.getProperty("user.dir"))
        searchDirs += userDir
        searchDirs += File(userDir, "native")
        searchDirs += File(userDir, "app/native")
        searchDirs += DesktopDataDir.directory("native")

        for (dir in searchDirs) {
            val candidate = File(dir, handlerName)
            if (candidate.isFile && candidate.exists()) {
                return candidate
            }
        }
        return null
    }

    /**
     * 通过 JDK 22+ 标准 FFM (Foreign Function & Memory API) 加载并初始化 libsentry
     */
    private fun initNativeSentry(dsn: String, versionName: String) {
        try {
            val libFile = findNativeLibrary()
            val arena = Arena.ofAuto()
            val lookup: SymbolLookup = when {
                libFile != null -> {
                    _nativeLibraryPath = libFile.absolutePath
                    SymbolLookup.libraryLookup(Path.of(libFile.absolutePath), arena)
                }
                else -> {
                    try {
                        val os = System.getProperty("os.name", "").lowercase()
                        val defaultLibName = if (os.contains("win")) "sentry" else "libsentry"
                        SymbolLookup.libraryLookup(defaultLibName, arena).also {
                            _nativeLibraryPath = "system:$defaultLibName"
                        }
                    } catch (_: Throwable) {
                        null
                    }
                }
            } ?: run {
                logger.info("libsentry 未找到预编译动态库，原生崩溃捕获未激活（JVM 异常监控正常运行）。")
                return
            }

            val linker = Linker.nativeLinker()

            fun findMethod(name: String, descriptor: FunctionDescriptor): MethodHandle? {
                val symbol = lookup.find(name).orElse(null) ?: return null
                return linker.downcallHandle(symbol, descriptor)
            }

            val optionsNew = findMethod("sentry_options_new", FunctionDescriptor.of(ValueLayout.ADDRESS))
            val optionsSetDsn = findMethod("sentry_options_set_dsn", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            val optionsSetRelease = findMethod("sentry_options_set_release", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            val optionsSetEnv = findMethod("sentry_options_set_environment", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            val optionsSetDbPath = findMethod("sentry_options_set_database_path", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            val optionsSetHandlerPath = findMethod("sentry_options_set_handler_path", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            val sentryInit = findMethod("sentry_init", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
            val sentryClose = findMethod("sentry_close", FunctionDescriptor.of(ValueLayout.JAVA_INT))

            if (optionsNew == null || optionsSetDsn == null || sentryInit == null) {
                logger.warning("libsentry 缺少必要导出函数 (sentry_options_new / sentry_options_set_dsn / sentry_init)")
                return
            }

            val options = optionsNew.invoke() as? MemorySegment
            if (options == null || options == MemorySegment.NULL) {
                logger.warning("sentry_options_new() 返回空指针，无法初始化 libsentry。")
                return
            }

            Arena.ofConfined().use { initArena ->
                optionsSetDsn.invoke(options, initArena.allocateFrom(dsn))
                optionsSetRelease?.invoke(options, initArena.allocateFrom("cp.player@$versionName"))
                optionsSetEnv?.invoke(options, initArena.allocateFrom("desktop-production"))

                // 数据库缓存目录统一使用 DesktopDataDir（~/.cpplayer/sentry-db）
                val dbDir = DesktopDataDir.directory("sentry-db")
                optionsSetDbPath?.invoke(options, initArena.allocateFrom(dbDir.absolutePath))

                // 探测伴侣 Crashpad Handler
                val handler = findCrashpadHandler(libFile?.parentFile)
                if (handler != null && optionsSetHandlerPath != null) {
                    optionsSetHandlerPath.invoke(options, initArena.allocateFrom(handler.absolutePath))
                    logger.info("libsentry 绑定 Crashpad Handler: ${handler.absolutePath}")
                }

                val initResult = sentryInit.invoke(options) as Int
                if (initResult == 0) {
                    _isNativeAvailable = true
                    logger.info("libsentry (Sentry Native FFM) 成功初始化 [path=${_nativeLibraryPath}]")

                    // 注册退出钩子释放资源
                    if (sentryClose != null) {
                        Runtime.getRuntime().addShutdownHook(Thread {
                            runCatching { sentryClose.invoke() }
                        })
                    }
                } else {
                    logger.warning("sentry_init 返回非零状态码: $initResult")
                }
            }
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "初始化 libsentry 发生异常，降级为纯 JVM 监控", e)
        }
    }

    /**
     * 探测上次运行时遗留的 JVM 原生崩溃日志（hs_err_pid*.log），并作为诊断事件上报 Sentry。
     */
    private fun inspectPreviousCrashLogs() {
        try {
            val searchDirs = listOf(
                File(System.getProperty("user.dir")),
                DesktopDataDir.root(),
                File(System.getProperty("user.home")),
            )
            val now = System.currentTimeMillis()
            val maxAgeMs = 24 * 3600 * 1000L // 仅检查 24 小时内的崩溃

            for (dir in searchDirs) {
                if (!dir.isDirectory) continue
                val logs = dir.listFiles { f ->
                    f.isFile && f.name.startsWith("hs_err_pid") && f.name.endsWith(".log") && !f.name.endsWith(".reported.log")
                } ?: continue

                for (logFile in logs) {
                    if (now - logFile.lastModified() > maxAgeMs) continue
                    runCatching {
                        val headerLines = logFile.bufferedReader().useLines { lines ->
                            lines.take(30).toList()
                        }.joinToString("\n")

                        if (headerLines.isNotBlank()) {
                            val event = SentryEvent().apply {
                                level = SentryLevel.FATAL
                                message = Message().apply {
                                    formatted = "Fatal JVM Native Crash: ${logFile.name}"
                                }
                                fingerprints = listOf("jvm-native-crash-hs-err")
                                setTag("crash.type", "hs_err_log")
                                setTag("crash.file", logFile.name)
                                setExtra("log_header", headerLines)
                            }
                            Sentry.captureEvent(event)
                            logger.info("已将上次 JVM 致命崩溃日志上报至 Sentry: ${logFile.name}")
                        }
                        // 标记已上报，避免重复上传
                        val markedFile = File(dir, "${logFile.name}.reported.log")
                        logFile.renameTo(markedFile)
                    }
                }
            }
        } catch (e: Throwable) {
            logger.log(Level.FINE, "检查历史崩溃日志失败", e)
        }
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

        // 3. 原生层 libsentry (Sentry Native FFM) 探测与初始化
        initNativeSentry(dsn, versionName)

        // 4. 检查历史 JVM 崩溃日志 (hs_err_pid*.log)
        inspectPreviousCrashLogs()
    }

    fun captureException(throwable: Throwable) {
        Sentry.captureException(throwable)
    }
}
