package cp.player.core.util

import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** JVM actual：直接调用 System.currentTimeMillis()。 */
actual fun currentTimeMillis(): Long = System.currentTimeMillis()

/**
 * 探测 `(host, port)` 当前能否被监听成功。
 *
 * ### 语义必须与 Ktor CIO 的绑定一致
 * ktor-network 的 `tcp().bind()` 默认 `reuseAddress = false`（3.6.0 字节码核对过），
 * 所以这里也**不开** SO_REUSEADDR —— 探测通过 ⇔ CIO 随后真正 bind 也能通过。
 * 返回 false 仅表示「端口上已有活动监听 / 无权限」，TIME_WAIT 之外的行为两端一致。
 *
 * ### 为什么绑定前必须先探测
 * CIO 的端口绑定发生在引擎内部的 accept 协程里（`httpServer$acceptJob`），
 * `start(wait = false)` 外面的 try/catch **接不住** —— 异常会直达全局未捕获处理器，
 * 在 Android 上直接 FATAL 杀进程（实锤：`BindException` 崩溃）。
 * 所以「先探测、占不上就不启动」是唯一能把绑定失败降级为状态报错的位置，
 * 调用方：[cp.player.core.control.KtorLocalServer.start] 与
 * [cp.player.core.sync.SyncTransport.Server.start]。
 */
internal fun isTcpPortBindable(host: String, port: Int): Boolean = try {
    ServerSocket().use { socket ->
        socket.bind(java.net.InetSocketAddress(host, port))
    }
    true
} catch (_: Exception) {
    false
}

/**
 * JVM actual：走 `java.time`，与桌面 / Android 的系统时区一致。
 *
 * ⚠️ `java.time` 是 **API 26** 才进系统的，而本项目 minSdk 已降到 **24**（Android 7.0）
 * —— 这里能跑起来靠的是 **核心库脱糖**（`app-android` 的
 * `compileOptions.isCoreLibraryDesugaringEnabled` + `coreLibraryDesugaring(...)`，
 * 用 `desugar_jdk_libs_nio`，见 `libs.versions.toml`）。
 * 脱糖是**应用层 D8 阶段**做的：`:core` / `:app` 这两个 KMP 库模块本身不需要开开关，
 * 但**必须由 app 模块开着**，否则 API 24/25 上这里就是 `NoClassDefFoundError: java/time/Instant`。
 * 关掉那个开关时编译、桌面端、单测**全都照常通过**，只有低版本真机运行才炸 —— 别顺手删。
 */
actual fun localDateTimeOf(epochMillis: Long): LocalDateTimeParts {
    val dt = java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
    return LocalDateTimeParts(
        year = dt.year,
        month = dt.monthValue,
        day = dt.dayOfMonth,
        hour = dt.hour,
        minute = dt.minute,
        second = dt.second,
    )
}

/** 单个 zip 包解压上限：条目数与累计字节数（防 zip bomb / 损坏包写满磁盘）。 */
private const val MAX_ZIP_ENTRIES = 20_000
private const val MAX_ZIP_TOTAL_BYTES = 512L * 1024 * 1024

/**
 * JVM 共享平台支持（Android 与 Desktop 共用）。
 *
 * 端口探测（ServerSocket）、解压、文件读写、入口解析在此统一实现；
 * 仅 ABI 列表与模块根目录差异由 [PlatformInfo] 提供。
 */
