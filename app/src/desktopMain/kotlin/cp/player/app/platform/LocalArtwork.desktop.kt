package cp.player.app.platform

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 从本地音频文件提取**内嵌封面**（桌面端）。
 *
 * ## 为什么存在
 *
 * 桌面本地音源（[cp.player.core.local.DesktopLocalMediaSource]）刻意轻量：
 * 标题靠文件名推断、不解析音频标签，所以本地曲目的 `TrackSummary.coverUrl`
 * 恒为 null —— UI 与系统媒体面板都拿不到封面。这里在 SMTC 推送路径上懒提取，
 * 不动扫描链路（扫描时逐文件解标签会让整库扫描变慢一个量级）。
 *
 * 支持 ID3v2.3/2.4（mp3）APIC、FLAC PICTURE 块、mp4/m4a `covr` 原子，
 * 覆盖绝对多数本地曲库；ogg/wav/ape 等明确放弃（返回 null，无副作用）。
 *
 * 结果按文件路径哈希缓存在调用方的封面缓存目录，重复推送零 IO。
 * 所有解析在 `runCatching` 边界内，坏文件最多损失封面，绝不影响播放与推送。
 */
internal object LocalArtwork {

    private const val MAX_ID3_BYTES = 8L * 1024 * 1024
    private const val MAX_MOOV_BYTES = 32L * 1024 * 1024
    private const val MAX_COVER_BYTES = 10L * 1024 * 1024

    /** 封面缓存目录的条目上限（与远程封面共用同一目录，一并按 LRU 回收）。 */
    private const val MAX_CACHE_FILES = 64

