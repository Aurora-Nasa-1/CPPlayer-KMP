package cp.player.app.ui.preview

import cp.player.app.i18n.CpStrings
import cp.player.app.ui.component.TrayMenuAction
import cp.player.app.platform.TrayMenuModel
import cp.player.app.platform.TrayMenuScene
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.TrackSummary
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * 托盘菜单的**离屏出图**。
 *
 * 渲的是真实链路：`TrayMenuScene` → `ImageComposeScene`，和托盘上弹出时同一份代码，
 * 所以字体、Material 图标、圆角、Skia 的真实投影都是最终值。
 *
 * 要盯的三件事：
 * 1. 投影有没有被 `ImageComposeScene` 的边界裁掉（四周留白 12dp 就是给它用的）；
 * 2. 中文 / 日文有没有落到项目字库（AWT 手绘那版在这里是方块或系统字体）；
 * 3. 选中态与 hover 态的层次是否清楚的区分开（两者底色不同）。
 *
 * 产物：`build-tray-menu-preview/`（`build-*` 已被 `.gitignore` 覆盖）。
 */
class TrayMenuPopupPreviewTest {

    private val track = TrackSummary(
        id = "preview-1",
        name = "夜に駆ける",
        artist = "YOASOBI",
        album = "THE BOOK",
        coverUrl = null,
        durationMs = 261_000L,
    )

    @Test
    fun `render tray menu`() {
        render(
            name = "tray-menu-dark-playing",
            track = track,
            isPlaying = true,
            isMiniMode = false,
            hovered = null,
            mode = ThemeMode.DARK,
        )
        render(
            name = "tray-menu-dark-hover-mini",
            track = track,
            isPlaying = false,
            isMiniMode = true,
            hovered = TrayMenuAction.TOGGLE_MINI_MODE,
            mode = ThemeMode.DARK,
        )
        render(
            name = "tray-menu-light-hover-exit",
            track = track,
            isPlaying = true,
            isMiniMode = false,
            hovered = TrayMenuAction.EXIT,
            mode = ThemeMode.LIGHT,
        )
        render(
            name = "tray-menu-idle",
            track = null,
            isPlaying = false,
            isMiniMode = false,
            hovered = null,
            mode = ThemeMode.DARK,
        )
    }

    private fun render(
        name: String,
        track: TrackSummary?,
        isPlaying: Boolean,
        isMiniMode: Boolean,
        hovered: TrayMenuAction?,
        mode: ThemeMode,
    ) {
        val strings = CpStrings.zh.messageNotify
        val model = TrayMenuModel(
            title = track?.name ?: strings.trayNothingPlaying,
            subtitle = track?.artist?.takeIf { it.isNotBlank() },
            isPlaying = isPlaying,
            isMiniMode = isMiniMode,
            strings = strings,
        )
        // 2 倍密度出图：既看清细节，也是 HiDPI 屏上的真实观感。
        val scene = TrayMenuScene(density = 2f, model = model, themeModeOverride = mode)
        try {
            val menu = scene.render(hovered)
            // 贴到一块模拟桌面的底色上，才看得出投影与圆角是否成立。
            val pad = 32
            val canvas = BufferedImage(menu.width + pad * 2, menu.height + pad * 2, BufferedImage.TYPE_INT_ARGB)
            val g = canvas.createGraphics()
            try {
                g.color = Color(0x1B, 0x1B, 0x1F)
                g.fillRect(0, 0, canvas.width, canvas.height)
                g.color = Color(0x0C, 0x0C, 0x0E)
                g.fillRect(0, canvas.height - 64, canvas.width, 64)
                g.drawImage(menu, pad, pad, null)
            } finally {
                g.dispose()
            }
            File("build-tray-menu-preview").mkdirs()
            ImageIO.write(canvas, "png", File("build-tray-menu-preview/$name.png"))
        } finally {
            scene.close()
        }
    }
}
