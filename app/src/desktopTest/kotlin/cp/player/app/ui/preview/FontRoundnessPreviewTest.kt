package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test

/**
 * 核对「外观与主题」新增的**字体圆滑度**分组（滑杆行 + 恢复默认行 + 说明条）：
 *
 * 1. 滑杆行是「标题 + 副标题 + 右上值标签 + 滑轨」四段，滑轨宽度应与行内文字左边缘对齐；
 * 2. 有自定义值时「恢复平台默认」行与滑杆行拼成一张分段卡片（index/total 连续）；
 * 3. 浅色 / 深色下滑杆轨道与行容器色都要浮在页面背景之上。
 */
class FontRoundnessPreviewTest {

    @Composable
    private fun Body(customized: Boolean) {
        SettingsPage(Modifier.fillMaxSize()) {
            SettingsSection("字体") {
                SettingsSliderItem(
                    title = "字体圆滑度",
                    subtitle = "Google Sans Flex 的 ROND 可变轴：0 方正、100 最圆润。" +
                        "Android 16 及以上默认 100，其余平台默认 0",
                    value = if (customized) 50f else 0f,
                    onValueChange = {},
                    valueRange = 0f..100f,
                    steps = 19,
                    onValueChangeFinished = {},
                    valueLabel = if (customized) "50" else "0 · 默认",
                    index = 0,
                    total = if (customized) 2 else 1,
                )
                if (customized) {
                    SettingsButtonItem(
                        text = "恢复平台默认",
                        subtitle = "清除自定义值，回到当前平台的默认圆滑度",
                        index = 1,
                        total = 2,
                        onClick = {},
                    )
                }
            }
            SettingsNote(
                "字体圆滑度改动即时生效，会应用到整个界面的拉丁字符；" +
                    "中文字形来自系统回退字体，不受此设置影响。"
            )
        }
    }

    private fun render(name: String, dark: Boolean, customized: Boolean) {
        val scene = ImageComposeScene(width = 1400, height = 900, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Body(customized)
                }
            }
        }
        try {
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("build-verify/preview").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderPreviews() {
        render("font-roundness-default-light", dark = false, customized = false)
        render("font-roundness-customized-light", dark = false, customized = true)
        render("font-roundness-customized-dark", dark = true, customized = true)
    }
}
