package cp.player.core.media

import java.io.RandomAccessFile

/**
 * 本地音频文件的轻量元数据读取器（jvmMain：Android 与 Desktop 共用）。
 *
 * ## 为什么自己写而不引第三方库
 *
 * - `jaudiotagger` / `mp3spi` 一类是 JVM 库，能同时服务两个平台，但它们为了兼容
 *   各种畸形文件做了大量容错与对象分配，整库扫描时的开销不可控。
 * - 这里只解析**头部若干字节**（外加 MP4 的 moov、OGG 的尾页），不读音频数据，
 *   单文件 IO 量恒定在几十 KB 量级。
 * - 全部解析包在 `runCatching` 边界内：坏文件最多丢元数据，绝不影响扫描与播放。
 *
 * ## 覆盖范围
 *
 * | 格式 | 标签 | 时长 | 码率/采样率/位深 |
 * |---|---|---|---|
 * | MP3（ID3v2.3/2.4） | ✅ | ✅ Xing 优先，CBR 估算兜底 | ✅ |
 * | FLAC | ✅ Vorbis Comment | ✅ STREAMINFO | ✅ |
 * | MP4 / M4A / AAC | ✅ ilst | ✅ mvhd | ✅ |
 * | OGG / Opus | ✅ Vorbis/Opus Tags | ✅ 尾页 granule | ✅ |
 * | WAV | ❌（RIFF 无标准标签） | ✅ data/fmt | ✅ |
 *
 * APE / DSF / WMA / MPC 等明确不解析（返回 null，只靠文件名推断标题）。
 */
internal object AudioMetadataReader {

    /** 头部扫描窗口：ID3 标签、MP4 moov、FLAC 元数据块都在这个量级内。 */
    private const val HEAD_BYTES = 96 * 1024

    /**
     * ID3v2 标签的读取上限。
     *
     * 96KB 的头部窗口经常不够：一张 500×500 的 APIC 封面就能把 USLT 推到 200KB 之后，
     * 于是「文件明明有歌词，应用读不到」。这里按标签头声明的长度单独把标签读完，
     * 上限只为防畸形文件撑爆堆。
     */
    private const val MAX_ID3_BYTES = 16 * 1024 * 1024

    /** 尾部扫描窗口：OGG 的时长需要从最后一个页面的 granule position 反推。 */
    private const val TAIL_BYTES = 64 * 1024

    /** OGG 头部最多翻多少页来找注释包（异常文件保护）。 */
    private const val MAX_OGG_PAGES = 256

    /** `moov` 上限：4K 视频的样本表可能上 MB，但再大就不是音频标签了。 */
    private const val MAX_MOOV_BYTES = 32 * 1024 * 1024

