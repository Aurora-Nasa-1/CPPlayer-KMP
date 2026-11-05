package cp.player.app.warmup

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import cp.player.app.AppModel
import cp.player.app.ui.screen.AudioEffectSettingsScreen
import cp.player.app.ui.screen.HomeScreen
import cp.player.app.ui.screen.MainScreen
import cp.player.app.ui.screen.PlaylistDetailScreen
import cp.player.app.ui.screen.SearchScreen
import cp.player.app.ui.screen.SettingsScreen
import cp.player.app.ui.screen.settingsEntries
import cp.player.app.ui.theme.CoverSeedCache
import cp.player.core.model.Artist
import cp.player.core.model.LyricsInfo
import cp.player.core.model.Playlist
import cp.player.core.model.Song
import cp.player.core.music.PlaylistSummary
import cp.player.core.playback.AudioEffectConfig
import cp.player.core.playback.AudioEffectPreset
import cp.player.core.playback.EqBand
import cp.player.core.playback.EqualizerConfig
import cp.player.core.playback.LyricsParser
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AOT 预热执行器：在 `-Dcp.player.aotWarmup=true` 阶段执行全链路深度热点预热。
 *
 * 覆盖五大核心瓶颈：
 * 1. 序列化与数据层（kotlinx.serialization、Song、Playlist、Artist 等模型编解码与反射元数据）；
 * 2. 音频与 DSP 管线（AudioEffectConfig、10段 EQ、预设与 PlaybackState 流）；
 * 3. 歌词与排版引擎（TTML 逐字、LRC 时标解析、时钟修整与文本 AnnotatedString 排版）；
 * 4. 图像与主题色调（CoverSeedCache、Material 3 Expressive 动态色板生成与度量）；
 * 5. 核心页面 Screen 与设置树（Voyager 核心页面实例、SettingsRegistry 设置入口全量初始化）。
 */
object AotWarmupRunner {

    suspend fun runAllWarmups() = withContext(Dispatchers.Default) {
        val totalMs = measureTimeMillis {
            println("[AOT Warmup] 开始执行全系统深度热点预热...")

            // 1. 序列化与数据模型热点预热
            warmupSerialization()

            // 2. 音频引擎与音效 DSP 热点预热
            warmupAudioPipeline()

            // 3. 歌词解析与排版排布热点预热
            warmupLyricsAndTypography()

            // 4. 图像缓存与 Material 3 动态色彩预热
            warmupGraphicsAndTheme()

            // 5. 核心页面与设置注册表预热
            warmupScreensAndRegistry()
        }
        println("[AOT Warmup] 全系统热点预热完毕，总耗时: ${totalMs}ms")
    }

