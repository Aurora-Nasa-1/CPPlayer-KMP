package cp.player.app.ui.screen

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.playback.PlaybackSessionSettings
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 音频设置页出图探针。
 *
 * 这一页的难点**全在平台差异**，而平台在真机上换不了 —— 编译和单测都量不到
 * 「Windows 的独占行有没有写错平台」「Linux 上是不是还在提 WASAPI」。
 * 这里把三种形态各渲一张 PNG 落到 `app/build-preview-audio-engine/`，人工核对文案；
 * 机器只能判两件硬事：
 * 1. 渲染不抛异常（约束冲突 / 无限高度会在这里暴露）；
 * 2. 画面里真的有内容（否则「什么都没画」会静默通过）。
 */
class AudioEngineSettingsVisualTest {

    @Test
    fun `三种平台形态都能渲染出音频设置页`() {
        data class Shot(val name: String, val state: AudioEngineUiState, val light: Boolean = true)

        val shots = listOf(
            // Windows：默认共享（新装用户看到的第一个状态 —— 也必须是「不独占」）
            Shot(
                "windows-shared-default",
                AudioEngineUiState(isAndroid = false, isWindowsDesktop = true, vipActivated = false),
            ),
            // Windows：VIP 已激活但**没开**独占 —— 最容易写错的一格（不能显示「生效中」）
            Shot(
                "windows-vip-but-shared",
                AudioEngineUiState(
                    isAndroid = false,
                    isWindowsDesktop = true,
                    vipActivated = true,
                    vipPlanName = "Hi-Res 专业发烧版 (永久授权)",
                    vipLicenseKey = "CP88-ABCD-1234-5678",
                ),
            ),
            // Windows：VIP + 独占开启 = Bit-Perfect 生效
            Shot(
                "windows-exclusive-active",
                AudioEngineUiState(
                    isAndroid = false,
                    isWindowsDesktop = true,
                    vipActivated = true,
                    vipPlanName = "Hi-Res 专业发烧版 (永久授权)",
                    vipLicenseKey = "CP88-ABCD-1234-5678",
                    exclusivePref = true,
                ),
                light = false,
            ),
            // 其它桌面：没有 WASAPI，不该出现独占开关
            Shot(
                "linux-shared",
                AudioEngineUiState(isAndroid = false, isWindowsDesktop = false, vipActivated = false),
            ),
            // Android：系统内核
            Shot(
                "android-media3",
                AudioEngineUiState(isAndroid = true, isWindowsDesktop = false, vipActivated = false),
            ),
            // Android：FlickEngine 生效
            Shot(
                "android-flick",
                AudioEngineUiState(
                    isAndroid = true,
                    isWindowsDesktop = false,
                    vipActivated = true,
                    vipPlanName = "Hi-Res 专业发烧版 (永久授权)",
                    engineKey = PlaybackSessionSettings.ENGINE_FLICK_HIRES,
                ),
            ),
        )

        shots.forEach { shot ->
            val ink = render(shot.name, shot.light, shot.state)
            println("[audio-engine] ${shot.name} 非背景像素占比=$ink")
            assertTrue(ink > 0.05f, "${shot.name} 几乎什么都没画（覆盖率 $ink）")
        }
    }

    /** 返回「非纯色背景」像素占比；同时把 PNG 写盘供人工核对。 */
    private fun render(name: String, light: Boolean, state: AudioEngineUiState): Float {
        val scene = ImageComposeScene(width = 900, height = 1180, density = Density(1f)) {
            CpTheme(themeMode = if (light) ThemeMode.LIGHT else ThemeMode.DARK) {
                AudioEngineSettingsContent(
                    state = state,
                    onSelectEngine = {},
                    onSelectExclusive = {},
                    onOpenVipDialog = {},
                    onOpenAudioEffect = {},
                    onOpenFlickHome = {},
                    onBack = {},
                )
            }
        }
        try {
            scene.render()
            val data = scene.render().encodeToData() ?: error("encode failed")
            val png = File("build-preview-audio-engine/$name.png").apply { parentFile.mkdirs() }
            png.writeBytes(data.bytes)

            val img = ImageIO.read(png)
            val background = img.getRGB(0, 0)
            var ink = 0
            var total = 0
            var x = 0
            while (x < img.width) {
                var y = 0
                while (y < img.height) {
                    if (img.getRGB(x, y) != background) ink++
                    total++
                    y += 2
                }
                x += 2
            }
            return ink.toFloat() / total.toFloat()
        } finally {
            scene.close()
        }
    }
}
