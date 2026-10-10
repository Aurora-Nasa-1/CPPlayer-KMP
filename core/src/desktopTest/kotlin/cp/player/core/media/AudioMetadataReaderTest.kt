package cp.player.core.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AudioMetadataReader] 的行为测试。
 *
 * 样本文件在测试里用字节**现场构造**（而不是提交二进制素材）：一是仓库里不必夹带
 * 音频文件，二是构造过程本身就是对格式布局的一份可执行文档 —— 若哪天解析器的
 * 偏移量改了，这里的构造代码会立刻对不上。
 */
class AudioMetadataReaderTest {

    // ============ WAV：时长 / 采样率 / 位深 / 声道 ============

    @Test
    fun `WAV 读取时长与流参数`() {
        val file = tempFile("sample.wav").apply { writeBytes(pcmWav(seconds = 0.5, sampleRate = 44100)) }
        val info = AudioMetadataReader.read(file.absolutePath)
        assertNotNull(info)
        assertEquals("PCM", info.codec)
        assertEquals(44100, info.sampleRateHz)
        assertEquals(16, info.bitDepth)
        assertEquals(1, info.channels)
        // 44100Hz × 16bit × 1ch，0.5 秒
        assertEquals(500L, info.durationMs)
        file.delete()
    }

    @Test
    fun `WAV 立体声 48k 也能算对`() {
        val file = tempFile("stereo.wav").apply { writeBytes(pcmWav(seconds = 1.0, sampleRate = 48000, channels = 2)) }
        val info = AudioMetadataReader.read(file.absolutePath)
        assertNotNull(info)
        assertEquals(48000, info.sampleRateHz)
        assertEquals(2, info.channels)
        assertEquals(1000L, info.durationMs)
        file.delete()
    }

    // ============ ID3v2：标签解析 ============

    @Test
    fun `MP3 的 ID3v2 标签能被解析`() {
        val file = tempFile("tagged.mp3").apply {
            // 只有标签、没有有效音频帧：duration 允许为 0，但文本标签必须解出来
            writeBytes(id3v2Tags(mapOf("TIT2" to "测试歌曲", "TPE1" to "某人", "TALB" to "某专辑")))
        }
        val info = AudioMetadataReader.read(file.absolutePath)
        assertNotNull(info)
        assertEquals("测试歌曲", info.title)
        assertEquals("某人", info.artist)
        assertEquals("某专辑", info.album)
        assertEquals("MP3", info.codec)
        file.delete()
    }

    @Test
    fun `ID3v2 的年份 轨号 碟号 与流派数字索引`() {
        val file = tempFile("meta.mp3").apply {
            writeBytes(
                id3v2Tags(
                    mapOf(
                        "TYER" to "1998",
                        "TRCK" to "7/12",
                        "TPOS" to "2/2",
                        // 17 = Rock（ID3v1 流派表索引），应还原成文字而不是留 "(17)"
                        "TCON" to "(17)",
                    ),
                ),
            )
        }
        val info = AudioMetadataReader.read(file.absolutePath)
        assertNotNull(info)
        assertEquals(1998, info.year)
        assertEquals(7, info.trackNumber)
        assertEquals(2, info.discNumber)
        assertEquals("Rock", info.genre)
        file.delete()
    }

    @Test
    fun `APIC 帧会让 hasEmbeddedCover 为真`() {
        val file = tempFile("cover.mp3").apply {
            writeBytes(id3v2Frames(textFrame("TIT2", "有封面"), apicFrame(pngBytes())))
        }
        val info = AudioMetadataReader.read(file.absolutePath)
        assertNotNull(info)
        assertTrue(info.hasEmbeddedCover)
        file.delete()
    }

    // ============ 健壮性 ============

    @Test
    fun `不存在的文件返回 null 而不抛异常`() {
        assertNull(AudioMetadataReader.read("/definitely/not/here/nope.mp3"))
    }

    @Test
    fun `非音频内容返回 null`() {
        val file = tempFile("readme.mp3").apply { writeText("这不是音频，只是一段文本。") }
        assertNull(AudioMetadataReader.read(file.absolutePath))
        file.delete()
    }

