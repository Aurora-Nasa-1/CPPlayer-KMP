package cp.player.app

fun main(args: Array<String>) {
    // 与 Main.kt 同一条纪律：钩子路径不建窗、不初始化，立刻以 0 退出。见 VelopackHooks.kt。
    exitIfVelopackHook(args)
    System.setProperty("cp.player.mobilePreview", "true")
    if (System.getProperty("cp.player.releaseChannel").isNullOrBlank()) {
        System.setProperty("cp.player.releaseChannel", "debug")
    }
    runDesktopApp(args, isMobilePreview = true)
}
