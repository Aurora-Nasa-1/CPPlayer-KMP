package cp.player.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.component.bentoOutline
import cp.player.app.ui.component.MonetIcon
import cp.player.app.ui.component.SettingsClickItem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 表面层级的离屏出图探针。
 *
 * 颜色改动**只能靠眼睛验收**：编译过、单测过都不代表「浅色下卡片还看得见」
 * 「纯黑下卡片不是贴在黑底上的灰板」。这个探针把页面 / 卡片 / 选中行 / 内嵌槽 /
 * 浮层 / 浮层内块放在同一屏里，三种模式各出一张 PNG。
 *
 * 产物：`app/build/visual/ladder-*.png`（不参与断言，断言只保证图确实渲染出来了）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class SurfaceLadderVisualTest {

    private fun render(
        mode: ThemeMode,
        pureBlack: Boolean,
        fileName: String,
        colorSource: ColorSource = ColorSource.FIXED,
        coverSeed: Color? = null,
    ) {
        val scene = ImageComposeScene(width = 430, height = 640, density = Density(1f)) {
            CpTheme(
                themeMode = mode,
                colorSource = colorSource,
                pureBlack = pureBlack,
                coverSeed = coverSeed,
            ) {
                ProvideCpStrings(AppLanguage.ZH_HANS) { LadderProbe() }
            }
        }
        val bytes = try {
            scene.render().encodeToData()?.bytes ?: error("PNG 编码失败")
        } finally {
            scene.close()
        }
        val target = File("build/visual/$fileName")
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        assertTrue(bytes.size > 1024, "$fileName 渲染结果太小，可能是一张空图")
    }

    @Test
    fun `浅色层级`() = render(ThemeMode.LIGHT, pureBlack = false, fileName = "ladder-light.png")

    @Test
    fun `深色层级`() = render(ThemeMode.DARK, pureBlack = false, fileName = "ladder-dark.png")

    @Test
    fun `纯黑层级`() = render(ThemeMode.DARK, pureBlack = true, fileName = "ladder-oled.png")

    /**
     * 复现「脏」的那组：跟随封面 + 一个褐色种子。
     *
     * 用户实际截图里页面 #130C0C / 卡片 #211717 / 正文 #FFDAD9 全是暖色相 ——
     * 表面去染色就是为这一组加的，所以必须有一张图盯着它。
     */
    @Test
    fun `褐色封面种子的深色层级`() = render(
        mode = ThemeMode.DARK,
        pureBlack = false,
        fileName = "ladder-seed-brown-dark.png",
        colorSource = ColorSource.COVER,
        coverSeed = Color(0xFF8B5E3C),
    )

    @Test
    fun `褐色封面种子的浅色层级`() = render(
        mode = ThemeMode.LIGHT,
        pureBlack = false,
        fileName = "ladder-seed-brown-light.png",
        colorSource = ColorSource.COVER,
        coverSeed = Color(0xFF8B5E3C),
    )

    private fun renderMoreSheet(
        mode: ThemeMode,
        pureBlack: Boolean,
        fileName: String,
        onDislike: (() -> Unit)? = null,
    ) {
        val scene = ImageComposeScene(width = 412, height = 700, density = Density(1f)) {
            CpTheme(
                themeMode = mode,
                colorSource = ColorSource.FIXED,
                pureBlack = pureBlack,
            ) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Surface(
                            shape = CpShapes.sheet,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            cp.player.app.ui.component.SongOptionsSheetContent(
                                songName = "夜曲",
                                artistName = "周杰伦 · 十一月的萧邦",
                                isFavorite = false,
                                isDownloaded = false,
                                onDismiss = {},
                                onPlay = {},
                                onToggleFavorite = {},
                                onAddToQueue = {},
                                onPlayNext = {},
                                onAddToPlaylist = {},
                                onDownload = {},
                                onDislike = onDislike,
                            )
                        }
                    }
                }
            }
        }
        val bytes = try {
            scene.render().encodeToData()?.bytes ?: error("PNG 编码失败")
        } finally {
            scene.close()
        }
        val target = File("build/visual/$fileName")
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        assertTrue(bytes.size > 1024, "$fileName 渲染结果太小，可能是一张空图")
    }

    @Test
    fun `歌曲更多弹层深色`() = renderMoreSheet(ThemeMode.DARK, pureBlack = false, "more-sheet-dark.png")

    @Test
    fun `歌曲更多弹层浅色`() = renderMoreSheet(ThemeMode.LIGHT, pureBlack = false, "more-sheet-light.png")

    @Test
    fun `歌曲更多弹层纯黑`() = renderMoreSheet(ThemeMode.DARK, pureBlack = true, "more-sheet-oled.png")

    @Test
    fun `私人 FM 通用歌曲更多弹层`() = renderMoreSheet(
        ThemeMode.DARK,
        pureBlack = false,
        fileName = "personal-fm-song-options.png",
        onDislike = {},
    )

    private fun renderMiniPlayer(mode: ThemeMode, pureBlack: Boolean, fileName: String) {
        val scene = ImageComposeScene(width = 430, height = 200, density = Density(1f)) {
            CpTheme(themeMode = mode, pureBlack = pureBlack) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(cpPageSurface()).padding(12.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shadowElevation = if (LocalIsDarkTheme.current) 4.dp else 2.dp,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (LocalIsDarkTheme.current) 0.12f else 0.20f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            cp.player.app.ui.component.MiniPlayerDesktopBar(
                                state = cp.player.core.playback.PlaybackUiState(
                                    positionMs = 60_000L,
                                    durationMs = 240_000L,
                                    isPlaying = true,
                                ),
                                isFavorite = false,
                                qualityLevel = "HQ",
                                cover = {
                                    Box(
                                        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                    )
                                },
                                info = {
                                    Column {
                                        Text("夜曲", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                        Text("为你弹奏萧邦的夜曲", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onTogglePlay = {},
                                onSkipPrev = {},
                                onSkipNext = {},
                                onShuffle = {},
                                onRepeat = {},
                                onLike = {},
                                onQueue = {},
                                onMore = {},
                                onVolumeChange = {},
                                onQualityChange = {},
                            )
                        }
                    }
                }
            }
        }
        val bytes = try {
            scene.render().encodeToData()?.bytes ?: error("PNG 编码失败")
        } finally {
            scene.close()
        }
        val target = File("build/visual/$fileName")
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        assertTrue(bytes.size > 1024, "$fileName 渲染结果太小，可能是一张空图")
    }

    @Test
    fun `迷你播放器深色`() = renderMiniPlayer(ThemeMode.DARK, pureBlack = false, "mini-player-dark.png")

    @Test
    fun `迷你播放器浅色`() = renderMiniPlayer(ThemeMode.LIGHT, pureBlack = false, "mini-player-light.png")

    @Test
    fun `迷你播放器纯黑`() = renderMiniPlayer(ThemeMode.DARK, pureBlack = true, "mini-player-oled.png")
}