    @Test
    fun `被截断的 WAV 不抛异常`() {
        val full = pcmWav(seconds = 0.2, sampleRate = 44100)
        val file = tempFile("truncated.wav").apply { writeBytes(full.copyOf(30)) }
        // 只要求不抛；能读到什么算什么
        runCatching { AudioMetadataReader.read(file.absolutePath) }
        file.delete()
    }

    // ============ 样本构造 ============

    private fun tempFile(name: String): File =
        File.createTempFile("cpplayer-meta-", "-$name")

    /** 最小可解析的 PCM WAV（44 字节头 + 静音数据）。 */
    private fun pcmWav(seconds: Double, sampleRate: Int, channels: Int = 1, bits: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val dataSize = (byteRate * seconds).toInt()
        val out = ByteArray(44 + dataSize)
        var p = 0
        fun ascii(s: String) { s.forEach { out[p++] = it.code.toByte() } }
        fun le32(v: Int) { repeat(4) { out[p++] = (v shr (8 * it)).toByte() } }
        fun le16(v: Int) { repeat(2) { out[p++] = (v shr (8 * it)).toByte() } }

        ascii("RIFF"); le32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); le32(16)
        le16(1)          // PCM
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(channels * bits / 8) // blockAlign
        le16(bits)
        ascii("data"); le32(dataSize)
        return out
    }

    /** 由若干文本帧拼出一个完整的 ID3v2.3 文件头 + 帧体。 */
    private fun id3v2Tags(tags: Map<String, String>): ByteArray =
        id3v2Frames(*tags.map { (id, value) -> textFrame(id, value) }.toTypedArray())

    /** 拼接若干帧体，并包上 ID3v2.3 容器头。 */
    private fun id3v2Frames(vararg frames: ByteArray): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        frames.forEach { body.write(it) }
        return id3Container(body.toByteArray())
    }

    /**
     * ID3v2.3 文本帧；UTF-8（encoding=3）。
     *
     * 帧大小在 v2.3 里是**大端 32 位**（不是 syncsafe），只有标签总大小才是 syncsafe。
     */
    private fun textFrame(id: String, value: String): ByteArray {
        val text = value.toByteArray(Charsets.UTF_8)
        val size = 1 + text.size
        val frame = ByteArray(10 + size)
        id.forEachIndexed { i, c -> frame[i] = c.code.toByte() }
        frame[4] = (size shr 24).toByte()
        frame[5] = (size shr 16).toByte()
        frame[6] = (size shr 8).toByte()
        frame[7] = size.toByte()
        frame[10] = 3 // encoding = UTF-8
        text.copyInto(frame, 11)
        return frame
    }

    /** APIC 帧：enc + mime\0 + picType + desc\0 + 图像数据。 */
    private fun apicFrame(image: ByteArray): ByteArray {
        val mime = "image/png".toByteArray(Charsets.ISO_8859_1)
        val size = 1 + mime.size + 1 + 1 + 1 + image.size
        val frame = ByteArray(10 + size)
        "APIC".forEachIndexed { i, c -> frame[i] = c.code.toByte() }
        frame[4] = (size shr 24).toByte()
        frame[5] = (size shr 16).toByte()
        frame[6] = (size shr 8).toByte()
        frame[7] = size.toByte()
        var p = 10
        frame[p++] = 0 // encoding = ISO-8859-1
        mime.copyInto(frame, p); p += mime.size
        frame[p++] = 0 // mime 终止符
        frame[p++] = 3 // picture type = front cover
        frame[p++] = 0 // 空描述（终止符）
        image.copyInto(frame, p)
        return frame
    }

    /** 拼接 ID3v2 头 + 帧体。 */
    private fun id3Container(frames: ByteArray): ByteArray {
        val out = ByteArray(10 + frames.size)
        out[0] = 'I'.code.toByte(); out[1] = 'D'.code.toByte(); out[2] = '3'.code.toByte()
        out[3] = 3; out[4] = 0; out[5] = 0
        val size = frames.size
        // syncsafe：每字节 7 位
        out[6] = ((size shr 21) and 0x7F).toByte()
        out[7] = ((size shr 14) and 0x7F).toByte()
        out[8] = ((size shr 7) and 0x7F).toByte()
        out[9] = (size and 0x7F).toByte()
        frames.copyInto(out, 10)
        return out
    }

    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0,
    )
}
