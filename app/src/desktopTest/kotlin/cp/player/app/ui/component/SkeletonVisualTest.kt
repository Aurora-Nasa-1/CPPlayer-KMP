package cp.player.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 骨架屏出图探针：只回答一件事 —— **这些灰块摆出来是不是真的像那个页面**。
 *
 * 编译与单测都量不到「封面是不是 220dp、行距会不会挤在一起」，所以这里把每个
 * 骨架块直接渲成 PNG 落在 `app/build-preview-skeleton/`，人工核对结构。
 *
 * 断言只保留两条**硬**约束（能机器判的）：
 * 1. 渲染过程不能抛异常（约束冲突、除零、无限高度都会在这里暴露）；
 * 2. 画面里必须真的有内容（非纯背景色像素占比够高），否则「骨架没画出来」也会
 *    静默通过。
 */
class SkeletonVisualTest {

    @Test
    fun `各页面骨架都能渲染且确实画出了东西`() {
        data class Shot(
            val name: String,
            val width: Int,
            val height: Int,
            val light: Boolean = false,
            val body: @Composable () -> Unit,
        )

        val shots = listOf(
            Shot("album-detail-wide", 1280, 860) {
                AlbumDetailSkeletonFixture(wide = true)
            },
            Shot("album-detail-narrow", 420, 900) {
                AlbumDetailSkeletonFixture(wide = false)
            },
            Shot("playlist-detail-narrow", 420, 900) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    CpPlaylistHeroSkeleton()
                    CpSongRowSkeleton(rows = 6)
                }
            },
            Shot("user-profile", 420, 900) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    CpProfileHeroSkeleton()
                    CpSongRowSkeleton(rows = 3)
                    CpCardRowSkeleton(rows = 2, thumbSize = 60.dp)
                }
            },
            Shot("search-songs", 1280, 860) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    CpSkeletonCaption("正在从当前音源查找内容")
                    CpSectionHeaderSkeleton(titleWidth = 96.dp, subtitleWidth = 44.dp)
                    CpSongRowSkeleton(rows = 7)
                }
            },
            Shot("search-albums", 420, 900) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    CpCardRowSkeleton(rows = 5, thumbSize = 60.dp)
                }
            },
            Shot("search-artists", 420, 900) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    CpCardRowSkeleton(rows = 5, thumbSize = 56.dp, circular = true)
                }
            },
            Shot("contacts", 420, 900) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    CpContactListSkeleton(rows = 6)
                }
            },
            // 浅色主题单独出一张：卡片行靠「卡片 Low / 块 Highest」拉开对比，
            // 而浅色下 Highest 是**更深**的一档 —— 深色下好看不代表浅色下也成立。
            Shot("light-search-albums", 420, 900, light = true) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    CpCardRowSkeleton(rows = 3, thumbSize = 60.dp)
                }
            },
            Shot("chat", 420, 900) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    CpChatBubblesSkeleton(rows = 6)
                }
            },
        )

        shots.forEach { shot ->
            val ink = render(shot.name, shot.width, shot.height, shot.light, shot.body)
            println("[skeleton] ${shot.name} 非背景像素占比=$ink")
            assertTrue(ink > 0.05f, "${shot.name} 几乎什么都没画（覆盖率 $ink）—— 骨架可能没挂上")
        }
    }

    /** 返回「非纯色背景」像素占比；同时把 PNG 写盘供人工核对。 */
    private fun render(
        name: String,
        width: Int,
        height: Int,
        light: Boolean,
        body: @Composable () -> Unit,
    ): Float {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = if (light) ThemeMode.LIGHT else ThemeMode.DARK) { body() }
        }
        try {
            scene.render()
            val data = scene.render().encodeToData() ?: error("encode failed")
            val png = File("build-preview-skeleton/$name.png").apply { parentFile.mkdirs() }
            png.writeBytes(data.bytes)

            val img = ImageIO.read(png)
            // 背景色取左上角像素 —— 骨架页面没有铺满的装饰层，这里一定是页面底色。
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

/** 专辑详情骨架的调用形态（与 `AlbumDetailScreen` 加载分支一致）。 */
@Composable
private fun AlbumDetailSkeletonFixture(wide: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            PaddingValues(start = CpSpacing.pageHorizontal, end = CpSpacing.pageHorizontal),
        ),
    ) {
        CpSkeletonCaption("正在从当前音源读取专辑信息")
        CpDetailHeroSkeleton(wide = wide)
        CpSectionHeaderSkeleton(titleWidth = 64.dp, subtitleWidth = 150.dp, trailing = true)
        CpSongRowSkeleton(rows = 8)
    }
}
