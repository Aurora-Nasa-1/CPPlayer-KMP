package cp.player.kmp.util

import cp.player.kmp.provider.BackendProvider

/** 跨平台毫秒时间戳（避免 kotlinx-datetime 运行时缺失问题）。 */
expect fun currentTimeMillis(): Long

/**
 * 平台支持抽象（端口探测 / 文件系统 / ELF 校验 / 入口解析）。
 *
 * commonMain 声明 expect；jvmMain 提供 JVM 共享 actual（Android + Desktop 共用）。
 */
expect object PlatformSupport {
    /** 检查端口是否可用 */
    fun isPortAvailable(port: Int): Boolean

    /** 从 [startPort] 开始搜索可用端口，最多尝试 [maxAttempts] 个，找不到返回 null */
    fun findAvailablePort(startPort: Int = 3000, maxAttempts: Int = 20): Int?

    /** 模块根目录（每个平台的具体路径） */
    fun modulesDir(context: PlatformContext): String

    /** 平台默认下载目录（不存在时创建） */
    fun defaultDownloadsDir(context: PlatformContext): String

    /** 应用数据目录（存放 downloads.json 等元数据，与 [modulesDir] 同级风格） */
    fun dataDir(context: PlatformContext): String

    /**
     * 解压 zip 到目标目录（带路径穿越保护）。
     * @return true 成功
     */
    fun unzipTo(zipPath: String, destDir: String): Boolean

    /** 递归删除目录 */
    fun deleteRecursively(path: String): Boolean

    /** 读取文本文件 */
    fun readTextFile(path: String): String?

    /** 判断文件/目录是否存在 */
    fun exists(path: String): Boolean

    /** 文件大小（字节）；不存在返回 0 */
    fun fileSize(path: String): Long

    /** 文件最后修改时间戳（毫秒）；不存在返回 0 */
    fun fileLastModified(path: String): Long

    /** 确保目录存在（不存在则递归创建）；返回是否为目录 */
    fun ensureDir(path: String): Boolean

    /** 移动/重命名单个文件（优先原子 rename，失败降级普通替换） */
    fun moveFile(src: String, dest: String): Boolean

    /** 写文本文件（自动创建父目录）；返回是否成功 */
    fun writeTextFile(path: String, content: String): Boolean

    /**
     * 按平台 ABI 顺序解析模块入口文件路径。
     *
     * @param moduleDir 模块根目录
     * @param entryPoint manifest 中的 entryPoint（如 "libfoo.so"）
     * @param supportedAbis manifest 声明的 ABI 列表（可选）
     * @return 实际入口文件绝对路径（可能不存在）
     */
    fun resolveEntryPoint(moduleDir: String, entryPoint: String, supportedAbis: List<String>?): String

    /**
     * 校验 ELF 文件头魔数与架构是否匹配 [PlatformInfo.supportedAbis]。
     * @return null 表示通过；非空为错误描述（用于阻止加载/启动）
     */
    fun validateElfHeader(path: String): String?

    /** 列出目录下的子目录绝对路径（不含文件）。目录不存在时返回空列表。 */
    fun listChildDirectories(dir: String): List<String>

    /** 重命名/移动目录（src → dest）。dest 必须不存在或可覆盖。 */
    fun moveDir(src: String, dest: String): Boolean
}