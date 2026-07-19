package cp.player.kmp.util

import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.util.zip.ZipInputStream

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

    actual fun unzipTo(zipPath: String, destDir: String): Boolean {
        val dest = File(destDir).canonicalFile
        if (!dest.exists()) dest.mkdirs()
        return try {
            ZipInputStream(File(zipPath).inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val newFile = File(dest, entry.name)
                    if (!newFile.canonicalPath.startsWith(dest.canonicalPath + File.separator)) {
                        throw SecurityException("Entry outside target dir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { fos -> zis.copyTo(fos) }
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

    actual fun readTextFile(path: String): String? = File(path).takeIf { it.exists() }?.readText()

    actual fun exists(path: String): Boolean = File(path).exists()

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

    actual fun moveDir(src: String, dest: String): Boolean {
        val srcFile = File(src)
        if (!srcFile.exists()) return false
        if (PlatformSupport.exists(dest)) PlatformSupport.deleteRecursively(dest)
        return srcFile.renameTo(File(dest))
    }
}