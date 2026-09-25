package cp.player.kmp.local

import java.io.File

/** JVM actual：读取文本文件，不存在或异常返回 null。 */
internal actual fun localMediaReadText(path: String): String? =
    runCatching { File(path).takeIf { it.exists() }?.readText() }.getOrNull()

/** JVM actual：写入文本文件，自动创建父目录。 */
internal actual fun localMediaWriteText(path: String, content: String): Boolean = runCatching {
    val file = File(path)
    file.parentFile?.takeIf { !it.exists() }?.mkdirs()
    file.writeText(content)
    true
}.getOrDefault(false)
