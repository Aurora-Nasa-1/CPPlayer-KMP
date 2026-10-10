package cp.player.core.local

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 本地曲封面仓库（jvmMain：桌面端扫描链路使用）。
 *
 * ## 为什么需要它
 *
 * 桌面端扫描出来的本地曲此前 `coverUri` 恒为 null —— 列表是一整片灰占位，
 * 封面取色 / Material You / 专辑墙 / 系统媒体面板全都拿不到输入。
 * 这里在扫描时把封面落到磁盘缓存并回一个 `file://` URI，让本地曲与在线曲
 * 走同一条封面渲染路径。
 *
 * ## 封面来源（按可信度递减）
 *
 * 1. **内嵌封面**（ID3v2 APIC / FLAC PICTURE / MP4 covr）
 * 2. **同目录封面图**（`cover.jpg` / `folder.jpg` / `albumart.jpg` …）
 *
 * 刻意**不做**「退化为目录里任意一张大图」：那会让一张生活照变成整张专辑的封面，
 * 是本地播放器最常见的视觉事故之一。
 *
 * 结果按路径哈希落盘缓存，重复扫描零重复 IO；坏文件最多丢封面，不影响扫描。
 */
internal class LocalCoverStore(private val cacheDir: File) {

    init {
        runCatching { if (!cacheDir.exists()) cacheDir.mkdirs() }
    }

