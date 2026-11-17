package cp.player.core.playback.drm

import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 发现并启动一个可承载 EME（Widevine CDM）的 Chromium 内核窗口。
 *
 * Windows 上没有面向普通桌面应用的系统级 Widevine 接口，只能借浏览器内核：
 * 完整安装的 Edge / Chrome 均自带 Widevine；只有 WebView2 Runtime 的机器
 * （EdgeCore 目录）也附带 msedge.exe 与 WidevineCdm，作为最后候选。
 * 找不到任何内核时 DRM 能力位保持为空，宿主回退明文 preview。
 */
object WebViewDrmBrowser {

    /** 固定安装路径候选，按优先级排列。 */
    private val windowsCandidates: List<String> = listOf(
        "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
        "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
        "C:/Program Files/Google/Chrome/Application/chrome.exe",
        "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
        "$LOCAL_APPDATA/Google/Chrome/Application/chrome.exe",
    )

    /** Linux 桌面兜底（Chrome/Chromium 同样自带 Widevine）。 */
    private val linuxCandidates: List<String> = listOf(
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
    )

    /** 版本号目录家族：<root>/<version>/<exeRelative>，取最高版本。 */
    private val windowsVersionedFamilies: List<VersionedBrowserFamily> = listOf(
        VersionedBrowserFamily(
            root = "C:/Program Files (x86)/Microsoft/EdgeCore",
            exeRelative = "msedge.exe",
        ),
        VersionedBrowserFamily(
            root = "C:/Program Files (x86)/Microsoft/EdgeWebView/Application",
            exeRelative = "msedgewebview2.exe",
        ),
    )

    data class VersionedBrowserFamily(val root: String, val exeRelative: String)

    val isAvailable: Boolean
        get() = findExecutable() != null

    fun findExecutable(): String? = findExecutable(platformCandidates(), platformFamilies())

    /**
     * 供测试注入候选；生产走默认常量。固定路径按序探测，版本目录取最高版本。
     */
    fun findExecutable(fixed: List<String>, families: List<VersionedBrowserFamily>): String? {
        for (candidate in fixed) {
            if (File(candidate).isFile) return candidate
        }
        for (family in families) {
            latestVersionExecutable(family)?.let { return it }
        }
        return null
    }

    private fun latestVersionExecutable(family: VersionedBrowserFamily): String? {
        val root = File(family.root)
        if (!root.isDirectory) return null
        return root.listFiles { file -> file.isDirectory && file.name.firstOrNull()?.isDigit() == true }
            ?.maxByOrNull { it.name }
            ?.let { versionDir -> File(versionDir, family.exeRelative) }
            ?.takeIf { it.isFile }
            ?.absolutePath
    }

    /**
     * 以 app 模式启动一个离屏小窗口加载播放页。
     *
     * - `--app`：独立应用窗口，无地址栏/书签干扰；
     * - 窗口定位到屏幕外：宿主只需要音频输出与 CDM，不需要可视 UI；
     * - 三个 `--disable-*-background*`：离屏窗口会被 Chromium 判定为后台页而
     *   节流定时器/渲染，hls.js 的分片装载会因此停滞，必须显式关闭；
     * - `--autoplay-policy=no-user-gesture-required`：页面无用户手势，
     *   否则首次 play() 会被自动播放策略拒绝；
     * - `--user-data-dir`：独立 profile，避免污染用户浏览器会话。
     */
    fun launch(executable: Path, pageUrl: String, userDataDir: Path): Process {
        val arguments = listOf(
            executable.toString(),
            "--app=$pageUrl",
            "--user-data-dir=${userDataDir.normalize().toAbsolutePath()}",
            "--window-size=480,120",
            "--window-position=-32000,-32000",
            "--no-first-run",
            "--no-default-browser-check",
            "--noerrdialogs",
            "--disable-features=Translate,MediaRouter",
            "--autoplay-policy=no-user-gesture-required",
            "--disable-background-timer-throttling",
            "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding",
            pageUrl,
        )
        val process = ProcessBuilder(arguments).start()
        // 启动即退（路径失效 / 不支持某参数）要在这里暴露出来，
        // 否则调用方会傻等一个永远不会连上的播放页。
        if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() != 0) {
            throw IOException("浏览器内核启动失败（退出码 ${process.exitValue()}）")
        }
        return process
    }

    private fun platformCandidates(): List<String> {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("win") -> windowsCandidates
            os.contains("linux") -> linuxCandidates
            else -> emptyList()
        }
    }

    private fun platformFamilies(): List<VersionedBrowserFamily> =
        if (System.getProperty("os.name", "").lowercase().contains("win")) {
            windowsVersionedFamilies
        } else {
            emptyList()
        }

    private val LOCAL_APPDATA: String
        get() = System.getenv("LOCALAPPDATA") ?: "C:/Users/Unknown/AppData/Local"
}
