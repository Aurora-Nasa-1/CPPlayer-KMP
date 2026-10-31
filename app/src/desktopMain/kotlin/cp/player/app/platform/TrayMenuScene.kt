package cp.player.app.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import cp.player.app.AppModel
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.component.TrayMenuContent
import cp.player.app.ui.component.TrayMenuAction
import cp.player.app.ui.component.TrayMenuLayout
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.awt.image.BufferedImage

/**
 * 托盘菜单的**离屏渲染器**：把 [TrayMenuContent] 渲成一张 `BufferedImage`。
 *
 * ## 为什么要离屏，而不是直接开一个 Compose 窗口
 *
 * 直接开 Compose 窗口试过，实机上窗口建出来了但内容全空（托盘这个场景里，窗口的
 * 组合/渲染循环不可靠），而且无边框 JFrame 还会在任务栏占一格。退回 AWT 手绘能显示，
 * 但拿不到项目的字体与 Material 图标 —— AWT 加载的自定义字体**不会对中文回退**，
 * 结果就是方块字或者系统字体，观感比界面差一截。
 *
 * 离屏渲染把两边拆开：**视觉 100% 走 Compose**（Google Sans Flex、Material 图标、
 * Skia 的真实高斯投影），**显示 100% 走 AWT**（`JWindow` + `POPUP` 类型，
 * 弹得出来、不进任务栏）。
 *
 * ## hover 为什么不重新 `setContent`
 *
 * `setContent` 会重建整棵组合。这里把 hover 放进 `mutableStateOf`，只改状态再
 * `render()` —— 重组范围只有读过它的那几个 composable，出图仍然是整张。
 *
 * 用完必须 [close]：`ImageComposeScene` 持有自己的 Recomposer 和 Skia 图层。
 */
internal class TrayMenuScene(
    density: Float,
    private val model: TrayMenuModel,
    /**
     * 强制指定明暗。
     *
     * 正常路径传 null（跟随应用设置）；出图测试用它一次渲出深浅两版 ——
     * 否则测试机是浅色桌面时永远只能出浅色图，暗色那一版等于没验过。
     */
    themeModeOverride: ThemeMode? = null,
) {

    private val hovered = mutableStateOf<TrayMenuAction?>(null)

    private val scene = ImageComposeScene(
        width = ((TrayMenuLayout.Width + TrayMenuLayout.Shadow * 2) * density).toInt(),
        height = ((TrayMenuLayout.Height + TrayMenuLayout.Shadow * 2) * density).toInt(),
        density = Density(density),
    ) {
        CpTheme(
            themeMode = themeModeOverride ?: AppModel.themeModeFlow.value,
            colorSource = AppModel.colorSourceFlow.value,
            pureBlack = AppModel.pureBlackFlow.value,
            coverSeed = AppModel.coverSeedFlow.value,
            wallpaperSeed = AppModel.wallpaperSeedFlow.value,
            fontRoundness = AppModel.fontRoundnessFlow.value,
            fontFaceEnabled = AppModel.fontFaceEnabledFlow.value,
        ) {
            ProvideCpStrings(AppModel.appLanguageFlow.value) {
                // 根节点必须自己撑满：`ImageComposeScene` 不会给内容任何默认尺寸，
                // 内容按 wrapContent 排的话会被裁在左上角的一小块里。
                Box(Modifier.fillMaxSize()) {
                    TrayMenuContent(
                        title = model.title,
                        subtitle = model.subtitle,
                        isPlaying = model.isPlaying,
                        isMiniMode = model.isMiniMode,
                        hovered = hovered.value,
                        strings = model.strings,
                    )
                }
            }
        }
    }

    /** 更新悬停项并重新出图。返回新的一帧（没有任何变化时也会重画，调用方自己判重）。 */
    fun render(hoveredAction: TrayMenuAction?): BufferedImage {
        hovered.value = hoveredAction
        return scene.render().toComposeImageBitmap().toAwtImage()
    }

    fun close() {
        runCatching { scene.close() }
    }
}