    /** 判断是否形似 LRC 行首时间戳。 */
    private val LRC_TAG = Regex("""(?m)^\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")

    /**
     * 读取 [path] 的音频元数据；不支持、损坏或 IO 失败一律返回 null。
     */
    fun read(path: String): AudioTagInfo? = runCatching {
        RandomAccessFile(path, "r").use { raf ->
            if (raf.length() <= 0) return null
            val head = ByteArray(minOf(HEAD_BYTES.toLong(), raf.length()).toInt())
            raf.seek(0)
            if (raf.read(head) < 4) return null
            val info = dispatch(raf, head)
            // APE 标签在不少 MP3 / FLAC 里单独承载歌词（`Lyrics` 项），且位于文件尾。
            // 各格式自己的歌词读取失败时兜到这里，避免「同一首歌换个封装就读不到词」。
            if (info != null && info.lyrics.isNullOrBlank()) {
                info.copy(lyrics = readApeLyrics(raf))
            } else {
                info
            }
        }
    }.getOrNull()

    private fun dispatch(raf: RandomAccessFile, head: ByteArray): AudioTagInfo? {
        if (head.size >= 4 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
            head[2] == '3'.code.toByte()
        ) return readMp3(raf, head)
        if (isAscii(head, 0, "fLaC")) return readFlac(raf)
        if (isAscii(head, 0, "OggS")) return readOgg(raf)
        if (isAscii(head, 0, "RIFF") && isAscii(head, 8, "WAVE")) return readWav(head)
        // MP4 家族：ftyp 开头，或 moov/wide/free/skip 直接在文件头（少数封装）
        if (isAscii(head, 4, "ftyp") || isAscii(head, 4, "moov") ||
            isAscii(head, 0, "moov") || isAscii(head, 4, "wide")
        ) return readMp4(raf)
        // 裸 MP3（无 ID3）：以 MPEG 帧同步字开头
        if (isMpegFrameStart(head, 0)) return readMp3(raf, head, id3Size = 0)
        return null
    }

    // ==================== MP3 / ID3v2 ====================

    private fun readMp3(raf: RandomAccessFile, head: ByteArray, id3Size: Int = -1): AudioTagInfo? {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var year: Int? = null
        var track: Int? = null
        var disc: Int? = null
        var hasCover = false
        var lyrics: String? = null

        // ID3v2 标签可能远大于头部窗口（封面帧在前、歌词帧在后）。按声明长度补齐读取，
        // 否则 USLT / SYLT 会被静默漏掉 —— 这是「本地歌明明带词却显示无歌词」的主因之一。
        val tagData = if (id3Size >= 0) head else readId3Tag(raf, head) ?: head
        val tagSize = if (id3Size >= 0) id3Size else parseId3v2(tagData) { id, text ->
            when (id) {
                "TIT2" -> if (title == null) title = text
                "TPE1" -> if (artist == null) artist = text
                "TALB" -> if (album == null) album = text
                "TPE2" -> if (albumArtist == null) albumArtist = text
                "TCON" -> if (genre == null) genre = normalizeGenre(text)
                "TYER", "TDRC" -> if (year == null) year = text.take(4).toIntOrNull()
                "TRCK" -> if (track == null) track = text.substringBefore('/').toIntOrNull()
                "TPOS" -> if (disc == null) disc = text.substringBefore('/').toIntOrNull()
                "USLT", "SYLT" -> if (lyrics == null) lyrics = text.takeIf { it.isNotBlank() }
            }
            if (id == "APIC") hasCover = true
        }
        if (tagSize < 0) return null

        val fileSize = raf.length()
        // id3Size >= 0 表示调用方已知没有 ID3v2（裸 MP3），音频从第 0 字节开始
        val audioStart = if (id3Size >= 0) id3Size.toLong() else (10L + tagSize).coerceAtMost(fileSize)
        // ID3v1 尾巴 128 字节不属于音频数据
        val audioEnd = if (fileSize > 128 && hasId3v1(raf, fileSize)) fileSize - 128 else fileSize
        val audioBytes = (audioEnd - audioStart).coerceAtLeast(0)

        val frame = findMpegFrame(raf, audioStart)
        var durationMs = 0L
        var bitrateKbps: Int? = null
        var sampleRate: Int? = null
        if (frame != null) {
            bitrateKbps = frame.bitrateKbps
            sampleRate = frame.sampleRateHz
            // Xing/Info 头给出精确帧数；没有就按 CBR 估算（VBR 会偏，但比 0 强得多）
            val frameCount = readXingFrames(raf, frame)
            if (frameCount != null && frameCount > 0 && frame.sampleRateHz > 0) {
                durationMs =
                    frameCount * frame.samplesPerFrame * 1000L / frame.sampleRateHz
            } else if (frame.bitrateKbps > 0) {
                durationMs = audioBytes * 8 / (frame.bitrateKbps.toLong())
            }
        }

        return AudioTagInfo(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = year,
            trackNumber = track,
            discNumber = disc,
            durationMs = durationMs,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRate,
            channels = frame?.channels,
            hasEmbeddedCover = hasCover,
            codec = "MP3",
            lyrics = lyrics,
        )
    }

    /**
     * 遍历 ID3v2 帧，回调每个帧的 [id] 与解码后的文本（APIC 等二进制帧传空串）。
     * @return 标签体大小（不含 10 字节头）；无 ID3v2 返回 0；解析失败返回 -1。
     */
    private fun parseId3v2(head: ByteArray, onFrame: (String, String) -> Unit): Int {
        if (head.size < 10) return -1
        val version = head[3].toInt() and 0xFF
        val flags = head[5].toInt() and 0xFF
        val size = syncsafe(head, 6)
        if (size <= 0) return -1
        val bodyEnd = minOf(10 + size, head.size)

        var pos = 10
        if (flags and 0x40 != 0) { // 扩展头：v2.4 长度含自身（syncsafe），v2.3 不含
            val extSize = if (version >= 4) {
                if (pos + 4 > bodyEnd) return -1
                syncsafe(head, pos)
            } else {
                if (pos + 4 > bodyEnd) return -1
                be32(head, pos)
            }
            pos += if (version >= 4) extSize else extSize + 4
        }

        while (pos + 10 <= bodyEnd) {
            val id = readAscii(head, pos, 4) ?: break
            if (id.isEmpty() || id[0] !in 'A'..'Z') break // padding
            val frameSize = if (version >= 4) syncsafe(head, pos + 4) else be32(head, pos + 4)
            if (frameSize <= 0 || pos + 10 + frameSize > bodyEnd) break
            val dataStart = pos + 10
            // v2.4 的帧有「分组标识 / 压缩 / 加密 / 同步」标志位，这里一律跳过不处理
            val text = when (id) {
                "APIC" -> ""
                "USLT" -> decodeId3Uslt(head, dataStart, frameSize)
                "SYLT" -> decodeId3Sylt(head, dataStart, frameSize)
                else -> decodeId3Text(head, dataStart, frameSize)
            }
            onFrame(id, text)
            pos = dataStart + frameSize
        }
        return size
    }

    /**
     * 按标签头声明的长度把整个 ID3v2 标签读进内存。
     *
     * @return 至少包含 10 字节标签头的数组；没有标签 / 长度不可信时返回 null
     */
    private fun readId3Tag(raf: RandomAccessFile, head: ByteArray): ByteArray? {
        if (head.size < 10) return null
        if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return null
        val size = syncsafe(head, 6)
        if (size <= 0) return null
        val total = (10L + size).coerceAtMost(MAX_ID3_BYTES.toLong()).coerceAtMost(raf.length())
        if (total <= head.size) return head
        return ByteArray(total.toInt()).also {
            raf.seek(0)
            raf.readFully(it)
        }
    }

    /**
     * ID3 SYLT 同步歌词帧 → LRC 文本。
     *
     * 帧结构：encoding(1) + language(3) + timeFormat(1) + contentType(1) + descriptor(终止符)
     * + 重复的 `text(终止符) + time(4B BE)`。timeFormat=1 表示 MPEG 帧号（无法脱离码流换算），
     * 这类帧不能可靠还原，直接跳过 —— 读错时间比没有歌词更糟。
     */
    private fun decodeId3Sylt(data: ByteArray, start: Int, size: Int): String {
        if (size <= 6) return ""
        val encoding = data[start].toInt() and 0xFF
        val end = start + size
        var p = start + 4
        val timeFormat = data[p++].toInt() and 0xFF
        p++ // contentType
        if (timeFormat != 2) return ""
        // 跳过 descriptor
        if (encoding == 1 || encoding == 2) {
            while (p + 1 < end) {
                if (data[p] == 0.toByte() && data[p + 1] == 0.toByte()) { p += 2; break }
                p += 2
            }
        } else {
            while (p < end) {
                if (data[p] == 0.toByte()) { p++; break }
                p++
            }
        }
        val out = StringBuilder()
        while (p < end) {
            val textStart = p
            if (encoding == 1 || encoding == 2) {
                while (p + 1 < end) {
                    if (data[p] == 0.toByte() && data[p + 1] == 0.toByte()) break
                    p += 2
                }
                if (p + 2 > end) break
            } else {
                while (p < end && data[p] != 0.toByte()) p++
                if (p >= end) break
            }
            val text = decodeId3Bytes(data, textStart, p, encoding)
            p += if (encoding == 1 || encoding == 2) 2 else 1
            if (p + 4 > end) break
            val time = be32(data, p)
            p += 4
            if (text.isBlank()) continue
            out.append('[')
                .append((time / 60_000).toString().padStart(2, '0')).append(':')
                .append((time / 1_000 % 60).toString().padStart(2, '0')).append('.')
                .append((time % 1_000 / 10).toString().padStart(2, '0'))
                .append(']').append(text.replace('\n', ' ')).append('\n')
        }
        return out.toString().trim()
    }

    private fun decodeId3Bytes(data: ByteArray, from: Int, to: Int, encoding: Int): String {
        if (to <= from) return ""
        val raw = data.copyOfRange(from, to)
        return runCatching {
            when (encoding) {
                1 -> {
                    if (raw.size < 2) ""
                    else {
                        val le = raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()
                        String(raw, 2, raw.size - 2, if (le) Charsets.UTF_16LE else Charsets.UTF_16BE)
                    }
                }
                2 -> String(raw, Charsets.UTF_16BE)
                3 -> String(raw, Charsets.UTF_8)
                else -> String(raw, Charsets.ISO_8859_1)
            }.trim().trim('\u0000')
        }.getOrDefault("")
    }

    /** ID3 USLT 未同步歌词帧：encoding(1) + language(3) + descriptor(null terminated) + lyrics */
    private fun decodeId3Uslt(data: ByteArray, start: Int, size: Int): String {
        if (size <= 4) return ""
        val encoding = data[start].toInt() and 0xFF
        var p = start + 4
        val end = start + size
        // 跳过以 null 结尾的 content descriptor 描述符
        if (encoding == 1 || encoding == 2) {
            while (p + 1 < end) {
                if (data[p] == 0.toByte() && data[p + 1] == 0.toByte()) {
                    p += 2
                    break
                }
                p += 2
            }
        } else {
            while (p < end) {
                if (data[p] == 0.toByte()) {
                    p += 1
                    break
                }
                p += 1
            }
        }
        if (p >= end) return ""
        val raw = data.copyOfRange(p, end)
        return runCatching {
            val text = when (encoding) {
                1 -> {
                    if (raw.size >= 2) {
                        val le = raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()
                        val body = if ((raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) ||
                            (raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte())
                        ) {
                            raw.copyOfRange(2, raw.size)
                        } else raw
                        String(body, if (le) Charsets.UTF_16LE else Charsets.UTF_16BE)
                    } else ""
                }
                2 -> String(raw, Charsets.UTF_16BE)
                3 -> String(raw, Charsets.UTF_8)
                else -> String(raw, Charsets.ISO_8859_1)
            }
            text.trim().trim('\u0000')
        }.getOrDefault("")
    }

    /** ID3 文本帧：encoding(1) + 文本。encoding 1/2 为 UTF-16，需处理 BOM。 */
    private fun decodeId3Text(data: ByteArray, start: Int, size: Int): String {
        if (size <= 1) return ""
        val encoding = data[start].toInt() and 0xFF
        val from = start + 1
        val to = start + size
        val raw = data.copyOfRange(from, to)
        return runCatching {
            val text = when (encoding) {
                1 -> {
                    // UTF-16 with BOM：手动判端序，避免 Java charset 对奇数字节的处理差异
                    if (raw.size >= 2) {
                        val le = raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()
                        val body = raw.copyOfRange(2, raw.size)
                        String(body, if (le) Charsets.UTF_16LE else Charsets.UTF_16BE)
                    } else ""
                }
                2 -> String(raw, Charsets.UTF_16BE)
                3 -> String(raw, Charsets.UTF_8)
                else -> String(raw, Charsets.ISO_8859_1)
            }
            text.trim().trim('\u0000')
        }.getOrDefault("")
    }

    private fun hasId3v1(raf: RandomAccessFile, fileSize: Long): Boolean {
        val tag = ByteArray(3)
        raf.seek(fileSize - 128)
        return raf.read(tag) == 3 && readAscii(tag, 0, 3) == "TAG"
    }

    private class MpegFrame(
        val bitrateKbps: Int,
        val sampleRateHz: Int,
        val samplesPerFrame: Int,
        val channels: Int,
        val offset: Long,
        val headerSize: Int,
    )

    /** 从 [from] 起找第一个合法的 MPEG 音频帧头。 */
    private fun findMpegFrame(raf: RandomAccessFile, from: Long): MpegFrame? {
        val buf = ByteArray(4)
        var pos = from
        val limit = minOf(from + 64 * 1024, raf.length() - 4)
        while (pos <= limit) {
            raf.seek(pos)
            if (raf.read(buf) != 4) return null
            if (isMpegFrameStart(buf, 0)) {
                parseMpegHeader(buf, pos)?.let { return it }
            }
            pos++
        }
        return null
    }

    private fun isMpegFrameStart(data: ByteArray, at: Int): Boolean {
        if (at + 1 >= data.size) return false
        return (data[at].toInt() and 0xFF) == 0xFF && (data[at + 1].toInt() and 0xE0) == 0xE0
    }

    private fun parseMpegHeader(h: ByteArray, offset: Long): MpegFrame? {
        val b1 = h[1].toInt() and 0xFF
        val b2 = h[2].toInt() and 0xFF
        val versionBits = (b1 shr 3) and 0x03 // 3=MPEG1, 2=MPEG2, 0=MPEG2.5
        val layerBits = (b1 shr 1) and 0x03 // 1=LayerIII, 2=LayerII, 3=LayerI
        if (layerBits == 0) return null
        val bitrateIndex = (b2 shr 4) and 0x0F
        val sampleRateIndex = (b2 shr 2) and 0x03
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null

        val layer = when (layerBits) { 3 -> 1; 2 -> 2; else -> 3 }
        val isMpeg1 = versionBits == 3
        val bitrate = when {
            isMpeg1 && layer == 1 -> BITRATES_V1L1
            isMpeg1 && layer == 2 -> BITRATES_V1L2
            isMpeg1 -> BITRATES_V1L3
            layer == 1 -> BITRATES_V2L1
            else -> BITRATES_V2L23
        }.getOrNull(bitrateIndex) ?: return null
        if (bitrate <= 0) return null

        val sampleRate = when (versionBits) {
            3 -> SAMPLE_RATES_V1
            2 -> SAMPLE_RATES_V2
            0 -> SAMPLE_RATES_V25
            else -> return null
        }.getOrNull(sampleRateIndex) ?: return null

        val samplesPerFrame = when {
            layer == 1 -> 384
            isMpeg1 -> 1152
            else -> 576
        }
        val channelMode = (h[3].toInt() and 0xC0) shr 6
        val channels = if (channelMode == 3) 1 else 2

        // 帧头后到 Xing/Info 的偏移：MPEG1 立体声 32 字节，单声道 17 字节，MPEG2 减半
        val sideInfo = if (isMpeg1) (if (channels == 1) 17 else 32) else (if (channels == 1) 9 else 17)
        return MpegFrame(bitrate, sampleRate, samplesPerFrame, channels, offset, 4 + sideInfo)
    }

    /** 读取 Xing / Info / VBRI 头里的帧数；没有返回 null。 */
    private fun readXingFrames(raf: RandomAccessFile, frame: MpegFrame): Int? {
        val buf = ByteArray(minOf(200, 4 + 200))
        val at = frame.offset + frame.headerSize
        if (at + 12 > raf.length()) return null
        raf.seek(at)
        val read = raf.read(buf)
        if (read < 12) return null
        val tag = readAscii(buf, 0, 4) ?: return null
        return when (tag) {
            "Xing", "Info" -> {
                val flags = be32(buf, 4)
                if (flags and 0x01 == 0) return null // 无 frames 字段
                be32(buf, 8).takeIf { it > 0 }
            }
            "VBRI" -> {
                // VBRI: "VBRI"(4) version(2) delay(2) quality(2) bytes(4) frames(4)
                if (read < 26) return null
                be32(buf, 14).takeIf { it > 0 }
            }
            else -> null
        }
    }

    private val BITRATES_V1L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val BITRATES_V1L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val BITRATES_V1L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATES_V2L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val BITRATES_V2L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val SAMPLE_RATES_V1 = intArrayOf(44100, 48000, 32000)
    private val SAMPLE_RATES_V2 = intArrayOf(22050, 24000, 16000)
    private val SAMPLE_RATES_V25 = intArrayOf(11025, 12000, 8000)

    // ==================== FLAC ====================

    private fun readFlac(raf: RandomAccessFile): AudioTagInfo? {
        var durationMs = 0L
        var sampleRate: Int? = null
        var bitDepth: Int? = null
        var channels: Int? = null
        var hasCover = false
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var year: Int? = null
        var track: Int? = null
        var disc: Int? = null
        var lyrics: String? = null

        // 元数据块按声明长度**逐个读**，而不是在 96KB 头部窗口里找。
        // 一张 1000×1000 的 PICTURE 就有数百 KB，排在它后面的 VORBIS_COMMENT（歌词所在）
        // 会整个落在窗口之外 —— 直接后果是「文件里明明有 LYRICS 标签，应用读不到」。
        var pos = 4L // 跳过 "fLaC"
        var guard = 0
        val fileSize = raf.length()
        while (pos + 4 <= fileSize && guard++ < 64) {
            val header = ByteArray(4)
            raf.seek(pos)
            if (raf.read(header) != 4) break
            val blockType = header[0].toInt() and 0x7F
            val isLast = header[0].toInt() and 0x80 != 0
            val length = (header[1].toInt() and 0xFF shl 16) or
                (header[2].toInt() and 0xFF shl 8) or
                (header[3].toInt() and 0xFF)
            if (length <= 0) break
            val contentStart = pos + 4
            if (contentStart + length > fileSize) break
            // 只为解析所必需的两个块把内容读进来；封面等大块直接跳过。
            val content = if (blockType == 0 || blockType == 4) {
                ByteArray(length).also { raf.seek(contentStart); raf.readFully(it) }
            } else null

            when (blockType) {
                0 -> { // STREAMINFO
                    val data = content
                    if (data != null && data.size >= 18) {
                        sampleRate = bits(data, 10, 0, 20).toInt()
                        channels = bits(data, 10, 20, 3).toInt() + 1
                        bitDepth = bits(data, 10, 23, 5).toInt() + 1
                        val totalSamples = bits(data, 10, 28, 36)
                        if (sampleRate > 0 && totalSamples > 0) {
                            durationMs = totalSamples * 1000L / sampleRate
                        }
                    }
                }
                4 -> { // VORBIS_COMMENT
                    val data = content ?: break
                    val comments = parseVorbisComments(data, 0, data.size)
                    title = comments["TITLE"]
                    artist = comments["ARTIST"]
                    album = comments["ALBUM"]
                    albumArtist = comments["ALBUMARTIST"]
                    genre = normalizeGenre(comments["GENRE"])
                    year = comments["DATE"]?.take(4)?.toIntOrNull()
                        ?: comments["YEAR"]?.take(4)?.toIntOrNull()
                    track = comments["TRACKNUMBER"]?.substringBefore('/')?.toIntOrNull()
                    disc = comments["DISCNUMBER"]?.substringBefore('/')?.toIntOrNull()
                    lyrics = pickLyrics(comments)
                }
                6 -> hasCover = true // PICTURE
            }
            if (isLast) break
            pos = contentStart + length
        }

        return AudioTagInfo(
            title = title, artist = artist, album = album, albumArtist = albumArtist,
            genre = genre, year = year, trackNumber = track, discNumber = disc,
            durationMs = durationMs,
            // FLAC 是无损：标称码率由位深/采样率/声道推出，VBR 意义不大但 UI 需要个量级
            bitrateKbps = if (sampleRate != null && bitDepth != null && channels != null) {
                (sampleRate * bitDepth * channels) / 1000
            } else null,
            sampleRateHz = sampleRate, bitDepth = bitDepth, channels = channels,
            hasEmbeddedCover = hasCover, codec = "FLAC",
            lyrics = lyrics,
        )
    }

    /** Vorbis Comment：vendorLen(4 LE) + vendor + count(4 LE) + [len(4 LE) + "K=V"]… */
    private fun parseVorbisComments(data: ByteArray, from: Int, to: Int): Map<String, String> {
        val out = HashMap<String, String>(16)
        runCatching {
            var p = from
            if (p + 8 > to) return out
            val vendorLen = le32(data, p); p += 4 + vendorLen
            if (p + 4 > to) return out
            val count = le32(data, p); p += 4
            repeat(count.coerceAtMost(256)) {
                if (p + 4 > to) return@repeat
                val len = le32(data, p); p += 4
                if (len < 0 || p + len > to) return@repeat
                val entry = String(data, p, len, Charsets.UTF_8)
                p += len
                val eq = entry.indexOf('=')
                if (eq > 0) out[entry.substring(0, eq).uppercase()] = entry.substring(eq + 1)
            }
        }
        return out
    }

    // ==================== MP4 / M4A ====================

    private fun readMp4(raf: RandomAccessFile): AudioTagInfo? {
        val moov = findTopAtom(raf, "moov", MAX_MOOV_BYTES) ?: return null
        var durationMs = 0L
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var year: Int? = null
        var track: Int? = null
        var disc: Int? = null
        var hasCover = false
        var lyrics: String? = null

        // `moov` 的字节里第一个原子就是它自己的 8 字节头，子原子从第 8 字节开始。
        // 从 0 找会把 `moov` 自己当成第一个子项，后面全部错位 —— 表现为时长与所有
        // `udta/meta/ilst` 标签都读不到。
        val moovContent = 8

        // mvhd：时长
        val mvhd = childAtom(moov, moovContent, moov.size, "mvhd")
        if (mvhd != null) {
            val (start, end) = mvhd
            if (end - start >= 20) {
                val version = moov[start].toInt() and 0xFF
                val timescale: Int
                val duration: Long
                if (version == 1 && end - start >= 32) {
                    timescale = be32(moov, start + 20)
                    duration = (be32(moov, start + 24).toLong() shl 32) or
                        (be32(moov, start + 28).toLong() and 0xFFFFFFFFL)
                } else {
                    timescale = be32(moov, start + 12)
                    duration = be32(moov, start + 16).toLong() and 0xFFFFFFFFL
                }
                if (timescale > 0 && duration > 0) durationMs = duration * 1000L / timescale
            }
        }

        // udta > meta > ilst
        val udta = childAtom(moov, moovContent, moov.size, "udta")
        // 少数封装把 `meta` 直接挂在 `moov` 下（不是 `udta > meta`），两条路都试。
        val meta = udta?.let { (start, end) -> childAtom(moov, start, end, "meta") }
            ?: childAtom(moov, moovContent, moov.size, "meta")
        // `meta` 是 **full box**：内容开头还有 4 字节 version/flags。
        // 不跳过它们，childAtom 会把 0 当成原子长度直接返回 null ——
        // 结果是所有标准 m4a 的内嵌歌词都读不到（`©lyr` 永远为 null）。
        val metaContent = meta?.let { (start, end) ->
            val contentStart = start + 4
            if (contentStart < end) contentStart to end else null
        }
        val ilst = metaContent?.let { (start, end) -> childAtom(moov, start, end, "ilst") }
        if (ilst != null) {
            var pos = ilst.first
            val end = ilst.second
            var guard = 0
            while (pos + 8 <= end && guard++ < 128) {
                val size = be32(moov, pos).toLong() and 0xFFFFFFFFL
                if (size < 8) break
                val type = readAscii(moov, pos + 4, 4) ?: break
                val itemEnd = minOf(pos + size, end.toLong()).toInt()
                val data = childAtom(moov, pos + 8, itemEnd, "data")
                if (data != null) {
                    // data 原子：8B 头 + 4B type indicator + 4B locale，之后才是值
                    val dataType = if (data.first + 4 <= data.second) be32(moov, data.first) else 0
                    val valueStart = minOf(data.first + 8, data.second)
                    val value = moov.copyOfRange(valueStart, data.second)
                    val text = when (dataType) {
                        // 1 = UTF-8，2 = UTF-16BE；部分工具写 0（隐式 UTF-8）
                        2 -> String(value, Charsets.UTF_16BE)
                        else -> String(value, Charsets.UTF_8)
                    }.trim().trim('\u0000')
                    when (type) {
                        "\u00A9nam" -> title = text
                        "\u00A9ART" -> artist = text
                        "\u00A9alb" -> album = text
                        "aART" -> albumArtist = text
                        "\u00A9gen" -> genre = normalizeGenre(text)
                        "\u00A9day" -> year = text.take(4).toIntOrNull()
                        // 歌词除了 `©lyr`，还有 `©LYR`（大小写变体）与 `lyr `。
                        "\u00A9lyr", "\u00A9LYR", "lyr " -> lyrics = text.takeIf { it.isNotBlank() }
                        "\u00A9cmt", "desc" -> if (lyrics == null && text.contains('[')) {
                            // 少数工具把 LRC 塞进注释；只在形似 LRC 时采用，避免把普通注释当歌词。
                            lyrics = text.takeIf { LRC_TAG.containsMatchIn(it) }
                        }
                        "covr" -> hasCover = true
                        "trkn" -> if (value.size >= 4) {
                            track = ((value[2].toInt() and 0xFF shl 8) or (value[3].toInt() and 0xFF))
                        }
                        "disk" -> if (value.size >= 4) {
                            disc = ((value[2].toInt() and 0xFF shl 8) or (value[3].toInt() and 0xFF))
                        }
                    }
                }
                pos = itemEnd
            }
        }

        return AudioTagInfo(
            title = title, artist = artist, album = album, albumArtist = albumArtist,
            genre = genre, year = year, trackNumber = track, discNumber = disc,
            durationMs = durationMs,
            bitrateKbps = null, // MP4 的码率要遍历 stsz/stco 统计，成本高，交给 UI 用文件大小估算
            sampleRateHz = null, hasEmbeddedCover = hasCover,
            codec = "AAC",
            lyrics = lyrics,
        )
    }

    /** 在顶层找 [name] 原子并完整读入内存；[maxBytes] 防止异常文件撑爆堆。 */
    private fun findTopAtom(raf: RandomAccessFile, name: String, maxBytes: Int): ByteArray? {
        var pos = 0L
        val head = ByteArray(8)
        val limit = raf.length()
        var guard = 0
        while (pos + 8 <= limit && guard++ < 64) {
            raf.seek(pos)
            if (raf.read(head) != 8) return null
            var size = be32(head, 0).toLong() and 0xFFFFFFFFL
            val type = readAscii(head, 4, 4) ?: return null
            if (size == 1L) { // 64 位扩展尺寸
                val ext = ByteArray(8)
                raf.seek(pos + 8)
                if (raf.read(ext) != 8) return null
                size = ((ext[0].toLong() and 0xFF shl 56) or (ext[1].toLong() and 0xFF shl 48) or
                    (ext[2].toLong() and 0xFF shl 40) or (ext[3].toLong() and 0xFF shl 32) or
                    (ext[4].toLong() and 0xFF shl 24) or (ext[5].toLong() and 0xFF shl 16) or
                    (ext[6].toLong() and 0xFF shl 8) or (ext[7].toLong() and 0xFF))
            }
            if (size < 8) return null
            if (type == name) {
                val cap = minOf(size, maxBytes.toLong()).toInt()
                val buf = ByteArray(cap)
                raf.seek(pos)
                val n = raf.read(buf)
                return if (n <= 8) null else buf.copyOf(n)
            }
            pos += size
        }
        return null
    }

    /** 在 [data] 的 [from, until) 内找直接子原子 [name]，返回 (内容起点, 内容终点)。 */
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

    // ==================== OGG / Opus ====================

    private fun readOgg(raf: RandomAccessFile): AudioTagInfo? {
        // 前两个 packet（识别头 + 注释头）按**页**流式读：
        // 注释 packet 可能跨页，封面的 BASE64（METADATA_BLOCK_PICTURE）经常几 MB，
        // 只读 96KB 头部窗口会把排在封面之后的 LYRICS 截掉。
        val packets = readOggPackets(raf, maxPackets = 2)
        if (packets.isEmpty()) return null

        val first = packets[0]
        val isOpus = first.size >= 8 && readAscii(first, 0, 8) == "OpusHead"
        val isVorbis = first.size >= 7 && first[0].toInt() == 1 && readAscii(first, 1, 6) == "vorbis"

        var channels: Int? = null
        var sampleRate: Int? = null
        when {
            isOpus -> {
                if (first.size >= 12) {
                    channels = first[9].toInt() and 0xFF
                    sampleRate = le32(first, 12) // OpusHead 记的是输入采样率，解码恒为 48k
                }
            }
            isVorbis -> {
                if (first.size >= 12) {
                    channels = first[11].toInt() and 0xFF
                    sampleRate = le32(first, 12)
                }
            }
            else -> return null
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var year: Int? = null
        var track: Int? = null
        var disc: Int? = null
        var lyrics: String? = null
        if (packets.size >= 2) {
            val comments = when {
                isOpus -> {
                    val c = packets[1]
                    if (c.size >= 8 && readAscii(c, 0, 8) == "OpusTags") {
                        parseVorbisComments(c, 8, c.size)
                    } else emptyMap()
                }
                else -> parseVorbisComments(packets[1], 7, packets[1].size)
            }
            title = comments["TITLE"]
            artist = comments["ARTIST"]
            album = comments["ALBUM"]
            albumArtist = comments["ALBUMARTIST"]
            genre = normalizeGenre(comments["GENRE"])
            year = comments["DATE"]?.take(4)?.toIntOrNull()
            track = comments["TRACKNUMBER"]?.substringBefore('/')?.toIntOrNull()
            disc = comments["DISCNUMBER"]?.substringBefore('/')?.toIntOrNull()
            lyrics = pickLyrics(comments)
        }

        // 时长：最后一个页面的 granule position（Opus 恒按 48kHz 计）
        var durationMs = 0L
        val granule = lastOggGranule(raf)
        if (granule != null && granule > 0) {
            val rate = if (isOpus) 48000 else (sampleRate ?: 44100)
            if (rate > 0) durationMs = granule * 1000L / rate
        }

        return AudioTagInfo(
            title = title, artist = artist, album = album, albumArtist = albumArtist,
            genre = genre, year = year, trackNumber = track, discNumber = disc,
            durationMs = durationMs,
            bitrateKbps = null,
            sampleRateHz = sampleRate, channels = channels,
            hasEmbeddedCover = false, // OGG 的封面在 METADATA_BLOCK_PICTURE 里，罕见，不解析
            codec = if (isOpus) "Opus" else "Vorbis",
            lyrics = lyrics,
        )
    }

    /** 从 OGG 页面流里拼出前 [maxPackets] 个 packet（跨页拼接）。 */
    private fun readOggPackets(raf: RandomAccessFile, maxPackets: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>(maxPackets)
        val current = ArrayList<Byte>(4096)
        var pos = 0L
        val fileSize = raf.length()
        var guard = 0
        val header = ByteArray(27)
        while (pos + 27 <= fileSize && out.size < maxPackets && guard++ < MAX_OGG_PAGES) {
            raf.seek(pos)
            if (raf.read(header) != 27) break
            if (readAscii(header, 0, 4) != "OggS") break
            val segmentCount = header[26].toInt() and 0xFF
            val table = ByteArray(segmentCount)
            if (segmentCount > 0 && raf.read(table) != segmentCount) break
            var pageSize = 27 + segmentCount
            for (i in 0 until segmentCount) pageSize += table[i].toInt() and 0xFF
            // 整页数据一次读入：同一页里可能有多个 packet，逐段 seek 会算错偏移。
            val page = ByteArray(pageSize - 27 - segmentCount)
            if (page.isNotEmpty() && raf.read(page) != page.size) break
            var cursor = 0
            for (i in 0 until segmentCount) {
                val segLen = table[i].toInt() and 0xFF
                val end = minOf(cursor + segLen, page.size)
                for (j in cursor until end) current.add(page[j])
                cursor = end
                if (segLen < 255) { // 小于 255 表示该 packet 在此结束
                    out.add(current.toByteArray())
                    current.clear()
                    if (out.size >= maxPackets) break
                }
            }
            pos += pageSize
        }
        return out
    }

    /** 从文件尾回溯最后一个 OGG 页面，取 granule position（8 字节 LE，偏移 6）。 */
    private fun lastOggGranule(raf: RandomAccessFile): Long? {
        val fileSize = raf.length()
        val window = minOf(TAIL_BYTES.toLong(), fileSize).toInt()
        if (window < 32) return null
        val buf = ByteArray(window)
        raf.seek(fileSize - window)
        if (raf.read(buf) < 32) return null
        for (i in buf.size - 32 downTo 0) {
            if (readAscii(buf, i, 4) == "OggS") {
                var g = 0L
                for (k in 0 until 8) {
                    g = g or ((buf[i + 6 + k].toLong() and 0xFF) shl (8 * k))
                }
                return g
            }
        }
        return null
    }

    // ==================== WAV ====================

    private fun readWav(head: ByteArray): AudioTagInfo? {
        var sampleRate: Int? = null
        var channels: Int? = null
        var bitDepth: Int? = null
        var byteRate = 0
        var dataBytes = 0L

        var pos = 12
        var guard = 0
        while (pos + 8 <= head.size && guard++ < 128) {
            val id = readAscii(head, pos, 4) ?: break
            val size = le32(head, pos + 4).toLong() and 0xFFFFFFFFL
            val contentStart = pos + 8
            when (id) {
                "fmt " -> {
                    if (contentStart + 16 <= head.size) {
                        channels = le16(head, contentStart + 2)
                        sampleRate = le32(head, contentStart + 4)
                        byteRate = le32(head, contentStart + 8)
                        bitDepth = le16(head, contentStart + 14)
                    }
                }
                "data" -> dataBytes = size
            }
            // 用 Long 推进：data chunk 常达数百 MB，直接 toInt() 会溢出成负值导致死循环
            val next = contentStart.toLong() + size
            if (next > head.size) break
            pos = next.toInt()
        }

        val durationMs = if (byteRate > 0 && dataBytes > 0) dataBytes * 1000L / byteRate else 0L
        val rate = sampleRate
        val depth = bitDepth
        val ch = channels
        return AudioTagInfo(
            durationMs = durationMs,
            bitrateKbps = if (rate != null && depth != null && ch != null) rate * depth * ch / 1000 else null,
            sampleRateHz = rate, bitDepth = depth, channels = ch,
            codec = "PCM",
        )
    }

    // ==================== 工具 ====================

    /**
     * Vorbis Comment / MP4 ilst 里的歌词字段。
     *
     * 不同打标工具的字段名不统一：`LYRICS` 最常见，`UNSYNCEDLYRICS` / `SYNCEDLYRICS`
     * 也都有实际文件在用。逐个尝试比只认一个的命中率高得多。
     */
    private fun pickLyrics(comments: Map<String, String>): String? =
        listOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS", "LYRIC")
            .firstNotNullOfOrNull { comments[it]?.takeIf { text -> text.isNotBlank() } }

    /**
     * 读取文件尾的 APE 标签（`APETAGEX`）里的歌词项。
     *
     * MP3 常同时带 ID3 与 APE 两套标签；不少打标工具只写 APE 的 `Lyrics`，
     * 于是「ID3 里没有 USLT」被误判成「这首歌没有内嵌歌词」。
     */
    private fun readApeLyrics(raf: RandomAccessFile): String? = runCatching {
        val fileSize = raf.length()
        if (fileSize < 64) return@runCatching null
        // 页脚 32 字节：preamble(8) version(4) size(4) itemCount(4) flags(4) reserved(8)
        val footerSize = 32
        // APE 标签后可能还跟着 128 字节 ID3v1；两种落点都试。
        val footerStarts = listOf(fileSize - footerSize, fileSize - 128 - footerSize)
        var footerAt = -1L
        var footer: ByteArray? = null
        for (start in footerStarts) {
            if (start < 0) continue
            val candidate = ByteArray(footerSize)
            raf.seek(start)
            if (raf.read(candidate) != footerSize) continue
            if (readAscii(candidate, 0, 8) == "APETAGEX") {
                footerAt = start
                footer = candidate
                break
            }
        }
        val foot = footer ?: return@runCatching null
        val size = le32(foot, 12)
        if (size <= 0 || size > 16 * 1024 * 1024) return@runCatching null
        // tags size 含 footer 与 items，不含 header
        val bodySize = (size - footerSize).coerceAtLeast(0)
        if (bodySize <= 0 || bodySize > fileSize) return@runCatching null
        val body = ByteArray(bodySize)
        raf.seek(footerAt - bodySize)
        if (raf.read(body) != bodySize) return@runCatching null
        var p = 0
        while (p + 8 <= body.size) {
            val valueSize = le32(body, p)
            p += 4
            var flags = 0
            for (k in 0 until 4) flags = flags or ((body[p + k].toInt() and 0xFF) shl (8 * k))
            p += 4
            if (valueSize <= 0 || p + valueSize > body.size) break
            var keyEnd = p
            while (keyEnd < p + valueSize && body[keyEnd] != 0.toByte()) keyEnd++
            if (keyEnd >= p + valueSize) break
            val key = String(body, p, keyEnd - p, Charsets.ISO_8859_1).lowercase()
            val valueStart = keyEnd + 1
            if (key in APE_LYRIC_KEYS && valueStart < p + valueSize) {
                return@runCatching String(body, valueStart, p + valueSize - valueStart, Charsets.UTF_8)
                    .trim().trim('\u0000').takeIf { it.isNotBlank() }
            }
            p += valueSize
        }
        null
    }.getOrNull()

    private val APE_LYRIC_KEYS = setOf("lyrics", "unsyncedlyrics", "syncedlyrics", "lyric")

    /**
     * 流派归一化：ID3v1 的数字索引（`(17)`、`17`）要还原成文字，
     * 否则列表里会出现一堆 `(32)` 这种没人看得懂的流派。
     */
    private fun normalizeGenre(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val digits = text.trim('(', ')')
        val index = digits.toIntOrNull()
        if (index != null && index in 0..191) return ID3_GENRES.getOrElse(index) { text }
        return text
    }

    private fun isAscii(data: ByteArray, at: Int, expected: String): Boolean {
        if (at + expected.length > data.size) return false
        for (i in expected.indices) {
            if (data[at + i] != expected[i].code.toByte()) return false
        }
        return true
    }

    private fun readAscii(data: ByteArray, at: Int, length: Int): String? {
        if (at < 0 || at + length > data.size) return null
        return String(data, at, length, Charsets.ISO_8859_1)
    }

    /** 从 [startByte] 的第 0 位起按大端位序读 [n] 位（FLAC STREAMINFO 用）。 */
    private fun bits(data: ByteArray, startByte: Int, bitOffset: Int, n: Int): Long {
        var result = 0L
        for (i in 0 until n) {
            val bitIndex = bitOffset + i
            val idx = startByte + bitIndex / 8
            if (idx >= data.size) break
            val bit = (data[idx].toInt() and 0xFF shr (7 - (bitIndex % 8))) and 1
            result = (result shl 1) or bit.toLong()
        }
        return result
    }

    private fun be32(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF shl 24) or (data[at + 1].toInt() and 0xFF shl 16) or
            (data[at + 2].toInt() and 0xFF shl 8) or (data[at + 3].toInt() and 0xFF)

    private fun le32(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or (data[at + 1].toInt() and 0xFF shl 8) or
            (data[at + 2].toInt() and 0xFF shl 16) or (data[at + 3].toInt() and 0xFF shl 24)

    private fun le16(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or (data[at + 1].toInt() and 0xFF shl 8)

    private fun syncsafe(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0x7F shl 21) or (data[at + 1].toInt() and 0x7F shl 14) or
            (data[at + 2].toInt() and 0x7F shl 7) or (data[at + 3].toInt() and 0x7F)

    /** ID3v1 流派表（前 79 项为 Winamp 标准表，其后为 Winamp 扩展）。 */
    private val ID3_GENRES = arrayOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge",
        "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B",
        "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska",
        "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop",
        "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental",
        "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise", "Alternative Rock",
        "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop",
        "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial",
        "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy",
        "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle",
        "Native US", "Cabaret", "New Wave", "Psychedelic", "Rave",
        "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz",
        "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )
}