@Composable
private fun LadderProbe() {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cpPageSurface())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("页面 · surface", color = onSurface, style = MaterialTheme.typography.titleSmall)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cpCardSurface()),
        ) {
            SettingsClickItem(title = "卡片 · 普通行", index = 0, total = 3)
            SettingsClickItem(title = "卡片 · 选中行（当前生效）", index = 1, total = 3, selected = true)
            SettingsClickItem(title = "卡片 · 第三行", index = 2, total = 3, showChevron = true)
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(cpNestedSurface()),
                contentAlignment = Alignment.Center,
            ) {
                Text("内嵌槽 · nested", color = onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        // 设置首页分类入口：彩色 Fixed 图标块 + 选中行。
        // 钉住「选中时图标块透明化，不与 secondaryContainer 行底叠两个容器」。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cpCardSurface()),
        ) {
            SettingsClickItem(
                title = "未选中 · 彩色图标块",
                index = 0,
                total = 2,
                leadingContent = {
                    MonetIcon(
                        icon = Icons.Rounded.Tune,
                        containerColor = MaterialTheme.colorScheme.primaryFixed,
                        contentColor = MaterialTheme.colorScheme.onPrimaryFixed,
                    )
                },
            )
            SettingsClickItem(
                title = "选中 · 图标块应透明",
                index = 1,
                total = 2,
                selected = true,
                leadingContent = {
                    MonetIcon(
                        icon = Icons.Rounded.Tune,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cpCardSurface(),
            border = bentoOutline(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Bento 卡片 · outlineVariant 描边",
                color = onSurface,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = cpSheetSurface(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("浮层 · overlay", color = onSurface, style = MaterialTheme.typography.titleSmall)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(cpSheetInnerSurface()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("浮层内块 · overlayInner", color = onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "强调块 · primaryContainer",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "强调块 · tertiaryContainer",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
        Text("正文 · onSurface", color = onSurface, style = MaterialTheme.typography.bodyMedium)
        Text("次要文字 · onSurfaceVariant", color = onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Text("描边 · outlineVariant", color = Color.Unspecified, style = MaterialTheme.typography.labelSmall)
    }
}