actual object PlatformSupport {
    actual fun isPortAvailable(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (e: Exception) {
        false
    }

    actual fun findAvailablePort(startPort: Int, maxAttempts: Int): Int? {
        for (offset in 0 until maxAttempts) {
            val port = startPort + offset
            if (isPortAvailable(port)) return port
        }
        return null
    }

    actual fun modulesDir(context: PlatformContext): String = PlatformInfo.modulesDirectory(context)

    actual fun defaultDownloadsDir(context: PlatformContext): String = PlatformInfo.downloadsDirectory(context)

    actual fun dataDir(context: PlatformContext): String = PlatformInfo.dataDirectory(context)

    actual fun unzipTo(zipPath: String, destDir: String): Boolean {
        val dest = File(destDir).canonicalFile
        if (!dest.exists()) dest.mkdirs()
        return try {
            ZipInputStream(File(zipPath).inputStream()).use { zis ->
                var entry = zis.nextEntry
                var entryCount = 0
                var totalBytes = 0L
                while (entry != null) {
                    val newFile = File(dest, entry.name)
                    if (!newFile.canonicalPath.startsWith(dest.canonicalPath + File.separator)) {
                        throw SecurityException("Entry outside target dir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        // 防 zip bomb：条目数 + **实际写入字节数**双重上限。
                        // 不信任 entry.size（可以撒谎），所以按真实读到的字节累计。
                        if (++entryCount > MAX_ZIP_ENTRIES) {
                            throw SecurityException("zip 条目数超过上限 $MAX_ZIP_ENTRIES")
                        }
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { fos ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                totalBytes += n
                                if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                                    throw SecurityException("zip 解压总量超过上限 $MAX_ZIP_TOTAL_BYTES 字节")
                                }
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    actual fun deleteRecursively(path: String): Boolean = File(path).deleteRecursively()

    actual fun zipDirTo(dirPath: String, zipPath: String): Boolean = try {
        val root = File(dirPath)
        val dest = File(zipPath)
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream().buffered()).use { zos ->
            // entry 名统一 '/' 分隔（zip 规范），根目录本身不写条目；
            // 目录条目以 '/' 结尾，保证空目录也能进包，unzipTo 端能还原结构。
            fun add(file: File, rel: String) {
                if (file.isDirectory) {
                    if (rel.isNotEmpty()) {
                        zos.putNextEntry(ZipEntry("$rel/"))
                        zos.closeEntry()
                    }
                    val children = file.listFiles().orEmpty().sortedBy { it.name }
                    for (child in children) {
                        add(child, if (rel.isEmpty()) child.name else "$rel/${child.name}")
                    }
                } else {
                    zos.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            add(root, "")
        }
        true
    } catch (e: Exception) {
        // 别留下半个坏包：调用方按 false 处理时，若目标路径残留空壳会误导后续判断。
        runCatching { File(zipPath).delete() }
        false
    }

    actual fun readTextFile(path: String): String? = File(path).takeIf { it.exists() }?.readText()

    actual fun sha256Hex(path: String): String? = runCatching {
        val file = File(path)
        if (!file.exists()) return@runCatching null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }.getOrNull()

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun fileSize(path: String): Long = File(path).takeIf { it.exists() }?.length() ?: 0L

    actual fun fileLastModified(path: String): Long = File(path).takeIf { it.exists() }?.lastModified() ?: 0L

    actual fun ensureDir(path: String): Boolean = File(path).let { if (it.exists()) it.isDirectory else it.mkdirs() }

    actual fun moveFile(src: String, dest: String): Boolean = try {
        java.nio.file.Files.move(File(src).toPath(), File(dest).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (e: Exception) {
        try {
            java.nio.file.Files.move(File(src).toPath(), File(dest).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e2: Exception) {
            File(src).renameTo(File(dest))
        }
    }

    actual fun writeTextFile(path: String, content: String): Boolean = try {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 追加一行。
     *
     * ⚠️ 必须用 `appendText`（真正的 append 模式），不要图省事写成
     * `file.writeText(file.readText() + line)` —— 追加型日志（听歌记录）那样是 O(N²)
     * 的写入量，见 expect 声明里的说明。
     *
     * `appendText` 自 1.4 起有 `createNew` 参数语义的默认行为：文件不存在时创建、
     * 存在时续写，父目录仍需自己建。
     */
    actual fun appendTextFile(path: String, line: String): Boolean = try {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
        true
    } catch (e: Exception) {
        false
    }

    actual fun resolveEntryPoint(moduleDir: String, entryPoint: String, supportedAbis: List<String>?): String {
        val dir = File(moduleDir)
        // 多 ABI 格式：按平台 ABI 顺序查找
        for (abi in PlatformInfo.supportedAbis) {
            val abiFile = File(dir, "lib/$abi/$entryPoint")
            if (abiFile.exists()) return abiFile.absolutePath
        }
        // manifest 显式声明 ABI 时，取与平台 ABI 的交集优先匹配
        if (supportedAbis != null) {
            for (abi in supportedAbis.intersect(PlatformInfo.supportedAbis)) {
                val abiFile = File(dir, "lib/$abi/$entryPoint")
                if (abiFile.exists()) return abiFile.absolutePath
            }
        }
        return File(dir, entryPoint).absolutePath
    }

    /**
     * 校验 ELF 文件头魔数与架构是否匹配 [PlatformInfo.supportedAbis]。
     *
     * @return null 表示通过；非空为错误描述（用于阻止加载/启动）
     */
    actual fun validateElfHeader(path: String): String? {
        val file = File(path)
        if (!file.exists()) return "文件不存在: $path"
        return try {
            file.inputStream().use { fis ->
                val magic = ByteArray(4)
                if (fis.read(magic) != 4 ||
                    magic[0] != 0x7F.toByte() ||
                    magic[1] != 'E'.code.toByte() ||
                    magic[2] != 'L'.code.toByte() ||
                    magic[3] != 'F'.code.toByte()
                ) {
                    return null // 非标准 ELF，允许尝试
                }
                if (fis.skip(0x0EL) != 0x0EL) return null
                val eMachine = ByteArray(2)
                if (fis.read(eMachine) != 2) return null
                val machine = (eMachine[0].toInt() and 0xFF) or ((eMachine[1].toInt() and 0xFF) shl 8)
                val currentArch = when (PlatformInfo.supportedAbis.firstOrNull()) {
                    "arm64-v8a" -> 0xB7
                    "armeabi-v7a" -> 0x28
                    "x86_64", "amd64" -> 0x3E
                    "x86" -> 0x03
                    else -> 0
                }
                if (currentArch != 0 && machine != currentArch) {
                    "ELF 架构不匹配: 文件=0x${machine.toString(16)}, 设备=0x${currentArch.toString(16)} (${PlatformInfo.supportedAbis.firstOrNull()})"
                } else null
            }
        } catch (e: Exception) {
            null // 校验异常不阻止
        }
    }

    actual fun listChildDirectories(dir: String): List<String> {
        val d = File(dir)
        if (!d.exists() || !d.isDirectory) return emptyList()
        return d.listFiles { f -> f.isDirectory }?.map { it.absolutePath } ?: emptyList()
    }

    actual fun listChildFiles(dir: String): List<String> {
        val d = File(dir)
        if (!d.exists() || !d.isDirectory) return emptyList()
        // 排序后再返回：调用方（日志分片加载）依赖稳定顺序，列目录的返回顺序在
        // 不同文件系统上并不保证。
        return d.listFiles { f -> f.isFile }?.map { it.absolutePath }?.sorted() ?: emptyList()
    }

    actual fun moveDir(src: String, dest: String): Boolean {
        val srcFile = File(src)
        if (!srcFile.exists()) return false
        val destFile = File(dest)
        // 同卷**原子**重命名：成功后旧目录「原地变成」新目录，整个操作没有
        // 「旧目录已删、新目录还没到」的窗口 —— 这是模块安装最不能出的状态。
        // 先试原子路径（目标通常已被调用方清理，故会成功）。
        try {
            java.nio.file.Files.move(
                srcFile.toPath(), destFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            return true
        } catch (e: Exception) {
            // 跨卷 / 目标被占用（Windows 上活跃 jni 模块的 dll 常被锁）⇒ 落到可回滚路径。
        }
        // 目标已存在时**先挪成 .bak 备份，而不是直接删**：替换失败还能把旧目录挪回来，
        // 绝不出现「旧模块被删掉、新模块又没装上」的空窗。
        val backup = File("$dest.bak-${System.currentTimeMillis()}")
        var destBackedUp = false
        if (destFile.exists()) {
            if (!destFile.renameTo(backup)) return false // 连备份都做不到：保留旧目录，直接失败
            destBackedUp = true
        }
        val moved = try {
            java.nio.file.Files.move(
                srcFile.toPath(), destFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (e: Exception) {
            srcFile.renameTo(destFile)
        }
        return if (moved) {
            if (destBackedUp) runCatching { backup.deleteRecursively() }
            true
        } else {
            if (destBackedUp) backup.renameTo(destFile) // 回滚：至少留一个可用模块
            false
        }
    }
}