    /**
     * 解析 [path] 的封面，返回可直接喂给图片加载器的 URI；无封面返回 null。
     *
     * @param hasEmbeddedCover 是否带内嵌封面（来自标签解析）；false 时跳过文件头扫描，
     *   只做目录封面回退，省掉一次完整解析
     */
    fun resolve(path: String, hasEmbeddedCover: Boolean): String? {
        if (path.startsWith("content://")) return null // Android SAF，走 MediaStore URI
        val source = File(path)
        if (!source.isFile) return null

        val key = sha256(path)
        cacheDir.listFiles { f -> f.name.startsWith("$key.") && !f.name.endsWith(".part") }
            ?.firstOrNull()?.let { return it.toURI().toString() }

        val found = if (hasEmbeddedCover) {
            runCatching { extractEmbedded(source) }.getOrNull()
        } else {
            null
        } ?: findFolderCover(source)
            ?.let { it.name.substringAfterLast('.').lowercase() to it.readBytes() }

        if (found == null) return null
        val (ext, bytes) = found
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null

        return runCatching {
            val target = File(cacheDir, "$key.$ext")
            // 先写 .part 再原子替换：扫描线程与 UI 线程可能并发读同一张封面，
            // 直接 writeBytes 会让 UI 读到半张图（系统媒体面板显示破损图片）。
            val tmp = File(cacheDir, "$key.$ext.part")
            tmp.writeBytes(bytes)
            runCatching {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            }.onFailure {
                // 某些文件系统不支持 ATOMIC_MOVE —— 退化为普通替换
                runCatching {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            target.toURI().toString()
        }.getOrNull()
    }

    /**
     * 同目录封面图。
     *
     * 只认固定文件名白名单：本地音乐库里 `cover.jpg` 几乎总是专辑封面，
     * 而目录里任意一张图极可能是无关的生活照。
     */
    private fun findFolderCover(source: File): File? {
        val dir = source.parentFile ?: return null
        for (name in FOLDER_COVER_NAMES) {
            for (ext in FOLDER_COVER_EXTS) {
                val candidate = File(dir, "$name.$ext")
                if (candidate.isFile && candidate.length() <= MAX_COVER_BYTES) return candidate
            }
        }
        return null
    }

    private fun extractEmbedded(source: File): Pair<String, ByteArray>? {
        RandomAccessFile(source, "r").use { raf ->
            val header = ByteArray(4)
            if (readFully(raf, 0, header) != header.size) return null
            return when {
                header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte() -> fromId3(raf)
                isAscii(header, 0, "fLaC") -> fromFlac(raf)
                isAscii(header, 4, "ftyp") || isAscii(header, 4, "moov") -> fromMp4(raf)
                else -> null
            }
        }
    }

    // ---------- ID3v2 (mp3) ----------

    private fun fromId3(raf: RandomAccessFile): Pair<String, ByteArray>? {
        val head = ByteArray(10)
        if (readFully(raf, 0, head) != head.size) return null
        val version = head[3].toInt() and 0xFF
        val flags = head[5].toInt() and 0xFF
        val tagSize = syncsafe(head, 6)
        if (tagSize <= 0) return null
        val buffer = ByteArray(minOf(tagSize.toLong(), MAX_ID3_BYTES).toInt())
        if (readFully(raf, 10, buffer) != buffer.size) return null

        var pos = 0
        if (flags and 0x40 != 0) { // 扩展头：v2.4 尺寸含自身（syncsafe），v2.3 不含
            if (pos + 4 > buffer.size) return null
            pos += if (version >= 4) syncsafe(buffer, 0) else be32(buffer, 0) + 4
        }
        while (pos + 10 <= buffer.size) {
            val id = readAscii(buffer, pos, 4) ?: break
            if (id.isEmpty() || id[0] !in 'A'..'Z') break // padding
            val frameSize = if (version >= 4) syncsafe(buffer, pos + 4) else be32(buffer, pos + 4)
            if (frameSize <= 0 || pos + 10 + frameSize > buffer.size) break
            if (id == "APIC") return parseApic(buffer, pos + 10, frameSize)
            pos += 10 + frameSize
        }
        return null
    }

    /** APIC 帧：enc(1) + mime(latin1\0) + picType(1) + description(按 enc 终止) + 图像数据。 */
    private fun parseApic(frame: ByteArray, start: Int, size: Int): Pair<String, ByteArray>? {
        var p = start
        val end = start + size
        if (p >= end) return null
        val encoding = frame[p].toInt() and 0xFF
        p += 1
        val mimeEnd = indexOfZero(frame, p, end) ?: return null
        val mime = String(frame, p, mimeEnd - p, Charsets.ISO_8859_1)
        p = mimeEnd + 1 + 1 // 越过 mime 终止符与 picture type
        p = skipText(frame, p, end, encoding) ?: return null
        if (p >= end) return null
        return image(frame.copyOfRange(p, end), mime)
    }

    private fun skipText(data: ByteArray, from: Int, end: Int, encoding: Int): Int? {
        if (encoding == 1 || encoding == 2) { // UTF-16：终止符为双 0，按 2 字节步进
            var i = from
            while (i + 1 < end) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i + 2
                i += 2
            }
            return null
        }
        return (indexOfZero(data, from, end) ?: return null) + 1
    }

    private fun indexOfZero(data: ByteArray, from: Int, end: Int): Int? {
        var i = from
        while (i < end) {
            if (data[i] == 0.toByte()) return i
            i++
        }
        return null
    }

    // ---------- FLAC ----------

    private fun fromFlac(raf: RandomAccessFile): Pair<String, ByteArray>? {
        var offset = 4L
        var guard = 0
        while (guard++ < 64) {
            val blockHeader = ByteArray(4)
            if (readFully(raf, offset, blockHeader) != blockHeader.size) return null
            val isLast = blockHeader[0].toInt() and 0x80 != 0
            val type = blockHeader[0].toInt() and 0x7F
            val length = (blockHeader[1].toInt() and 0xFF shl 16) or
                (blockHeader[2].toInt() and 0xFF shl 8) or
                (blockHeader[3].toInt() and 0xFF)
            if (type == 6) { // PICTURE
                val block = ByteArray(length)
                if (readFully(raf, offset + 4, block) != block.size) return null
                return parseFlacPicture(block)
            }
            if (isLast || length <= 0) return null
            offset += 4L + length
        }
        return null
    }

    /** PICTURE 块：type(4) mimeLen(4) mime descLen(4) desc w/h/depth/colors(16) dataLen(4) data。 */
    private fun parseFlacPicture(block: ByteArray): Pair<String, ByteArray>? {
        var p = 4
        if (p + 4 > block.size) return null
        val mimeLen = be32(block, p); p += 4
        if (mimeLen < 0 || p + mimeLen > block.size) return null
        val mime = String(block, p, mimeLen, Charsets.ISO_8859_1); p += mimeLen
        if (p + 4 > block.size) return null
        val descLen = be32(block, p); p += 4 + descLen + 16
        if (descLen < 0 || p + 4 > block.size) return null
        val dataLen = be32(block, p); p += 4
        if (dataLen <= 0 || p + dataLen > block.size) return null
        return image(block.copyOfRange(p, p + dataLen), mime)
    }

    // ---------- MP4 / M4A ----------

    private fun fromMp4(raf: RandomAccessFile): Pair<String, ByteArray>? {
        val moovOffset = findTopAtom(raf, "moov") ?: return null
        val sizeHeader = ByteArray(8)
        if (readFully(raf, moovOffset, sizeHeader) != sizeHeader.size) return null
        val moovSize = be32(sizeHeader, 0).toLong() and 0xFFFFFFFFL
        if (moovSize <= 8 || moovSize > MAX_MOOV_BYTES) return null
        val moov = ByteArray(moovSize.toInt())
        if (readFully(raf, moovOffset, moov) != moov.size) return null
        val udta = childAtom(moov, 8, moov.size, "udta") ?: return null
        val meta = childAtom(moov, udta.first, udta.second, "meta") ?: return null
        val ilst = childAtom(moov, meta.first + 12, meta.second, "ilst") ?: return null
        val covr = childAtom(moov, ilst.first, ilst.second, "covr") ?: return null
        val data = childAtom(moov, covr.first, covr.second, "data") ?: return null
        // data 原子：8B 头 + 4B 版本/标志 + 4B locale
        val payloadStart = data.first + 16
        if (payloadStart >= data.second) return null
        return image(moov.copyOfRange(payloadStart, data.second), null)
    }

    private fun findTopAtom(raf: RandomAccessFile, name: String): Long? {
        var pos = 0L
        val head = ByteArray(8)
        val limit = raf.length()
        var guard = 0
        while (pos + 8 <= limit && guard++ < 64) {
            if (readFully(raf, pos, head) != head.size) return null
            val size = be32(head, 0).toLong() and 0xFFFFFFFFL
            if (size < 8) return null
            if (readAscii(head, 4, 4) == name) return pos
            pos += size
        }
        return null
    }

    private fun childAtom(data: ByteArray, from: Int, until: Int, name: String): Pair<Int, Int>? {
        var pos = from
        var guard = 0
        while (pos + 8 <= until && guard++ < 256) {
            val size = be32(data, pos).toLong() and 0xFFFFFFFFL
            if (size < 8) return null
            val type = readAscii(data, pos + 4, 4) ?: return null
            val contentStart = pos + 8
            val contentEnd = minOf(pos + size, until.toLong()).toInt()
            if (type == name) return contentStart to contentEnd
            pos = contentEnd
        }
        return null
    }

    // ---------- 公共 ----------

    /** 按文件魔数判定格式并校验，认不出返回 null（防止把整文件当图）。 */
    private fun image(bytes: ByteArray, mime: String?): Pair<String, ByteArray>? {
        when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> return "jpg" to bytes
            bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> return "png" to bytes
            bytes.size > 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'E'.code.toByte() -> return "webp" to bytes
        }
        // 魔数认不出但 MIME 明确的，最后信一次 MIME
        val ext = when {
            mime == null -> null
            mime.contains("jpeg", ignoreCase = true) || mime.contains("jpg", ignoreCase = true) -> "jpg"
            mime.contains("png", ignoreCase = true) -> "png"
            mime.contains("webp", ignoreCase = true) -> "webp"
            else -> null
        }
        return if (ext != null && bytes.isNotEmpty()) ext to bytes else null
    }

