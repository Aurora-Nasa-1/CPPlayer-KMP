package cp.player.app

import cp.player.app.platform.DesktopSplashWindow

/**
 * 手机版界面预览的独立启动入口。
 *
 * 开发者可直接在 IDE (IntelliJ IDEA / Android Studio) 中右键本文件或点击 main 函数旁的
 * 运行按钮（Run 'MainMobileKt'）直接以手机版尺寸与布局运行桌面端。
 */
fun main(args: Array<String>) {
    DesktopSplashWindow.show(isMobilePreview = true)
    System.setProperty("cp.player.mobilePreview", "true")
    if (System.getProperty("cp.player.releaseChannel").isNullOrBlank()) {
        System.setProperty("cp.player.releaseChannel", "debug")
    }
    runDesktopApp(args, isMobilePreview = true)
}
