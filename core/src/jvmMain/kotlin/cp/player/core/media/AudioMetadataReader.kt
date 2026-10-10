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

    /** 尾部扫描窗口：OGG 的时长需要从最后一个页面的 granule position 反推。 */
    private const val TAIL_BYTES = 64 * 1024

    /**
     * 读取 [path] 的音频元数据；不支持、损坏或 IO 失败一律返回 null。
     */
    fun read(path: String): AudioTagInfo? = runCatching {
        RandomAccessFile(path, "r").use { raf ->
            if (raf.length() <= 0) return null
            val head = ByteArray(minOf(HEAD_BYTES.toLong(), raf.length()).toInt())
            raf.seek(0)
            if (raf.read(head) < 4) return null
            dispatch(raf, head)
        }
    }.getOrNull()

    private fun dispatch(raf: RandomAccessFile, head: ByteArray): AudioTagInfo? {
        if (head.size >= 4 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
            head[2] == '3'.code.toByte()
        ) return readMp3(raf, head)
        if (isAscii(head, 0, "fLaC")) return readFlac(head)
        if (isAscii(head, 0, "OggS")) return readOgg(raf, head)
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

        val tagSize = if (id3Size >= 0) id3Size else parseId3v2(head) { id, text ->
            when (id) {
                "TIT2" -> if (title == null) title = text
                "TPE1" -> if (artist == null) artist = text
                "TALB" -> if (album == null) album = text
                "TPE2" -> if (albumArtist == null) albumArtist = text
                "TCON" -> if (genre == null) genre = normalizeGenre(text)
                "TYER", "TDRC" -> if (year == null) year = text.take(4).toIntOrNull()
                "TRCK" -> if (track == null) track = text.substringBefore('/').toIntOrNull()
                "TPOS" -> if (disc == null) disc = text.substringBefore('/').toIntOrNull()
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
            val text = if (id == "APIC") "" else decodeId3Text(head, dataStart, frameSize)
            onFrame(id, text)
            pos = dataStart + frameSize
        }
        return size
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

    private fun readFlac(head: ByteArray): AudioTagInfo? {
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

        var offset = 4 // 跳过 "fLaC"
        var guard = 0
        while (offset + 4 <= head.size && guard++ < 64) {
            val blockType = head[offset].toInt() and 0x7F
            val isLast = head[offset].toInt() and 0x80 != 0
            val length = (head[offset + 1].toInt() and 0xFF shl 16) or
                (head[offset + 2].toInt() and 0xFF shl 8) or
                (head[offset + 3].toInt() and 0xFF)
            val contentStart = offset + 4
            val contentEnd = minOf(contentStart + length, head.size)
            if (length <= 0 || contentEnd <= contentStart) break

            when (blockType) {
                0 -> { // STREAMINFO
                    if (contentEnd - contentStart >= 18) {
                        sampleRate = bits(head, contentStart + 10, 0, 20).toInt()
                        channels = bits(head, contentStart + 10, 20, 3).toInt() + 1
                        bitDepth = bits(head, contentStart + 10, 23, 5).toInt() + 1
                        val totalSamples = bits(head, contentStart + 10, 28, 36)
                        if (sampleRate > 0 && totalSamples > 0) {
                            durationMs = totalSamples * 1000L / sampleRate
                        }
                    }
                }
                4 -> { // VORBIS_COMMENT
                    val comments = parseVorbisComments(head, contentStart, contentEnd)
                    title = comments["TITLE"]
                    artist = comments["ARTIST"]
                    album = comments["ALBUM"]
                    albumArtist = comments["ALBUMARTIST"]
                    genre = normalizeGenre(comments["GENRE"])
                    year = comments["DATE"]?.take(4)?.toIntOrNull()
                        ?: comments["YEAR"]?.take(4)?.toIntOrNull()
                    track = comments["TRACKNUMBER"]?.substringBefore('/')?.toIntOrNull()
                    disc = comments["DISCNUMBER"]?.substringBefore('/')?.toIntOrNull()
                }
                6 -> hasCover = true // PICTURE
            }
            if (isLast) break
            offset = contentEnd
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
        val moov = findTopAtom(raf, "moov", 8 * 1024 * 1024) ?: return null
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

        // mvhd：时长
        val mvhd = childAtom(moov, 0, moov.size, "mvhd")
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
        val udta = childAtom(moov, 0, moov.size, "udta")
        val meta = udta?.let { childAtom(moov, it.first, it.second, "meta") }
        val ilst = meta?.let { childAtom(moov, it.first, it.second, "ilst") }
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
                    val valueStart = minOf(data.first + 8, data.second)
                    val value = moov.copyOfRange(valueStart, data.second)
                    when (type) {
                        "\u00A9nam" -> title = String(value, Charsets.UTF_8)
                        "\u00A9ART" -> artist = String(value, Charsets.UTF_8)
                        "\u00A9alb" -> album = String(value, Charsets.UTF_8)
                        "aART" -> albumArtist = String(value, Charsets.UTF_8)
                        "\u00A9gen" -> genre = normalizeGenre(String(value, Charsets.UTF_8))
                        "\u00A9day" -> year = String(value, Charsets.UTF_8).take(4).toIntOrNull()
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

    private fun readOgg(raf: RandomAccessFile, head: ByteArray): AudioTagInfo? {
        // 头部页面里拼出前两个 packet：识别头 + 注释头
        val packets = readOggPackets(head, 0, head.size, maxPackets = 2)
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
        )
    }

    /** 从 OGG 页面流里拼出前 [maxPackets] 个 packet（跨页拼接）。 */
    private fun readOggPackets(data: ByteArray, from: Int, to: Int, maxPackets: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>(maxPackets)
        val current = ArrayList<Byte>(4096)
        var pos = from
        var guard = 0
        while (pos + 27 <= to && out.size < maxPackets && guard++ < 256) {
            if (readAscii(data, pos, 4) != "OggS") break
            val segmentCount = data[pos + 26].toInt() and 0xFF
            val tableStart = pos + 27
            if (tableStart + segmentCount > to) break
            for (i in 0 until segmentCount) {
                val segLen = data[tableStart + i].toInt() and 0xFF
                val start = tableStart + segmentCount + current.size
                val end = minOf(start + segLen, to)
                for (b in start until end) current.add(data[b])
                if (segLen < 255) { // 小于 255 表示该 packet 在此结束
                    out.add(current.toByteArray())
                    current.clear()
                    if (out.size >= maxPackets) break
                }
            }
            var pageSize = 27 + segmentCount
            for (i in 0 until segmentCount) pageSize += data[tableStart + i].toInt() and 0xFF
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
