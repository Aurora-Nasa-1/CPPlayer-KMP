package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.component.MonetIcon
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test

/**
 * 临时：核对「表单页容器」在浅色 / 深色下的版面与配色是否真的统一（渲完必删）。
 *
 * 量三件事：
 * 1. 正文是否居中且左右留白对称（= `formMaxWidth` 720dp 真的生效）；
 * 2. 分组标题 / 说明条 / 行内标题的左边缘是否落在同一条竖线上（= 页面内边距与行内边距同值）；
 * 3. 行底色在两种主题下是否都浮在页面背景之上、且「当前选中」那行在左栏里是否读得出来。
 */
class SettingsLayoutPreviewTest {

    @Composable
    private fun FormBody() {
        SettingsSection("通用") {
            SettingsClickItem(
                title = "外观与主题",
                subtitle = "主题模式、取色来源与纯黑背景",
                icon = Icons.Filled.Info,
                index = 0,
                total = 3,
                onClick = {},
            )
            SettingsClickItem(
                title = "播放与音质",
                subtitle = "默认音质与睡眠定时",
                icon = Icons.Filled.Person,
                index = 1,
                total = 3,
                onClick = {},
            )
            SettingsClickItem(
                title = "下载与存储",
                subtitle = "下载目录与图片缓存",
                icon = Icons.Filled.Storage,
                index = 2,
                total = 3,
                onClick = {},
            )
        }
        SettingsNote("说明条：左边缘应与分组标题、行内标题落在同一条竖线上。")
        SettingsSection("其他") {
            SettingsClickItem(title = "关于与支持", subtitle = "版本、更新与项目支持", index = 0, total = 2, onClick = {})
            SettingsClickItem(title = "诊断", subtitle = "查看接口调用状态、日志与回退信息", index = 1, total = 2, onClick = {})
        }
    }

    @Composable
    private fun FormPage() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            SettingsPage(Modifier.fillMaxSize()) { FormBody() }
        }
    }

    @Composable
    private fun LazyFormPage() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            SettingsLazyPage(
                pageModifier = Modifier.fillMaxSize(),
                header = {
                    SettingsClickItem(title = "综合状态：健康", subtitle = "最近 100 条综合判定 · 共 12 条记录", index = 0, total = 1)
                },
            ) {
                items(6) { index ->
                    SettingsClickItem(
                        title = "GET · provider-$index",
                        subtitle = "12:0$index:30 · 128ms · OK",
                        icon = Icons.Filled.BugReport,
                        index = index,
                        total = 6,
                        onClick = {},
                    )
                }
            }
        }
    }

    /** 宽屏双栏：左栏里「当前选中」那行用的是 [cp.player.app.ui.component.settingsRowHighlight]。 */
    @Composable
    private fun TwoPane() {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(320.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
                Column(
                    Modifier.fillMaxWidth().padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                    ),
                ) {
                    SettingsSection("通用") {
                        SettingsClickItem(
                            title = "外观与主题",
                            subtitle = "主题模式、取色来源与纯黑背景",
                            index = 0,
                            total = 3,
                            selected = true,
                            onClick = {},
                            leadingContent = {
                                MonetIcon(
                                    Icons.Filled.Info,
                                    MaterialTheme.colorScheme.primaryFixed,
                                    MaterialTheme.colorScheme.onPrimaryFixed,
                                )
                            },
                        )
                        SettingsClickItem(
                            title = "播放与音质",
                            subtitle = "默认音质与睡眠定时",
                            index = 1,
                            total = 3,
                            onClick = {},
                            leadingContent = {
                                MonetIcon(
                                    Icons.Filled.Person,
                                    MaterialTheme.colorScheme.secondaryFixed,
                                    MaterialTheme.colorScheme.onSecondaryFixed,
                                )
                            },
                        )
                        SettingsClickItem(
                            title = "下载与存储",
                            subtitle = "下载目录与图片缓存",
                            index = 2,
                            total = 3,
                            onClick = {},
                            leadingContent = {
                                MonetIcon(
                                    Icons.Filled.Storage,
                                    MaterialTheme.colorScheme.primaryFixed,
                                    MaterialTheme.colorScheme.onPrimaryFixed,
                                )
                            },
                        )
                    }
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                SettingsPage(Modifier.fillMaxSize()) { FormBody() }
            }
        }
    }

    private fun render(name: String, width: Int, height: Int, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                content()
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
        render("settings-form-light", 1400, 900, dark = false) { FormPage() }
        render("settings-form-dark", 1400, 900, dark = true) { FormPage() }
        render("settings-lazy-light", 1400, 900, dark = false) { LazyFormPage() }
        render("settings-twopane-light", 1400, 900, dark = false) { TwoPane() }
        render("settings-twopane-dark", 1400, 900, dark = true) { TwoPane() }
    }
}