    /**
     * 提取曲目 [trackId]（`local://audio/<绝对路径>` 格式）对应的内嵌封面，
     * 落盘到 [cacheDir] 并返回；无封面 / 不支持 / 失败一律返回 null。
     */
    fun extract(trackId: String, cacheDir: File): File? {
        val path = localFilePath(trackId) ?: return null
        val source = File(path)
        if (!source.isFile) return null

        val key = sha256(path)
        cacheDir.listFiles { f -> f.name.startsWith("$key.") }?.firstOrNull()?.let { return it }

        val found = runCatching { extractFrom(source) }.getOrNull() ?: return null
        val (ext, bytes) = found
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
        val target = File(cacheDir, "$key.$ext")
        return runCatching {
            // 先写 .part 再原子替换：SMTC 线程 / UI 线程可能并发读同一张封面，
            // 直接 writeBytes 会让它们读到半张图（系统媒体面板显示破损图片）。
            val tmp = File(cacheDir, "$key.$ext.part")
            tmp.writeBytes(bytes)
            val tmpPath = tmp.toPath()
            val targetPath = target.toPath()
            runCatching {
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure {
                // 某些文件系统不支持 ATOMIC_MOVE —— 退化为普通替换。
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
            pruneCache(cacheDir)
            target
        }.getOrNull()
    }

    /**
     * 按最后修改时间回收旧条目。
     *
     * 缓存目录挂在系统临时目录下：进程被强杀时不会走播控控制器的清理，
     * 长会话中又只增不减 —— 不设上限就会一直占磁盘。
     */
    private fun pruneCache(cacheDir: File) {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_CACHE_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHE_FILES)
            .forEach { runCatching { it.delete() } }
    }

    /** 只认桌面端本地文件路径；`content://`（Android）与其余 provider 一律返回 null。 */
    private fun localFilePath(trackId: String): String? {
        val schemeEnd = trackId.indexOf("://")
        if (schemeEnd <= 0) return null
        if (trackId.substring(0, schemeEnd) != "local") return null
        val rest = trackId.substring(schemeEnd + 3) // "audio/<path>"
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val path = rest.substring(slash + 1)
        if (path.isBlank() || path.contains("://")) return null
        return path
    }

    private fun extractFrom(source: File): Pair<String, ByteArray>? {
        RandomAccessFile(source, "r").use { raf ->
            val header = ByteArray(4)
            if (readFully(raf, 0, header) != header.size) return null
            return when {
                header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte() -> fromId3(raf)
                header.contentEquals("fLaC".toByteArray(Charsets.US_ASCII)) -> fromFlac(raf)
                header[0] == 0x00.toByte() || header[3] == 0x00.toByte() ||
                    isMp4Brand(header) -> fromMp4(raf)
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
        val limit = 10L + minOf(tagSize.toLong(), MAX_ID3_BYTES)

        var pos = 10L
        if (flags and 0x40 != 0) {
            val extHead = ByteArray(4)
            if (pos + 4 > limit || readFully(raf, pos, extHead) != 4) return null
            pos += if (version >= 4) syncsafe(extHead, 0).toLong() else (bigEndian32(extHead, 0).toLong() and 0xFFFFFFFFL) + 4L
        }
        val frameHead = ByteArray(10)
        while (pos + 10L <= limit) {
            if (readFully(raf, pos, frameHead) != frameHead.size) break
            val id = String(frameHead, 0, 4, Charsets.ISO_8859_1)
            if (id[0] !in 'A'..'Z') break // padding
            val frameSize = if (version >= 4) syncsafe(frameHead, 4) else bigEndian32(frameHead, 4)
            if (frameSize <= 0 || pos + 10L + frameSize > limit) break
            if (id == "APIC") {
                if (frameSize > MAX_COVER_BYTES + 4096) return null
                val apic = ByteArray(frameSize)
                if (readFully(raf, pos + 10L, apic) != apic.size) return null
                return parseApic(apic, 0, frameSize)
            }
            pos += 10L + frameSize
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
        return image(frame.copyOfRange(p, end), mime) ?: return null
    }

    /** 按编码跳过一个字符串（含终止符）。utf16 的终止符是双 0 且按 2 字节对齐。 */
    private fun skipText(data: ByteArray, from: Int, end: Int, encoding: Int): Int? {
        if (encoding == 1 || encoding == 2) {
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
        while (true) {
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
                return parseFlacPicture(block) ?: return null
            }
            if (isLast) return null
            offset += 4L + length
            if (length <= 0) return null
        }
    }

    /** PICTURE 块：type(4) mimeLen(4) mime descLen(4) desc w/h/depth/colors(16) dataLen(4) data。 */
    private fun parseFlacPicture(block: ByteArray): Pair<String, ByteArray>? {
        var p = 4
        val mimeLen = bigEndian32(block, p); p += 4
        if (mimeLen < 0 || p + mimeLen > block.size) return null
        val mime = String(block, p, mimeLen, Charsets.ISO_8859_1); p += mimeLen
        val descLen = bigEndian32(block, p); p += 4 + descLen + 16 // desc + w/h/depth/colors
        if (descLen < 0 || p + 4 > block.size) return null
        val dataLen = bigEndian32(block, p); p += 4
        if (dataLen <= 0 || p + dataLen > block.size) return null
        return image(block.copyOfRange(p, p + dataLen), mime) ?: return null
    }

    // ---------- MP4 / M4A ----------

    private fun isMp4Brand(header: ByteArray): Boolean =
        String(header, 0, 3, Charsets.US_ASCII) == "ft " ||
            String(header, 0, 4, Charsets.US_ASCII) in setOf("MOVI", "moov", "wide", "free", "skip")

    private fun fromMp4(raf: RandomAccessFile): Pair<String, ByteArray>? {
        val moov = findAtomRange(raf, 0L, raf.length(), "moov") ?: return null
        if (moov.second - moov.first > MAX_MOOV_BYTES) return null
        val udta = findAtomRange(raf, moov.first, moov.second, "udta") ?: return null
        val meta = findAtomRange(raf, udta.first, udta.second, "meta") ?: return null
        val ilst = findAtomRange(raf, meta.first + 4L, meta.second, "ilst") ?: return null
        val covr = findAtomRange(raf, ilst.first, ilst.second, "covr") ?: return null
        val data = findAtomRange(raf, covr.first, covr.second, "data") ?: return null
        val payloadStart = data.first + 8L
        val payloadLen = data.second - payloadStart
        if (payloadLen <= 0L || payloadLen > MAX_COVER_BYTES) return null
        val bytes = ByteArray(payloadLen.toInt())
        if (readFully(raf, payloadStart, bytes) != bytes.size) return null
        return image(bytes, null)
    }

    private fun findAtomRange(raf: RandomAccessFile, from: Long, until: Long, name: String): Pair<Long, Long>? {
        var pos = from
        val head = ByteArray(8)
        var guard = 0
        while (pos + 8L <= until && guard++ < 256) {
            if (readFully(raf, pos, head) != head.size) return null
            val size = bigEndian32(head, 0).toLong() and 0xFFFFFFFFL
            if (size < 8L) return null
            val end = minOf(pos + size, until)
            if (String(head, 4, 4, Charsets.US_ASCII) == name) return (pos + 8L) to end
            pos = end
        }
        return null
    }

    // ---------- 公共 ----------

    /** 按文件魔数判定格式并校验，认不出返回 null（防止把整文件当图）。 */
    private fun image(bytes: ByteArray, mime: String?): Pair<String, ByteArray>? {
        return when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> "jpg" to bytes
            bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png" to bytes
            bytes.size > 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'E'.code.toByte() -> "webp" to bytes
            else -> null
        }.also { result ->
            if (result == null && mime != null) {
                // 魔数认不出但 MIME 明确的，最后信一次 MIME
                val ext = when {
                    mime.contains("jpeg", ignoreCase = true) || mime.contains("jpg", true) -> "jpg"
                    mime.contains("png", ignoreCase = true) -> "png"
                    mime.contains("webp", ignoreCase = true) -> "webp"
                    else -> null
                }
                if (ext != null && bytes.isNotEmpty()) return ext to bytes
            }
        }
    }

    private fun readFully(raf: RandomAccessFile, offset: Long, target: ByteArray): Int {
        if (offset < 0 || offset >= raf.length()) return -1
        raf.seek(offset)
        return raf.read(target)
    }

    private fun syncsafe(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0x7F shl 21) or
            (data[offset + 1].toInt() and 0x7F shl 14) or
            (data[offset + 2].toInt() and 0x7F shl 7) or
            (data[offset + 3].toInt() and 0x7F)

    private fun bigEndian32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF shl 24) or
            (data[offset + 1].toInt() and 0xFF shl 16) or
            (data[offset + 2].toInt() and 0xFF shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