    private fun readFully(raf: RandomAccessFile, offset: Long, target: ByteArray): Int {
        if (offset < 0 || offset >= raf.length()) return -1
        raf.seek(offset)
        return raf.read(target)
    }

    private fun readAscii(data: ByteArray, at: Int, length: Int): String? {
        if (at < 0 || at + length > data.size) return null
        return String(data, at, length, Charsets.ISO_8859_1)
    }

    private fun isAscii(data: ByteArray, at: Int, expected: String): Boolean {
        if (at + expected.length > data.size) return false
        for (i in expected.indices) {
            if (data[at + i] != expected[i].code.toByte()) return false
        }
        return true
    }

    private fun be32(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF shl 24) or (data[at + 1].toInt() and 0xFF shl 16) or
            (data[at + 2].toInt() and 0xFF shl 8) or (data[at + 3].toInt() and 0xFF)

    private fun syncsafe(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0x7F shl 21) or (data[at + 1].toInt() and 0x7F shl 14) or
            (data[at + 2].toInt() and 0x7F shl 7) or (data[at + 3].toInt() and 0x7F)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_ID3_BYTES = 8L * 1024 * 1024
        const val MAX_MOOV_BYTES = 32L * 1024 * 1024
        const val MAX_COVER_BYTES = 10L * 1024 * 1024

        /** 目录封面文件名白名单（与常见媒体服务器 / 播放器约定一致）。 */
        val FOLDER_COVER_NAMES = listOf("cover", "folder", "albumart", "front", "album", "artwork")
        val FOLDER_COVER_EXTS = listOf("jpg", "jpeg", "png", "webp")
    }
}
