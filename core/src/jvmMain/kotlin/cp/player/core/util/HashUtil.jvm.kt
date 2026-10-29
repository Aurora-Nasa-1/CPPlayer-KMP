package cp.player.core.util

import java.security.MessageDigest

actual fun md5Hex(text: String): String =
    runCatching {
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