    private fun warmupSerialization() {
        runCatching {
            val json = Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                prettyPrint = false
            }

            // 预热 Song / Playlist / Artist / LyricLine 序列化描述符与编解码器
            val sampleSong = Song(
                id = "warmup-1001",
                name = "AOT Warmup Melody",
                artist = "CPPlayer Artist",
                artistId = "artist-1",
                album = "AOT Performance Suite",
                albumArtUrl = "https://example.com/cover.jpg",
                durationMs = 240000L
            )
            val songJson = json.encodeToString(sampleSong)
            json.decodeFromString<Song>(songJson)

            val samplePlaylist = Playlist(
                id = 999999L,
                name = "Warmup Playlist",
                coverImgUrl = "https://example.com/playlist.jpg",
                trackCount = 100,
                creatorName = "CPPlayer",
                creatorUserId = 1L
            )
            val playlistJson = json.encodeToString(samplePlaylist)
            json.decodeFromString<Playlist>(playlistJson)

            val sampleArtist = Artist(
                id = 888888L,
                name = "Warmup Artist",
                alias = listOf("AOT", "Leyden")
            )
            val artistJson = json.encodeToString(sampleArtist)
            json.decodeFromString<Artist>(artistJson)

            val sampleLyricsInfo = LyricsInfo(
                source = "AOT-Warmup",
                format = "LRC+TTML",
                hasWordLevel = true,
                hasTranslation = true,
                hasPhonetic = false
            )
            val lyricsJson = json.encodeToString(sampleLyricsInfo)
            json.decodeFromString<LyricsInfo>(lyricsJson)

            println("[AOT Warmup] [1/5] 序列化与核心数据模型预热完成")
        }.onFailure {
            println("[AOT Warmup] [1/5] 序列化预热警告: ${it.message}")
        }
    }

    private fun warmupAudioPipeline() {
        runCatching {
            // 访问 PlaybackController 状态
            val state = AppModel.playback.state.value

            // 预热均衡器与音效配置
            val sampleEq = AudioEffectConfig(
                enabled = true,
                equalizer = EqualizerConfig(
                    enabled = true,
                    bands = listOf(
                        EqBand(gainDb = 2.0f, frequencyHz = 31f),
                        EqBand(gainDb = 1.5f, frequencyHz = 62f),
                        EqBand(gainDb = 0.5f, frequencyHz = 125f),
                        EqBand(gainDb = 0.0f, frequencyHz = 250f),
                        EqBand(gainDb = -0.5f, frequencyHz = 500f),
                        EqBand(gainDb = 0.0f, frequencyHz = 1000f),
                        EqBand(gainDb = 1.0f, frequencyHz = 2000f),
                        EqBand(gainDb = 1.5f, frequencyHz = 4000f),
                        EqBand(gainDb = 2.0f, frequencyHz = 8000f),
                        EqBand(gainDb = 2.5f, frequencyHz = 16000f)
                    )
                )
            )
            val presets = AudioEffectPreset.entries

            println("[AOT Warmup] [2/5] 音频引擎控制流与 DSP 算法类预热完成 (Presets: ${presets.size}, EqEnabled: ${sampleEq.enabled})")
        }.onFailure {
            println("[AOT Warmup] [2/5] 音频管线预热警告: ${it.message}")
        }
    }

    private fun warmupLyricsAndTypography() {
        runCatching {
            // 预热标准 LRC 与 TTML 逐字歌词解析器
            val mockLyricsPayload = Json.parseToJsonElement(
                """
                {
                  "code": 0,
                  "data": {
                    "lyric": "[00:00.00]CPPlayer AOT Warmup Track\n[00:02.50]Ahead of Time Class Loading & Linking\n[00:06.00]Sub-millisecond Generational Shenandoah GC",
                    "tlyric": "[00:00.00]CPPlayer AOT 预热曲目\n[00:02.50]提前类加载与链接固化\n[00:06.00]亚毫秒级分代 Shenandoah 垃圾回收",
                    "ttml": "<tt><body><div><p begin=\"1.000\" end=\"4.000\"><span begin=\"1.000\" end=\"2.000\">Project</span> <span begin=\"2.000\" end=\"4.000\">Leyden</span></p></div></body></tt>"
                  }
                }
                """.trimIndent()
            )
            val parsedLines = LyricsParser.parse(mockLyricsPayload, durationMs = 120000L)

            // 预热 Compose 文本构建器
            val annotated = AnnotatedString.Builder("CPPlayer 高性能文本渲染引擎").apply {
                append(" - AOT Warmup")
            }.toAnnotatedString()

            println("[AOT Warmup] [3/5] 歌词解析引擎与文本排版预热完成 (Lines: ${parsedLines.size}, TextLen: ${annotated.length})")
        }.onFailure {
            println("[AOT Warmup] [3/5] 歌词解析预热警告: ${it.message}")
        }
    }

    private fun warmupGraphicsAndTheme() {
        runCatching {
            // 访问 CoverSeedCache 缓存结构
            CoverSeedCache.get("https://example.com/nonexistent_warmup.jpg")

            // 预热 Material 3 Expressive 动态色板生成算法
            val darkScheme = darkColorScheme(
                primary = Color(0xFFD0BCFF),
                secondary = Color(0xFFCCC2DC),
                tertiary = Color(0xFFEFB8C8),
                surface = Color(0xFF141218),
                surfaceContainer = Color(0xFF211F26),
                surfaceContainerLow = Color(0xFF1D1B20),
                surfaceContainerHigh = Color(0xFF2B2930),
                surfaceContainerHighest = Color(0xFF36343B)
            )

            println("[AOT Warmup] [4/5] 封面图像缓存与 Material 3 调色板预热完成 (ThemePrimary: ${darkScheme.primary})")
        }.onFailure {
            println("[AOT Warmup] [4/5] 图形与色彩预热警告: ${it.message}")
        }
    }

    private fun warmupScreensAndRegistry() {
        runCatching {
            // 预热设置树元数据（全量构建所有分类与入口）
            val entries = settingsEntries()

            // 实例化核心导航 Screen 页面类（触发静态初始化块与伴生对象）
            val dummyPlaylist = PlaylistSummary(
                id = 0L,
                name = "AOT Warmup Playlist",
                coverUrl = null,
                trackCount = 0,
                creatorName = "CPPlayer"
            )
            val screens = listOf(
                MainScreen(),
                HomeScreen(),
                PlaylistDetailScreen(playlist = dummyPlaylist),
                SearchScreen(),
                SettingsScreen(),
                AudioEffectSettingsScreen()
            )

            println("[AOT Warmup] [5/5] 核心 UI 页面与设置树注册表预热完成 (Screens: ${screens.size}, Settings: ${entries.size})")
        }.onFailure {
            println("[AOT Warmup] [5/5] 页面与注册表预热警告: ${it.message}")
        }
    }
}
